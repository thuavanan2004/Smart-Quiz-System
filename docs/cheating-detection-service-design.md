# CHEATING DETECTION SERVICE - THIẾT KẾ CHI TIẾT

**Hệ Thống Thi Trực Tuyến Thông Minh** | Version 1.0 | Tháng 4/2026

Tài liệu này mở rộng mục "Cheating Detection Service" trong `design.md`, ở mức đủ để triển khai code production.

---

## I. TỔNG QUAN

### 1.1 Vai trò & phạm vi

Cheating Detection Service (viết tắt **CDS**) là service **an ninh hành vi** — phân tích hành vi học sinh trên 6 tầng, tính điểm rủi ro theo thời gian thực, và đẩy cảnh báo đến Exam Service để suspend attempt nếu cần.

**Nguyên tắc thiết kế:**
- **Không phải judge cuối cùng** — suspend attempt ≠ student gian lận; quyết định sau cùng phải có con người (proctor/giáo viên)
- **False positive < 0.5%** — cảnh báo sai gây tổn hại uy tín học sinh, ảnh hưởng tâm lý
- **Real-time** — phát hiện phải < 500ms để kịp ngăn chặn
- **Audit trail đầy đủ** — mọi sự kiện phải ghi lại để appeal được
- **Không ảnh hưởng bài thi** — nếu CDS down, Exam Service vẫn chạy (fail-open, chỉ mất tính năng phát hiện)
- **Separation of concerns** — CDS phát hiện + cảnh báo; Exam Service quyết định trạng thái; Proctoring Service xử lý video; giáo viên quyết định kỷ luật

| Trách nhiệm | Không thuộc phạm vi |
| ----------- | ------------------- |
| Thu thập sự kiện từ 6 tầng (L1-L6) | Ngăn chặn sự kiện (client-side prevention — thuộc Exam client lib) |
| Tính `risk_score` real-time cho mỗi attempt | Suspend attempt (Exam Service quyết định sau khi nhận alert) |
| Phát hiện tương đồng đáp án giữa học sinh (L6) | Chấm điểm / punishment |
| Phân tích hành vi: tốc độ gõ, timing patterns | Lưu video (Media Service) |
| Vision AI inference: face, phone, gaze (L5) | Train model vision (ML platform team) |
| Phát cheat alerts qua Kafka + WebSocket | Thông báo email/SMS (Notification Service) |
| Quản lý review queue cho proctor | Proctor UI (Admin frontend) |
| Appeal workflow | Chính sách kỷ luật (Academic policy — out of system) |

### 1.2 Stack công nghệ

| Thành phần | Chọn | Lý do |
| ---------- | ---- | ----- |
| Runtime | Java 21 + Spring Boot 3 | Thống nhất; Virtual Threads cho consumer throughput |
| Stream processing | Kafka Streams (real-time) + Apache Flink (complex correlation) | Flink cho L6 statistical cross-attempt; Streams đủ cho per-attempt |
| ML model serving (L4, L5) | NVIDIA Triton (GPU) + ONNX Runtime (CPU fallback) | Batching, multi-model |
| Vision inference | YOLOv8 (phone detection) + MediaPipe (face landmarks) | Nhẹ + chính xác |
| Behavior analytics | Custom rule engine + XGBoost (tuỳ chọn) | Rule đủ cho MVP; ML mở rộng sau |
| State store | Redis Cluster (hot state) + ClickHouse (analytics history) | Hot path Redis; analytics CH |
| DB | PostgreSQL (`cheat_events`, `proctoring_sessions`) | Có sẵn từ `database.md` |
| Kafka client | Spring Kafka + kafka-streams | |
| GeoIP | MaxMind GeoLite2 | Offline, không phụ thuộc network |
| IP reputation | IPQualityScore API (fallback local blocklist) | VPN/proxy detection |
| Observability | Micrometer + OpenTelemetry | |

### 1.3 Port & giao thức

| Giao thức | Port | Mục đích |
| --------- | ---- | -------- |
| HTTP REST | `3005` | Admin UI (review queue, stats) + ingest fallback |
| gRPC | `4005` | Proctor action (suspend/resume), score query |
| Actuator | `9005` | Prometheus, health |

### 1.4 Yêu cầu phi chức năng (NFR)

| Chỉ số | Mục tiêu |
| ------ | -------- |
| Event ingestion throughput | 10.000 events/s/pod |
| Event processing latency p99 | < 500ms (receive → risk_score updated + alert published) |
| False positive rate | < 0.5% (measured qua appeal resolved) |
| False negative rate (copy detection) | < 5% |
| Availability | 99.9% (nếu down: exam vẫn chạy nhưng không phát hiện) |
| Review queue SLA | Proctor phải review alert critical trong 2 phút thi đang diễn ra |
| Storage retention | `cheat_events` 24 tháng, video 12 tháng, sao chép L6 (cross-attempt) vĩnh viễn |

---

## II. KIẾN TRÚC BÊN TRONG

### 2.1 Sơ đồ lớp

