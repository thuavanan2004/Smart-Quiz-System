# Proctoring Service — Design (DATN)

> **Port**: 8104 · **Ngôn ngữ**: Java 21 + Spring Boot 3.3 · **DB schema**: `proctoring`
> Thay thế cho "Cheating Detection" trong docs production. DATN scope **chỉ L1–L3**
> (tab blur, paste, timing anomaly). L4–L6 (ML behavioral, video, graph) là future work.

## 1. Trách nhiệm

**Có**:
- Consumer `cheat.event.raw.v1` từ Core (WebSocket forward event client).
- Detector rule-based L1/L2/L3.
- Aggregate alert theo sliding window + publish `cheat.alert.v1` nếu vượt ngưỡng.
- Track session heartbeat (khi không nhận event trong N giây → alert).
- REST cho teacher xem alert, mark reviewed, viết note.

**Không có**:
- Video proctoring (camera, face detection, gaze tracking) — future work.
- ML behavioral model — future work.
- Graph analysis giữa nhiều student — future work.
- Suspend attempt (Core owner). Proctoring chỉ publish alert, Core decide action.

## 2. Entity & DB

Xem `docs/database.md` §5 cho DDL. Tóm tắt:

- `proctoring.cheat_events` — raw log (append-only, audit).
- `proctoring.cheat_alerts` — aggregated rule hit, có flow review của teacher.
- `proctoring.proctoring_sessions` — session heartbeat state.
- `proctoring.outbox`, `proctoring.processed_events` — outbox + idempotent consumer.

## 3. Rule engine (L1–L3)

Rule-based, implement bằng in-memory sliding window per `attempt_id`. Window
state lưu trong Redis (key `proctoring:window:{attempt_id}`) TTL = duration của attempt.

### 3.1. L1 — Excessive tab blur

**Input events**: `TAB_BLUR`, `TAB_FOCUS`, `FULLSCREEN_EXIT`.

**Rule**:
- Đếm số lần `TAB_BLUR` trong 5 phút cuối (sliding window).
- Tổng thời gian blur trong attempt > `threshold_total_ms`.

**Thresholds** (configurable):
| Biến                      | Default |
| ------------------------- | ------- |
| `blur_count_window_5m`    | 3       |
| `blur_total_ms`           | 30000   |

**Alert severity**:
- WARN: blur_count_5m == threshold.
- HIGH: blur_count_5m > threshold OR blur_total_ms > threshold OR fullscreen_exit.

**Rule name**: `EXCESSIVE_TAB_BLUR`, `FULLSCREEN_EXIT`.

### 3.2. L2 — Paste detection

**Input events**: `PASTE`, `COPY`.

**Event data từ client**:
```json
{ "event_type": "PASTE", "data": { "target_field": "answer-position-3", "char_count": 420 } }
```

**Rule**:
- Bất kỳ `PASTE` với `char_count > small_paste_threshold` → INFO alert.
- `PASTE` với `char_count > large_paste_threshold` → HIGH alert.
- Tần suất `PASTE` > N lần / window → WARN.

**Thresholds**:
| Biến                            | Default |
| ------------------------------- | ------- |
| `small_paste_char_threshold`    | 50      |
| `large_paste_char_threshold`    | 200     |
| `paste_count_window_5m`         | 3       |

**Rule name**: `SMALL_PASTE`, `LARGE_PASTE`, `FREQUENT_PASTE`.

### 3.3. L3 — Timing anomaly

**Input events**: `TIMING_ANOMALY` từ Core (được Core tính dựa trên answer submission time).

Core tính timing per answer: `delta = answer.submitted_at - previous.submitted_at`.
Nếu `delta < min_expected_ms_per_question` → forward event.

Proctoring aggregate:
- Số lần timing anomaly trong attempt > `threshold_count` → alert.

**Default**:
| Biến                                | Default  |
| ----------------------------------- | -------- |
| `min_expected_ms_per_question`      | 3000     |
| `timing_anomaly_count_threshold`    | 3        |

**Rule name**: `FAST_ANSWER_PATTERN`.

### 3.4. Heartbeat lost

**Event**: `HEARTBEAT_LOST` từ Core (Core tự phát hiện khi WS miss 3 beat).

Proctoring chỉ log + tạo alert WARN nếu lost > 30s, HIGH nếu > 120s.

## 4. Consumer flow

```
Kafka cheat.event.raw.v1 ──► Consumer (Kafka listener)
                               │
                               ▼
                   1. Dedupe processed_events.event_id
                   2. INSERT cheat_events (raw log)
                   3. Load window state từ Redis
                   4. Apply rules L1/L2/L3
                   5. Nếu vượt ngưỡng:
                       a. INSERT cheat_alerts
                       b. INSERT outbox(cheat.alert.v1)
                   6. Update window state Redis
                   7. INSERT processed_events
                   (all in 1 TX, except Redis)
```

**Transaction boundary**: PG TX bọc bước 1, 2, 5a, 5b, 7. Redis update (bước 6) ngoài TX — nếu fail sẽ replay trong lần event kế. Trade-off chấp nhận được vì Redis chỉ là cache; lần apply kế lại load state từ `cheat_events` query (fallback).