```
┌──────────────────────────────────────────────────────────────┐
│  API Layer                                                   │
│  ─ ReviewController (proctor UI REST)                        │
│  ─ AppealController (student)                                │
│  ─ CheatAdminController (stats, config)                      │
│  ─ CheatGrpcService (internal)                               │
├──────────────────────────────────────────────────────────────┤
│  Event Ingestion                                             │
│  ─ KafkaCheatEventConsumer (topic: cheat.event.raw)          │
│  ─ HttpEventIngestRouter (fallback path khi WS down)         │
├──────────────────────────────────────────────────────────────┤
│  Detection Pipelines (per-layer)                             │
│  ─ L1_ClientBehaviorPipeline                                 │
│  ─ L2_BrowserIntegrityPipeline                               │
│  ─ L3_NetworkAnomalyPipeline                                 │
│  ─ L4_BehaviorAnalyticsPipeline                              │
│  ─ L5_VisionPipeline (GPU)                                   │
│  ─ L6_StatisticalCorrelationPipeline (Flink job riêng)       │
├──────────────────────────────────────────────────────────────┤
│  Scoring & Decision                                          │
│  ─ RiskScoreCalculator (weighted sum + decay)                │
│  ─ ThresholdEvaluator (0-29 / 30-59 / 60-79 / 80+)           │
│  ─ AlertDispatcher (Kafka + WebSocket + Proctor notify)      │
├──────────────────────────────────────────────────────────────┤
│  Review Workflow                                             │
│  ─ ReviewQueueManager                                        │
│  ─ DecisionEngine (confirm / dismiss / escalate)             │
│  ─ AppealWorkflow                                            │
├──────────────────────────────────────────────────────────────┤
│  Infrastructure                                              │
│  ─ PgCheatEventRepo, PgProctoringRepo                        │
│  ─ RedisRiskStore (session risk score)                       │
│  ─ ClickHouseAnalyticsClient                                 │
│  ─ KafkaConsumer/Producer                                    │
│  ─ TritonClient (vision inference)                           │
│  ─ GeoIpResolver (MaxMind)                                   │
│  ─ IpReputationClient                                        │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 Cấu trúc module

```
cheating-detection-service/
├── build.gradle.kts
├── api-grpc/
├── src/main/java/vn/smartquiz/cheat/
│   ├── CheatDetectionApplication.java
│   ├── config/                     # KafkaStreamsConfig, RedisConfig, TritonConfig
│   ├── web/
│   ├── grpc/
│   ├── ingest/
│   │   ├── kafka/
│   │   └── http/
│   ├── pipeline/
│   │   ├── l1/                     # ClientBehaviorPipeline
│   │   ├── l2/
│   │   ├── l3/
│   │   ├── l4/
│   │   ├── l5/                     # Vision
│   │   ├── l6/                     # Flink job separate module
│   │   └── common/                 # EventContext, LayerResult
│   ├── scoring/
│   │   ├── RiskScoreCalculator.java
│   │   ├── weights/                # YAML-driven config
│   │   └── ThresholdEvaluator.java
│   ├── alert/
│   │   ├── AlertDispatcher.java
│   │   └── wschannel/              # Redis pub/sub to Exam WS
│   ├── review/
│   │   ├── ReviewQueueManager.java
│   │   ├── ReviewController.java
│   │   └── AppealWorkflow.java
│   ├── domain/
│   │   ├── event/                  # CheatEvent, EventType (enum)
│   │   ├── attempt/                # AttemptRiskContext
│   │   └── policy/
│   ├── infrastructure/
│   │   ├── persistence/
│   │   ├── redis/
│   │   ├── clickhouse/
│   │   ├── kafka/
│   │   ├── triton/
│   │   └── geoip/
│   └── common/
└── src/main/flink/                 # L6 Flink job module
    └── StatisticalCorrelationJob.java
```

---

## III. 6 LAYER DETECTION

### 3.1 L1 — Client Behavior (browser events)

**Event types & base weights:**

| Event | Trigger client-side | Weight | Severity |
| ----- | ------------------ | ------ | -------- |
| `tab_switch` | `visibilitychange` API | +3 first, +5 each | low |
| `window_blur` | `blur` event | +2 first, +3 each | low |
| `fullscreen_exit` | `fullscreenchange` | +5 | medium |
| `copy_event` | `copy` / `cut` | +5 | medium |
| `paste_event` | `paste` | +7 | medium |
| `right_click` | `contextmenu` (nếu blocked) | +2 | low |
| `keyboard_shortcut` | Ctrl+C/V/U/Shift+I | +3-10 tuỳ shortcut | low-medium |
| `context_menu` | `contextmenu` attempt | +2 | low |

**Frequency multiplier:**
```
multiplier = min(3.0, 1 + 0.2 * N_recent_events_in_60s)
```
Nghĩa là xảy ra liên tiếp trong 60s → tăng weight tới 3x.

**Time decay:**
```
decayed_weight = original_weight * exp(-age_minutes / 30)
```
Sự kiện cũ giảm ảnh hưởng sau 30 phút.

### 3.2 L2 — Browser Integrity

| Event | Detection method | Weight | Severity |
| ----- | ---------------- | ------ | -------- |
| `devtools_open` | timing `debugger` statement; window size heuristic | +15 | high |
| `extension_detected` | fingerprint known extensions (Grammarly, Chegg) | +10-20 | medium-high |
| `emulator_detected` | canvas/WebGL fingerprint mismatch | +15 | high |
| `headless_browser` | navigator.webdriver + canvas | +25 | critical |
| `keyboard_blocked_shortcut` | Ctrl+Shift+I, F12 attempts | +10 | medium |

### 3.3 L3 — Network Anomaly

| Event | Detection | Weight |
| ----- | --------- | ------ |
| `ip_change` | IP khác giữa các request (trừ 1 lần reconnect hợp lệ) | +25 |
| `geolocation_change` | GeoIP khác country/city > 100km trong < 5 phút | +30 |
| `vpn_detected` | IPQualityScore + local blocklist | +20 |
| `proxy_detected` | Header `X-Forwarded-For` patterns | +15 |
| `multiple_ip` | Cùng attempt có > 2 IP khác nhau | +25 |
| `high_latency_jump` | RTT bất thường | +5 (low confidence) |

**Implementation:**
- Mỗi request HTTP/WS từ student: enrich với GeoIP
- So sánh với `attempt.geo_country`, `attempt.geo_city` (lưu khi start)
- Redis `attempt:ips:{id}` Set → bất kỳ IP mới → publish event

### 3.4 L4 — Behavior Analytics

**Typing dynamics:**
- Ghi nhận khoảng thời gian giữa các phím gõ khi trả lời
- So với baseline user (thu thập qua 5 lần thi đầu) → z-score
- Nếu |z| > 3 trên phần lớn câu → `typing_anomaly`, weight +8

**Answer speed:**
- Thời gian trung bình cho từng loại câu hỏi (theo `metadata.estimated_time_seconds`)
- Nếu student trả lời < 10% thời gian dự kiến AND đúng → `answer_speed_anomaly`, weight +6
- Nếu trả lời > 3x thời gian dự kiến AND đúng → có thể copy, weight +4

**Answer pattern:**
- Student trả lời tất cả câu đúng liên tục không sai câu nào cho exam khó → anomaly +10
- Pattern copy: thời gian nhàn rỗi rồi đột ngột trả lời nhanh đúng → +8

**Idle too long:**
- Không hoạt động > 5 phút giữa câu hỏi → `idle_too_long`, weight +3 (context: có thể đi vệ sinh, chưa chắc cheat)

Pipelined qua Kafka Streams với window aggregation:

```java
KStream<AttemptId, KeystrokeEvent> keystrokes = ...;
keystrokes
    .groupByKey()
    .windowedBy(TimeWindows.of(Duration.ofSeconds(60)))
    .aggregate(
        KeystrokeStats::new,
        (k, v, agg) -> agg.addEvent(v),
        Materialized.as("keystroke-stats")
    )
    .toStream()
    .filter((k, stats) -> stats.zScore() > 3)
    .mapValues(stats -> new TypingAnomalyEvent(...))
    .to("cheat.event.detected");