**Fallback load**: nếu Redis miss, query `SELECT event_type, count(*), max(occurred_at) FROM cheat_events WHERE attempt_id=$1 AND occurred_at > $window GROUP BY event_type` để rebuild window.

## 5. REST API

Base URL: `http://localhost:8104/api/v1`. Yêu cầu JWT từ Auth service.

### 5.1. Teacher endpoints (`TEACHER`, `ADMIN`)

| Method | Path                                             | Mô tả                                      |
| ------ | ------------------------------------------------ | ------------------------------------------ |
| GET    | `/alerts`                                        | List alert (filter: attempt_id, severity, reviewed, date) |
| GET    | `/alerts/{id}`                                   | Chi tiết alert + linked cheat_events       |
| POST   | `/alerts/{id}/review`                            | Mark reviewed + note                       |
| GET    | `/attempts/{attempt_id}/timeline`                | Tất cả event của attempt (sorted by time) cho giáo viên replay |
| GET    | `/attempts/{attempt_id}/summary`                 | Tổng hợp: count per event_type, alert count per severity |

**POST /alerts/{id}/review**:
```json
{ "decision": "ACCEPT" | "DISMISS", "note": "Sinh viên đã giải thích..." }
```

Response `200 OK` với alert đã update.

### 5.2. Internal endpoints

Không có. Core lấy cheat alert qua Kafka consumer, không gọi REST proctoring.

(Ngoại lệ: Core `/analytics/attempts/{id}/cheat` proxy qua HTTP `GET /api/v1/attempts/{id}/summary` nếu teacher mở UI sau khi attempt đã kết thúc và Redis/WS state đã gone. REST là fallback.)

## 6. Kafka

### 6.1. Consumer

| Topic                   | Handler                    | Consumer group |
| ----------------------- | -------------------------- | -------------- |
| `cheat.event.raw.v1`    | `CheatEventHandler`        | `proctoring`   |

Consumer config:
```yaml
spring.kafka.consumer:
  group-id: proctoring
  auto-offset-reset: earliest
  enable-auto-commit: false
  properties:
    isolation.level: read_committed
    max.poll.records: 50
```

Manual ack sau commit PG TX → at-least-once.

### 6.2. Producer

| Topic              | Khi nào publish (via outbox)                               |
| ------------------ | ---------------------------------------------------------- |
| `cheat.alert.v1`   | Khi rule vượt ngưỡng                                       |

Payload:
```json
{
  "event_id": "uuid",
  "alert_id": "uuid",
  "attempt_id": "uuid",
  "student_id": "uuid",
  "rule_name": "EXCESSIVE_TAB_BLUR",
  "severity": "HIGH",
  "evidence": {
    "count": 5,
    "window_seconds": 300,
    "threshold": 3,
    "sample_event_ids": ["...", "..."]
  },
  "occurred_at": "2026-05-01T10:23:45Z"
}
```

**Outbox relayer**: giống Core (leader election Redis `lock:outbox:proctoring`, poll 5s, batch 500).

## 7. Architecture

```
┌────────────────────────────────────────────────────────┐
│ Controller layer                                        │
│  AlertController · TimelineController                   │
└─────────────────────┬──────────────────────────────────┘
                      │
┌─────────────────────▼──────────────────────────────────┐
│ Application service                                     │
│  AlertService  (query + review)                         │
│  DetectionService (rule engine, window mgmt)            │
│  TimelineService                                        │
└─────────────────────┬──────────────────────────────────┘
                      │
┌─────────────────────▼──────────────────────────────────┐
│ Infra                                                   │
│  CheatEventConsumer · OutboxRelayer                     │
│  WindowStateStore (Redis) · JwksClient                  │
│  Rules: BlurRule · PasteRule · TimingRule · HeartbeatRule│
└─────────────────────┬──────────────────────────────────┘
                      │
┌─────────────────────▼──────────────────────────────────┐
│ Repository (Spring Data JPA)                            │
│  CheatEventRepository · CheatAlertRepository            │
│  ProctoringSessionRepository · OutboxRepository         │
└─────────────────────┬──────────────────────────────────┘
                      ▼
              PostgreSQL (schema: proctoring) + Redis + Kafka
```

**Dependencies chính**:
- `spring-boot-starter-web`, `-data-jpa`, `-security`, `-validation`
- `spring-kafka`
- `spring-boot-starter-data-redis`
- `nimbus-jose-jwt` (JWKS verify)
- `flyway-core` + `flyway-database-postgresql`

## 8. Rule engine pseudocode

```java
public class BlurRule implements CheatRule {
    public Optional<Alert> apply(CheatEvent e, WindowState w, Config cfg) {
        if (e.type() != TAB_BLUR && e.type() != FULLSCREEN_EXIT) return Optional.empty();

        if (e.type() == FULLSCREEN_EXIT) {
            return Optional.of(Alert.of("FULLSCREEN_EXIT", HIGH,
                Map.of("sample_event_ids", List.of(e.id()))));
        }

        w.blurWindow5m.add(e.occurredAt());
        w.blurWindow5m.evictBefore(e.occurredAt().minusMinutes(5));
        long blurCount = w.blurWindow5m.size();
        long blurTotalMs = durationBlur(w);

        if (blurCount > cfg.blurCountWindow5m() || blurTotalMs > cfg.blurTotalMs()) {
            return Optional.of(Alert.of("EXCESSIVE_TAB_BLUR", HIGH,
                Map.of("count", blurCount, "total_ms", blurTotalMs,
                       "threshold", cfg.blurCountWindow5m(),
                       "sample_event_ids", w.recentBlurIds(3))));
        }
        if (blurCount == cfg.blurCountWindow5m()) {
            return Optional.of(Alert.of("EXCESSIVE_TAB_BLUR", WARN, ...));
        }
        return Optional.empty();
    }
}
```

Rule chain:
```java
@Transactional
public void handle(CheatEventMessage msg) {
    if (processedEventsRepo.existsById(msg.eventId())) return;
    CheatEvent saved = cheatEventRepo.save(msg.toEntity());

    WindowState w = windowStore.load(msg.attemptId())
                              .orElseGet(() -> rebuild(msg.attemptId()));

    for (CheatRule rule : rules) {
        rule.apply(saved, w, cfg).ifPresent(alert -> {
            CheatAlert a = alertRepo.save(alert.toEntity(msg.attemptId()));
            outbox.enqueue("cheat.alert.v1", msg.attemptId().toString(), a.toPayload());
        });
    }
    windowStore.save(msg.attemptId(), w);
    processedEventsRepo.insert(msg.eventId(), "cheat.event.raw.v1");
}
```

## 9. Anti-false-positive

- **Debounce**: nếu cùng rule fire trong 30s kế tiếp → không alert mới, update alert cũ (increment count).
- **Session warm-up**: 10 giây đầu của attempt bỏ qua mọi alert (tránh false positive khi load trang).
- **Alert dedupe**: UNIQUE `(attempt_id, rule_name, created_at window 1m)` — handled bằng Redis `nx`.

## 10. Test strategy

- **Unit**: mỗi rule test độc lập (feed sequence of event → expect alerts list).
- **Integration (Testcontainers PG + Redis + Kafka)**:
  - Produce 10 TAB_BLUR trong 5 phút → alert HIGH phát ra đúng 1 lần.
  - Replay test: kill service, produce lại event → không duplicate alert (processed_events).
  - Window rebuild: xóa Redis state, process event mới → rebuild từ `cheat_events`, ra đúng kết quả.
- **Performance**: 1000 event/s trên 1 attempt → consumer lag < 2s.

## 11. Cấu hình

```yaml
server.port: 8104

spring:
  datasource.url: jdbc:postgresql://localhost:5432/smartquiz?currentSchema=proctoring
  datasource.username: proctoring_app
  jpa.properties.hibernate.default_schema: proctoring
  flyway: { schemas: proctoring, default-schema: proctoring }
  kafka:
    bootstrap-servers: localhost:9092
    consumer.group-id: proctoring
  data.redis: { host: localhost, port: 6379 }

smartquiz:
  auth.jwks-url: http://localhost:8101/.well-known/jwks.json
  outbox:
    poll-interval: 5s
    batch-size: 500
  rules:
    session-warmup-seconds: 10
    alert-debounce-seconds: 30
    blur:
      count-window-5m: 3
      total-ms: 30000
    paste:
      small-char-threshold: 50
      large-char-threshold: 200
      count-window-5m: 3
    timing:
      min-expected-ms-per-question: 3000
      anomaly-count-threshold: 3
```

## 12. Observability

- Log JSON + MDC `requestId`, `attemptId`, `ruleName`.
- Metric:
  - `proctoring_events_consumed_total{event_type}`
  - `proctoring_alerts_created_total{rule, severity}`
  - `proctoring_rule_eval_latency_seconds{rule}`
  - `proctoring_window_cache_hit_ratio`
  - `proctoring_kafka_consumer_lag_seconds`

## 13. Ranh giới

| Không bao giờ                                    | Thay vào đó                          |
| ------------------------------------------------ | ------------------------------------ |
| UPDATE `core.exam_attempts.status = SUSPENDED`  | Publish alert, Core xử lý suspend    |
| Kill WebSocket của student                       | Publish alert, Core push warning     |
| Phân tích nội dung essay / câu trả lời           | Đó là AI service (detector 9.3)      |
| Gọi AI service để hỏi "có phải cheating không"   | DATN scope chỉ rule-based            |

## 14. Future work

- L4: ML behavioral model (pattern gian lận học từ dataset).
- L5: video proctoring — webcam face detection, gaze.
- L6: graph analysis — phát hiện nhóm student có pattern giống nhau.
- Export report PDF / Excel cho phụ huynh.
- Realtime dashboard cho coi thi monitor tất cả attempt cùng lúc.

Đã thiết kế chi tiết trong `docs/archive/production-design/cheating-detection-service-design.md`.