```

### 3.5 L5 — Vision (Proctoring optional)

**Trigger:** Chỉ khi `exam.proctoring_level = 2`.

**Input:** Client extract 1 fps frame → upload S3 bucket `proctoring-frames` → publish Kafka `proctoring.frame.captured`.

**Pipeline:**

```
proctoring.frame.captured
        │
        ▼
VisionInferenceWorker (batches 8 frames)
        │
        ├─► Triton: face_detection (RetinaFace)
        │     └─► output: {num_faces, bounding_boxes}
        │
        ├─► Triton: object_detection (YOLOv8)
        │     └─► detect: phone, book, earphone, second_person
        │
        ├─► Triton: gaze_estimation (MediaPipe FaceMesh)
        │     └─► angle from center → screen-on vs off
        │
        └─► Aggregate per-frame result
                │
                ▼
         Publish cheat.event.detected theo rule:
          - 0 face > 5s liên tục  → face_missing (+20)
          - >1 face                 → multiple_faces (+35)
          - phone detected          → phone_detected (+30)
          - gaze off-screen > 3s    → gaze_off_screen (+10)
          - audio_detected (khi mic) → audio_detected (+15)
```

**Batching:** 8 frames/batch → độ trễ vs throughput. GPU inference ~50ms/batch.

**Cost control:** Nếu `proctoring_level=2` trên 1000 students × 60 phút × 1fps = 3.6M frames. Cần ~10 GPU pod (A10G). Đắt — chỉ bật cho kỳ thi quan trọng.

### 3.6 L6 — Statistical Cross-Attempt (Post-exam)

Chạy **sau khi exam completed** trên Flink batch job.

**Detections:**

| Indicator | Phương pháp | Confidence |
| --------- | ----------- | ---------- |
| `answer_similarity` | Cosine similarity giữa answer vectors (MC option sequence) giữa các học sinh cùng exam | Flag nếu > 0.92 |
| `wrong_answer_match` | 2 students có cùng ≥ 5 câu sai giống nhau + cùng option sai | High confidence |
| `submission_time_cluster` | 2+ attempts submit trong < 10s, từ IP khác (có thể coordinate) | Medium |
| `score_anomaly` | Score vượt 3σ của baseline class → có thể dùng outside help | Flag, không block |
| `answer_change_pattern` | Thay đổi đáp án ngay trước khi submit nhiều lần theo pattern → bị coached | Low |

**Flink job pseudo:**

```java
DataStream<Attempt> attempts = env.addSource(kafkaSource("exam.completed"));

attempts
    .keyBy(Attempt::examId)
    .window(SessionWindows.withGap(Time.hours(1)))
    .process(new SimilarityDetector(threshold=0.92))
    .addSink(kafkaSink("cheat.statistical.result"));
```

Output: `cheat.statistical.result` → CDS consume → insert `cheat_events` với `event_layer=6`, severity `low` (chỉ báo cáo, không suspend vì exam đã xong).

---

## IV. RISK SCORE ALGORITHM

### 4.1 Công thức

```
risk_score(attempt) = Σ_events ( base_weight × frequency_mult × decay × layer_confidence )

- base_weight: bảng cố định theo event_type (3.1-3.6)
- frequency_mult: 1 + 0.2 × recent_same_type_count (cap 3.0)
- decay: exp(-age_minutes / half_life)
    L1-L2: half_life = 30 phút
    L3:   half_life = 60 phút (IP anomaly giữ lâu)
    L4:   half_life = 20 phút
    L5:   half_life = 15 phút (video sự kiện gần)
- layer_confidence: [0.0, 1.0] hệ số độ tin cậy tầng
    L1-L2: 0.8
    L3: 0.9
    L4: 0.6 (ML có thể sai)
    L5: 0.85
```

Triển khai Redis Lua atomic:

```lua
-- KEYS[1]=risk:{attempt_id}, ARGV[1]=event_json
local r = redis.call('HGET', KEYS[1], 'total')
local events = redis.call('HGET', KEYS[1], 'events')  -- JSON array
-- Append event, recompute score với decay
-- ...
redis.call('HSET', KEYS[1], 'total', new_total, 'events', new_events)
redis.call('EXPIRE', KEYS[1], 7200)  -- 2h TTL sau exam
return new_total
```

### 4.2 Thresholds

| Score | Mức | Hành động auto | Hành động đi kèm |
| ----- | --- | -------------- | ---------------- |
| 0-29 | low | — | Chỉ log; không hiển thị cho student |
| 30-59 | medium | WebSocket warning đến student; `flagged_for_review=true` | Email proctor |
| 60-79 | high | Publish `cheat.alert.generated` → Exam Service suspend attempt | WS đến proctor, join video nếu có |
| 80+ | critical | Suspend + `auto_action=terminate` → Exam Service cancel attempt | Page on-call security |

### 4.3 Ngăn ngừa false positive

- **Cooldown** giữa các event cùng loại: tab_switch trong 2s không cộng dồn
- **Context awareness**: nếu là exam `proctoring_level=0` (casual), không suspend auto dù score cao
- **Grace period** 30s đầu attempt: không trigger alert (client-side code đang khởi động)
- **Whitelist events** khi cần: giáo viên có thể whitelist `copy_event` cho 1 bài thi cụ thể

---

## V. DATA MODEL

> **Mapping với schema thực tế:**
> - `cheat_events`, `proctoring_sessions` đã có trong `database/postgresql/schema.sql`
> - `cheat_review_queue`, `cheat_appeals` đã có trong `database/postgresql/schema.sql` (mục 9)
> - `cheat_analytics` + `mv_cheat_weekly` đã có trong `database/clickhouse/schema.sql`
> - Redis keys `risk:*`, `baseline:typing:*`, `attempt:ips:*`,... đã có trong `database/redis/schema.md` (Nhóm 7)

### 5.1 PostgreSQL (đã có trong `database.md`)

- `cheat_events` — từng sự kiện (30-columns partition theo tháng)
- `proctoring_sessions` — metadata video

### 5.2 Bảng bổ sung

```sql
CREATE TABLE cheat_review_queue (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attempt_id          UUID NOT NULL REFERENCES exam_attempts(id),
    triggered_by_event  UUID REFERENCES cheat_events(id),
    risk_score_at_trig  SMALLINT NOT NULL,
    severity            VARCHAR(10) NOT NULL,
    assigned_to         UUID REFERENCES users(id),     -- proctor được gán
    status              VARCHAR(20) DEFAULT 'pending',  -- pending | in_review | resolved | escalated
    decision            VARCHAR(20),                    -- confirmed | dismissed | escalated
    decision_reason     TEXT,
    reviewed_at         TIMESTAMPTZ,
    created_at          TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_review_pending ON cheat_review_queue(status, severity, created_at)
    WHERE status IN ('pending','in_review');

CREATE TABLE cheat_appeals (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attempt_id          UUID NOT NULL REFERENCES exam_attempts(id),
    user_id             UUID NOT NULL,
    reason              TEXT NOT NULL,
    evidence_s3_keys    TEXT[],
    status              VARCHAR(20) DEFAULT 'pending',
    reviewed_by         UUID REFERENCES users(id),
    decision            VARCHAR(20),      -- upheld | overturned
    decision_reason     TEXT,
    created_at          TIMESTAMPTZ DEFAULT NOW(),
    resolved_at         TIMESTAMPTZ
);
```

### 5.3 Redis state

| Key | Kiểu | TTL | Nội dung |
| --- | ---- | --- | -------- |
| `risk:{attempt_id}` | Hash | duration + 2h | `total`, `events`, `last_update` |
| `baseline:typing:{user_id}` | Hash | 30 ngày | `mean_kpi`, `stddev`, `samples` |
| `attempt:ips:{id}` | Set | duration + 1h | Set of IPs đã thấy |
| `attempt:geo:{id}` | Hash | duration + 1h | `country`, `city`, `lat`, `lon` |
| `proctor:queue:{proctor_id}` | List | 1h | Assigned attempt IDs |
| `cooldown:{attempt_id}:{event_type}` | String NX | 2-5s | Anti-spam event cùng loại |

### 5.4 ClickHouse (đã có `cheat_analytics`)

Lưu toàn bộ cheat events để phân tích offline: xu hướng cheat theo thời gian, theo org, theo subject...

---

## VI. EVENT INGESTION

### 6.1 Path chính: Kafka

Client → Exam Service WebSocket → publish Kafka `cheat.event.raw`.

**Schema (Avro):**
```json
{
  "type": "record", "name": "CheatEventRaw",
  "fields": [
    {"name": "event_id",       "type": "string"},
    {"name": "attempt_id",     "type": "string"},
    {"name": "user_id",        "type": "string"},
    {"name": "event_type",     "type": "string"},
    {"name": "event_layer",    "type": "int"},
    {"name": "event_data",     "type": "string"},  // JSON
    {"name": "client_ts",      "type": "long"},
    {"name": "server_ts",      "type": "long"},
    {"name": "question_index", "type": ["null","int"], "default": null}
  ]
}
```

### 6.2 Ingestion pipeline

```
cheat.event.raw (Kafka)
        │
        ▼
CheatEventConsumer (Spring Kafka, concurrency=10)
        │
        ▼
Deduplication by event_id (Redis set 1h TTL)
        │
        ▼
Enrichment:
   - GeoIP (IP → country, city)
   - User context (role, org_id from cached)
   - Attempt context (exam_id, proctoring_level)
        │
        ▼
Route to correct Pipeline (L1/L2/L3/L4 handler)
        │
        ▼
Pipeline output: DetectedEvent {severity, weight, confidence}
        │
        ▼
RiskScoreCalculator.add(attempt_id, detected_event)
        │
        ▼
ThresholdEvaluator.evaluate(new_score)
        │
        ├─► (medium) → WS warning to student (via Redis pub/sub ws:exam:{attempt})
        ├─► (high) → publish cheat.alert.generated
        ├─► (critical) → publish cheat.alert.critical + page on-call
        └─► (always) → publish cheat.event.detected (analytics + CH)
        │
        ▼
Insert cheat_events PG (async via buffered writer)
```

### 6.3 Throughput target

- 10.000 events/s/pod
- Batch insert `cheat_events` PG 1000 events/batch, mỗi 500ms
- Consumer concurrency 10, partition count matching

---

## VII. ALERT DISPATCH

### 7.1 WebSocket warning to student

```json
{
  "type": "cheat.warning",
  "severity": "medium",
  "message": "Hệ thống phát hiện bạn đã chuyển tab 3 lần. Vui lòng tập trung vào bài thi.",
  "event_types": ["tab_switch"],
  "risk_level": "medium"
}
```

Gửi qua Redis pub/sub `ws:exam:{attempt_id}` → Exam Service pod nào subscribed → forward đến client WS.

### 7.2 Alert to Exam Service

```json
{
  "attempt_id": "...",
  "user_id": "...",
  "exam_id": "...",
  "risk_score": 65,
  "severity": "high",
  "triggered_events": [
    { "event_type": "devtools_open", "timestamp": "...", "weight": 15 },
    { "event_type": "ip_change",     "timestamp": "...", "weight": 25 }
  ],
  "recommended_action": "suspend",
  "proctor_should_review": true
}
```

Publish vào `cheat.alert.generated` → Exam Service consume → set `attempt.status = 'suspended'`.

### 7.3 Alert to proctor

```json
{
  "type": "proctor.new_alert",
  "queue_item_id": "UUID",
  "attempt_id": "...",
  "exam_title": "...",
  "student_name": "...",
  "risk_score": 65,
  "severity": "high",
  "evidence": [...],
  "video_preview_url": "https://.../proctoring/{attempt}/clip.mp4",
  "sla_deadline": "2026-04-18T10:07:00Z"  // 2 phút
}
```

Gửi qua WebSocket đến proctor dashboard (Proctor Service UI). Hiển thị trên Kanban board: pending / in_review / resolved.

---

## VIII. REVIEW WORKFLOW

### 8.1 Quy trình

```
high/critical alert
        │
        ▼
Add to cheat_review_queue status=pending
        │
        ▼
Auto-assign proctor (round-robin trong danh sách online cùng org)
        │
        ▼
Proctor được push thông báo WS
        │
        ▼
Proctor click "Pick up" → status=in_review, assigned_to=proctor_id
        │
        ▼
Proctor xem:
   - Risk timeline (events list với time)
   - Video clip 30s trước và sau sự kiện critical
   - Answer pattern so với class average
   - IP/geo history
        │
        ▼
Proctor quyết định:
  ├─ "Confirmed cheating" → Exam Service terminate attempt, đánh điểm 0
  ├─ "Dismissed"           → Exam Service resume attempt, reset flag
  └─ "Escalate to admin"   → status=escalated, admin + giáo viên chính review
        │
        ▼
Update cheat_review_queue + publish decision event
        │
        ▼
Notification đến student (nếu resume) hoặc đến giáo viên (nếu confirmed)
```

### 8.2 SLA

| Severity | Proctor must act within | Escalation nếu miss |
| -------- | ---------------------- | ------------------- |
| critical | 1 phút | Auto-terminate attempt + page admin |
| high | 2 phút | Auto-terminate và notify giáo viên |
| medium | 10 phút (trong thi) | Không action auto, batch review sau |

### 8.3 Appeal process

Student có thể appeal sau khi bị confirmed cheating:

```
POST /cheat/appeals
Body: {attempt_id, reason, evidence_urls[]}
        │
        ▼
Create cheat_appeals record status=pending
        │
        ▼
Assign giáo viên chủ bài thi (owner) review
        │
        ▼
Giáo viên xem:
   - Nguyên bản evidence từ proctor decision
   - Student's argument + evidence
        │
        ▼
Giáo viên quyết định:
  ├─ "Upheld"      (giữ nguyên)  → attempt vẫn 0 điểm
  └─ "Overturned" (lật ngược)   → restore attempt, cho retake hoặc tự tính lại điểm
        │
        ▼
Notification student + update records
```

---

## IX. API ENDPOINTS

> **Ghi chú về cột "Role":** Default role được seed với permission cần thiết. Enforcement thật dùng permission code (`cheat.review`, `cheat.decide`, `attempt.suspend`, `cheat.appeal.submit`...). Xem `auth-service-design.md` mục 3.4.


### 9.1 Ingest (fallback khi WS không available)

| Method | Path | Body |
| ------ | ---- | ---- |
| POST | `/cheat/events` | Array of `CheatEventRaw` |

Chủ yếu cho mobile SDK offline buffer. Ingest qua Kafka vẫn là path chính.

### 9.2 Proctor / review

| Method | Path | Role |
| ------ | ---- | ---- |
| GET | `/cheat/review/queue?status=&severity=` | proctor, admin |
| POST | `/cheat/review/{queue_id}/pickup` | proctor |
| GET | `/cheat/review/{queue_id}` | proctor (assigned), admin |
| POST | `/cheat/review/{queue_id}/decide` body `{decision, reason}` | proctor (assigned), admin |
| POST | `/cheat/review/{queue_id}/escalate` | proctor |
| GET | `/cheat/attempts/{attempt_id}/timeline` | proctor, admin, instructor (owner) |
| GET | `/cheat/attempts/{attempt_id}/evidence` | same |

### 9.3 Student

| Method | Path | Role |
| ------ | ---- | ---- |
| GET | `/cheat/appeals/my` | student |
| POST | `/cheat/appeals` | student |
| GET | `/cheat/appeals/{id}` | student (owner), instructor |

### 9.4 Admin / config

| Method | Path | Role |
| ------ | ---- | ---- |
| GET | `/admin/cheat/weights` | admin |
| PATCH | `/admin/cheat/weights` | platform_admin (A/B test weight changes) |
| GET | `/admin/cheat/stats?org_id=&from=&to=` | admin |
| GET | `/admin/cheat/false-positive-rate?window=30d` | admin |
| POST | `/admin/cheat/whitelist` body `{org_id, exam_id?, event_type}` | admin |

### 9.5 gRPC (internal)

```proto
service CheatDetectionService {
    rpc GetRiskScore(GetRiskScoreRequest) returns (RiskScoreResponse);
    rpc RecordManualEvent(ManualEventRequest) returns (Empty);  // proctor note: "saw student look at phone"
    rpc SuspendByProctor(SuspendRequest) returns (Empty);
}
```

---

## X. KAFKA TOPICS

### 10.1 Consumed

| Topic | Nguồn | Handler |
| ----- | ----- | ------- |
| `cheat.event.raw` | Exam Service (forward từ client) | Main ingestion pipeline |
| `proctoring.frame.captured` | Client → Media Service | L5 Vision pipeline |
| `exam.attempt.submitted` | Exam Service | Trigger L6 statistical analysis (delayed) |
| `exam.completed` | Exam Service | Trigger L6 Flink batch |
| `exam.answer.submitted` | Exam Service | Feed L4 answer pattern |

### 10.2 Produced

| Topic | Payload | Consumer |
| ----- | ------- | -------- |
| `cheat.event.detected` | Enriched event | ClickHouse, Analytics |
| `cheat.alert.generated` | Medium+ alerts | Exam Service |
| `cheat.alert.critical` | Critical alerts | PagerDuty, Notification |
| `cheat.review.decided` | Proctor decision | Exam Service, Audit |
| `cheat.appeal.resolved` | Appeal outcome | Exam Service, Notification |
| `cheat.statistical.result` | L6 post-exam findings | Notification (giáo viên), Analytics |

---

## XI. ML MODEL MANAGEMENT

### 11.1 Models used

| Model | Layer | Input | Output | Format |
| ----- | ----- | ----- | ------ | ------ |
| Typing Anomaly Detector | L4 | Keystroke timings vector | z-score | XGBoost (ONNX) |
| Answer Speed Classifier | L4 | Time + correctness | Suspicious prob | Logistic Regression (ONNX) |
| Face Detection | L5 | Image 640x480 | Bounding boxes | RetinaFace TensorRT |
| Phone Detection | L5 | Image | {phone: bool, confidence} | YOLOv8 TensorRT |
| Gaze Estimation | L5 | Face crop | {yaw, pitch} angle | MediaPipe FaceMesh |
| Answer Similarity | L6 | Answer vector of 2 attempts | Similarity score | Cosine similarity (numpy) |

### 11.2 Model serving

- CPU models (L4): ONNX Runtime in-process
- GPU models (L5): Triton Inference Server dedicated pods

### 11.3 Retrain cadence

- L4 typing: hàng tháng từ baseline mới của users
- L5 vision: khi có dataset mới labeled; quý 1 lần
- L6 answer similarity: threshold tuning hàng tuần theo false positive rate

---

## XII. OBSERVABILITY

### 12.1 Metrics

| Metric | Type | Label |
| ------ | ---- | ----- |
| `cheat_events_ingested_total` | counter | `event_type`, `layer` |
| `cheat_events_processing_duration_seconds` | histogram | `layer` |
| `cheat_risk_score_distribution` | histogram | — |
| `cheat_alerts_published_total` | counter | `severity` |
| `cheat_review_queue_size` | gauge | `severity`, `status` |
| `cheat_review_decision_total` | counter | `decision` |
| `cheat_appeal_submitted_total` | counter | — |
| `cheat_false_positive_rate` | gauge | (rolling 30d, từ appeal overturned) |
| `cheat_vision_inference_duration_seconds` | histogram | `model` |
| `cheat_vision_gpu_utilization` | gauge | `gpu_id` |
| `cheat_flink_job_lag_seconds` | gauge | — |
| `cheat_kafka_consumer_lag` | gauge | `topic` |

### 12.2 SLO

| SLI | Target |
| --- | ------ |
| Event → risk updated p99 | < 500ms |
| Alert → Exam Service p99 | < 200ms |
| False positive rate (confirmed overturned) | < 0.5% over 30d |
| False negative (L6 catches post-exam) | < 5% |
| Proctor SLA compliance | > 95% |

### 12.3 Alerts

| Alert | Điều kiện | Severity |
| ----- | --------- | -------- |
| `CheatConsumerLagHigh` | lag > 10k messages trong 2 phút | WARNING |
| `CheatProcessingLatencyHigh` | p99 > 1s trong 5 phút | WARNING |
| `FalsePositiveRateSpike` | > 1% rolling 7d | CRITICAL (regression) |
| `VisionGpuUnavailable` | GPU node unhealthy | CRITICAL |
| `ReviewQueueBacklog` | pending > 20 quá 10 phút | WARNING |
| `CriticalAlertUnactioned` | critical > 2 phút chưa pickup | CRITICAL (page admin) |

### 12.4 Dashboards

1. **Operational** — events/sec, consumer lag, processing latency
2. **Risk distribution** — histogram risk_score qua thời gian
3. **Layer efficacy** — contribution của từng layer vào decisions
4. **Review workflow** — queue size, SLA compliance, decision distribution
5. **False positive watch** — appeals overturned / confirmed rate

---

## XIII. PERFORMANCE & SCALING

### 13.1 Capacity profile

| Load type | Peak | Pod needed |
| --------- | ---- | ---------- |
| L1-L3 event ingestion | 10k events/s | 2-3 pods (5k/pod comfortably) |
| L4 behavior analytics | 1k events/s with buffer | 1-2 pods |
| L5 vision inference | 1000 students × 1fps = 1k inference/s | 3-4 GPU pods (A10G) |
| L6 Flink statistical | 1 job per exam completed | 2 Flink TaskManager |

### 13.2 Scale strategy

- HPA theo `cheat_kafka_consumer_lag` + CPU
- Vision GPU: min 1 pod luôn warm, scale up on queue depth
- Flink: cluster với 2-4 TaskManager, elastic on backlog

### 13.3 Cost optimization

- L5 chỉ bật cho exam `proctoring_level=2` (enterprise) — không auto-enable
- Vision inference batching 8 frames → giảm GPU idle
- Cooldown event cùng loại → giảm redundant compute

---

## XIV. SECURITY

### 14.1 Abuse prevention

- Rate limit event ingestion per `attempt_id`: max 100 events/second (reasonable max for legitimate client)
- Signature: mỗi event từ client có HMAC với secret do Exam Service cấp lúc start attempt → server verify; chống fake event
- Event `client_ts` được compare với `server_ts`: nếu skew > 5 phút → discard + flag attempt

### 14.2 Data privacy

- Video frames: lưu S3 encrypted, TTL 12 tháng, chỉ proctor/admin access
- Typing dynamics: PII (có thể identify user từ rhythm) → hash + anonymize khi export
- Event logs: student có quyền `GET /cheat/attempts/{id}/events` (chỉ cho attempt của mình)

### 14.3 Access control

| Resource | student | proctor | instructor (own exam) | admin | platform_admin |
| -------- | ------- | ------- | --------------------- | ----- | -------------- |
| Xem risk của mình | ✖ | — | — | — | — |
| Xem events của attempt mình | ✔ (sau exam) | — | — | — | — |
| Xem review queue org | — | ✔ | ✔ | ✔ | ✔ |
| Review + decide | — | ✔ | — | ✔ | ✔ |
| Appeal submit | ✔ (own) | — | — | — | — |
| Appeal review | — | — | ✔ (own exam) | ✔ | ✔ |
| Config weights | — | — | — | — | ✔ |
| View video | — | ✔ | ✔ (own exam) | ✔ | ✔ |

---

## XV. ERROR HANDLING

| Code | HTTP | Ý nghĩa |
| ---- | ---- | ------- |
| `CHEAT_EVENT_INVALID` | 422 | Schema validation fail |
| `CHEAT_EVENT_RATE_LIMIT` | 429 | Quá 100 events/s/attempt |
| `CHEAT_EVENT_SIGNATURE_INVALID` | 401 | HMAC không match |
| `CHEAT_REVIEW_NOT_FOUND` | 404 | |
| `CHEAT_REVIEW_ALREADY_ASSIGNED` | 409 | Proctor khác đã pickup |
| `CHEAT_REVIEW_NOT_IN_STATE` | 409 | Cố decide 1 review đã resolved |
| `CHEAT_APPEAL_WINDOW_CLOSED` | 410 | Appeal > 30 ngày sau exam |

---

## XVI. TESTING

### 16.1 Unit

- Risk score calculation với các event sequences cố định
- Threshold evaluator
- Frequency multiplier + time decay function

### 16.2 Integration

- Testcontainers: Kafka + Redis + PG
- Flow: publish cheat.event.raw → verify risk updated + alert nếu threshold

### 16.3 Simulation & ML eval

- **Synthetic event generators**: mô phỏng student cheat vs honest → verify detection rate
- **Shadow mode**: chạy 2 weight config song song → so sánh decision rate
- **Red-team**: security team cố tình bypass detection → đo false negative

### 16.4 False positive regression

- Dataset của 1000 "known honest" attempts (đã verified) → phải có < 0.5% flagged
- Dataset của 100 "known cheat" attempts → phải catch ≥ 90%

---

## XVII. DEPLOYMENT

### 17.1 K8s

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: cheat-detection, namespace: smartquiz }
spec:
  replicas: 3
  template:
    spec:
      containers:
        - name: cheat
          image: registry.smartquiz.vn/cheat-detection:1.0.0
          resources:
            requests: { cpu: 1,   memory: 1Gi }
            limits:   { cpu: 4,   memory: 4Gi }
          env:
            - { name: KAFKA_BROKERS, value: kafka-0:9092,... }
            - { name: REDIS_URL,     value: redis://redis:6379 }
            - { name: TRITON_URL,    value: grpc://triton:8001 }
            - { name: MAXMIND_DB_PATH, value: /var/geoip/GeoLite2-City.mmdb }
          volumeMounts:
            - { mountPath: /var/geoip, name: geoip-data, readOnly: true }
      volumes:
        - name: geoip-data
          persistentVolumeClaim: { claimName: geoip-pvc }

---
apiVersion: apps/v1
kind: Deployment
metadata: { name: cheat-vision-gpu }
spec:
  replicas: 1  # scale on demand
  template:
    spec:
      nodeSelector: { "nvidia.com/gpu.present": "true" }
      tolerations: [{ key: "nvidia.com/gpu", operator: "Exists" }]
      containers:
        - name: vision-worker
          image: registry.smartquiz.vn/cheat-vision:1.0.0
          resources:
            limits: { "nvidia.com/gpu": 1, memory: 8Gi }

---
# Flink job cluster for L6
apiVersion: flink.apache.org/v1beta1
kind: FlinkDeployment
metadata: { name: cheat-statistical-flink }
spec:
  image: flink:1.18
  flinkConfiguration:
    taskmanager.numberOfTaskSlots: "4"
  taskManager: { replicas: 2 }
```

### 17.2 HPA

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
spec:
  minReplicas: 3
  maxReplicas: 20
  metrics:
    - type: Pods
      pods:
        metric: { name: cheat_kafka_consumer_lag }
        target: { type: AverageValue, averageValue: 1000 }
```

### 17.3 GeoIP DB update

CronJob hàng tuần tải MaxMind GeoLite2 mới → update PVC `geoip-pvc` → rolling restart pods.

---

## XVIII. DISASTER RECOVERY

| Scenario | Impact | Mitigation |
| -------- | ------ | ---------- |
| CDS hoàn toàn down | Exam vẫn chạy, không phát hiện cheat | Events buffer trong Kafka 30 ngày → replay khi CDS up |
| Triton GPU down (L5) | L5 không hoạt động, L1-L4 vẫn ok | Fallback: không proctor video, chỉ cảnh báo giáo viên |
| Flink cluster down (L6) | Post-exam analysis trễ | Retry batch khi cluster up; không critical trong kỳ thi |
| False positive bão (bug mới) | Nhiều student bị suspend sai | Emergency: PATCH weights về 0 cho event_type nghi vấn; rollback deployment |
| Redis down | Mất hot state risk_score | Rebuild từ Kafka replay + cheat_events PG |

---

## XIX. ETHICAL & UX CONSIDERATIONS

### 19.1 Transparency

- Student phải được thông báo **trước khi thi** rằng hệ thống giám sát gì
- Trong thi, nếu có warning medium → hiển thị rõ event đã trigger để student hiểu
- Sau thi, nếu bị flag/suspend → cung cấp evidence khi appeal

### 19.2 Bias & fairness

- Typing dynamics model: train trên dataset đa dạng (không chỉ 1 dân tộc / ngôn ngữ)
- Vision: face detection phải work với nhiều skin tone (dùng model có bias audit)
- Không discriminate theo device: mobile hơn laptop thường có nhiều `window_blur` (do multitask OS) → weight thấp hơn cho mobile

### 19.3 Proportionality

- Low stakes practice exam → `proctoring_level=0`, chỉ log, không cảnh báo
- Medium stakes → cảnh báo student, không auto-suspend
- High stakes (certification) → full proctoring, auto-suspend + manual review

---

## XX. ROADMAP

### 20.1 MVP (Q2/2026)

- [ ] L1-L2 client + browser detection + weight table
- [ ] Event ingestion + risk calculator Redis
- [ ] Threshold evaluator + alert → Exam Service
- [ ] Basic review queue for proctor
- [ ] PostgreSQL cheat_events storage

### 20.2 Phase 2 (Q3/2026)

- [ ] L3 Network anomaly (GeoIP, VPN detection)
- [ ] L4 behavior analytics (typing, timing)
- [ ] Appeal workflow
- [ ] Proctor SLA tracking
- [ ] ClickHouse analytics integration

### 20.3 Phase 3 (Q4/2026)

- [ ] L5 Vision proctoring (GPU, Triton)
- [ ] L6 Flink statistical cross-attempt
- [ ] A/B test weights framework
- [ ] False positive reduction ML model (2nd layer filter)
- [ ] Mobile SDK for on-device monitoring

### 20.4 Open questions

1. **Có nên share IP ban list cross-org?** → Privacy concern. Phase 3: opt-in sharing với hashed IP.
2. **Browser fingerprint scope?** → Balance fingerprint strength vs privacy; dùng GDPR-compliant fingerprint (không persist FingerprintJS Pro-level identification)
3. **Student tự xem risk_score?** → Không (chống ngược-engineer threshold); chỉ thấy "warning level" (low/medium/high)
4. **Giáo viên override weight cho exam cụ thể?** → Có, nhưng limited (+/- 30% base weight); không được set event xuống 0
5. **Open-source cheating patterns từ academic research?** → Integrate các paper detection heuristics; cite trong docs
6. **Deepfake trong video proctoring?** → Phase 3: liveness check (blink detection, random head turn challenge) trước khi thi

---

_Tài liệu thuộc Hệ Thống Thi Trực Tuyến Thông Minh — Cheating Detection Service Design v1.0 — Tháng 4/2026._
