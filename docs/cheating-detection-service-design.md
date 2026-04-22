# CHEATING DETECTION SERVICE - THIẾT KẾ CHI TIẾT

**Hệ Thống Thi Trực Tuyến Thông Minh** | Version 2.1 | Tháng 4/2026

Tài liệu này mở rộng mục "Cheating Detection Service" trong `design.md`, ở mức đủ để triển khai code production.

**Tài liệu nguồn ràng buộc (phải đọc trước khi thay đổi):**
- `CLAUDE.md` §2 (stack lock), §3 (NFR lock), §6 (interaction rules), §9 (prereqs scaffold)
- `docs/adr/ADR-001-sla-rpo-outbox.md` (SLA 99.9% platform, RPO ≤5s cho event critical, outbox pattern)
- `docs/adr/ADR-002-analytics-vs-cheating-split.md` (CDS & Analytics là 2 service riêng, shared ClickHouse cluster khác DB, cross-service qua Kafka fan-out)
- `database/postgresql/schema.sql` §2 (`cheat_event_type` ENUM), §8 (`cheat_events`, `proctoring_sessions`), §12 (`cheat_review_queue`, `cheat_appeals`), §13 (`outbox`, `processed_events`)
- `docs/auth-service-design.md` §3 (RBAC permission-based — CDS enforce bằng permission code, không hardcode role)
- `docs/exam-service-design.md` (Attempt aggregate, state transition, `state_version` fencing cho suspend race)
- `shared-contracts/avro/cheat/*.avsc` (event schema — BACKWARD compat mode)

**Changelog v2.1 (2026-04-22) — fix batch sau code-review độc lập:**
- §VIII.2 **fix Lua script**: `HGETALL` trả flat array trong Lua/Redis, không phải named hash → `current.state_version` luôn `nil` → fencing CAS bị bypass. Đổi sang `HMGET` với explicit keys + parse array theo cặp. Thêm §VIII.2.1 caller retry guidance cho `CAS_FAIL` (≤ 3 retry + backoff 10/50/200ms)
- §3.4 YAML weights + §VII pipeline: hoà giải với DDL ENUM `cheat_event_type` (schema.sql:50-58). **Extend ENUM** qua schema delta §18.1 item 5 với 9 event type mới (`extension_detected`, `emulator_detected`, `proxy_detected`, `high_latency_jump`, `wrong_answer_match`, `submission_time_cluster`, `answer_change_pattern`, `manual_proctor_note`, `suspicious_clock_skew`); đổi `keyboard_blocked_shortcut` → `keyboard_shortcut` (ENUM đã có). Thêm YAML weight cho 6 event DDL-only (`idle_too_long`, `answer_pattern`, `audio_detected`, `environment_issue`, `sync_submission`, `score_anomaly`) — không còn gap
- §18.1 item 1 (partition `cheat_events`): thêm cảnh báo PK cascade — partition PG yêu cầu PK chứa `server_timestamp` → đổi PK → break FK `cheat_review_queue.triggered_by_event` → phải recreate FK với composite reference + downtime plan
- §18.1: đánh dấu `[x] exam_attempts.state_version BIGINT` đã có ở `schema.sql:343`; không phải prereq
- §11.5 (gRPC): rename `SuspendByProctor` → `RequestSuspendByProctor` — CDS là HTTP→gRPC adapter, không own quyền UPDATE `exam_attempts` (align §3.5 RBAC)
- §13.1 (HMAC): thêm paragraph semantic rotation — attempt_secret derive 1 lần lúc `attempt.start`, Exam cache suốt attempt; CDS verify dual-key trong grace window 30 ngày
- §12.3: thêm `publishAlertCritical` branch fire-and-forget cho severity=critical (PagerDuty) — align §8.3
- §10.2: thêm pseudo-code `@Transactional` cron auto-escalate — UPDATE RETURNING + publish outbox **trong cùng TX** (ADR-001 invariant)
- §VII.6 Flink: note tradeoff SessionWindow vs TumblingEventTimeWindow cho exam mở dài ngày
- §VII.5 Vision pool: giảm `preproc-pool-size: 4 → 3` chừa 1 vCPU headroom cho VT I/O + Triton gRPC client + Spring main loop trên pod `cpu: 4`
- §3.1 rename "Redis source-of-truth" → "Redis authoritative hot-state" — tránh xung đột term với §5.2 (PG là audit truth)
- §16.3 rename test `..._exactly_once_...` → `..._deduplicates_alert_through_outbox_...` — tránh gợi exactly-once producer (ADR-001 là at-least-once + idempotent)

**Changelog v2.0 (2026-04-22) — align theo chuẩn auth-service-design v1.5:**
- Rewrite toàn bộ theo cấu trúc 18 section thống nhất (changelog, NFR lock, build gate, API conventions, error handling RFC 7807, observability OTel + MDC, roadmap prereqs)
- §III (Stack + port) lock theo `CLAUDE.md §2` — Java 21 + Spring Boot 3.3+ + Gradle multi-project; Virtual Threads cho I/O path, **bounded platform pool** cho CPU-bound vision pre-processing (giống pattern Argon2 auth §16.3)
- §IV (Build quality gate): Spotless + Checkstyle + JaCoCo (domain ≥80%, application ≥70%, global ≥75%); Error Prone; OWASP dependency-check nightly
- §V (Domain): tách aggregate `AttemptRiskContext` (hot state Redis) khỏi `CheatEvent` (append-only PG); value object `LayerResult`, `RiskScore`
- §VI (Data model): chỉ mô tả **invariant + business rule**, xoá DDL duplicate — schema master là `database/postgresql/schema.sql` (CLAUDE.md §6). Flyway delta path: `services/cheating-detection/app/src/main/resources/db/migration/V{epoch}__cheat_*.sql`
- §VII (Detection pipelines): 6 layer L1-L6, mỗi layer có YAML-driven weight config (§VIII), thread model rõ (VT cho I/O, bounded pool cho vision), cooldown + decay chuẩn
- §VIII (Risk scoring): Lua atomic trên Redis với double-write vào `cheat_events` async buffer; fencing token `state_version` khi call Exam Service suspend (ADR-001 §3)
- §IX (Alert dispatch): `cheat.alert.generated.v1` **qua outbox** (ADR-001 applied) — không publish trực tiếp từ ThresholdEvaluator
- §X (Review workflow): state machine `pending → in_review → resolved|escalated`; SLA enforcement + auto-escalation cron; appeal window 30 ngày sau attempt completed
- §XI (API conventions): full `/api/v1/` versioning, RFC 7807 error, Idempotency-Key, rate limit headers, cursor pagination, standard headers — cùng convention §12.0 của Auth
- §XII (Events): Avro qua Apicurio BACKWARD compat; bảng producer/consumer tách critical (outbox) vs fire-and-forget; topic names có `.v1` suffix (TOPICS.md là nguồn truth)
- §XIII (Security): HMAC event signature từ Exam Service cấp lúc start attempt; privacy (video S3 encrypted, typing dynamics anonymized); RBAC permission matrix
- §XIV (Observability): Micrometer → Prometheus, OTel OTLP, Loki JSON log + MDC masking (video S3 key, IP, keystroke raw bị mask)
- §XV (Error handling): RFC 7807 Problem Details + error code matrix per endpoint
- §XVI (Testing): Testcontainers + WireMock (Triton stub) + k6 load + ML eval (false positive regression gate < 0.5%)
- §XVII (Deployment): K8s + HPA theo `cheat_kafka_consumer_lag`, PDB, NetworkPolicy, GPU node selector
- §XVIII (Roadmap): gate prereqs theo CLAUDE.md §9 + schema delta block single source (tránh drift)

**Changelog v1.0 (2026-04-18):**
- Bản draft đầu tiên: 6 layer detection, risk score algorithm, review/appeal workflow, stack skeleton.

---

## I. TỔNG QUAN

### 1.1 Vai trò & phạm vi

Cheating Detection Service (viết tắt **CDS**) là service **an ninh hành vi** — phân tích hành vi học sinh trên 6 tầng, tính điểm rủi ro theo thời gian thực, và đẩy cảnh báo đến Exam Service để **suspend attempt** nếu cần. CDS **không quyết định** trạng thái attempt — Exam Service là owner, CDS chỉ recommend.

**Nguyên tắc thiết kế (NFR-level, không đổi nếu không có ADR mới):**

| Nguyên tắc | Lý do |
| ---------- | ----- |
| **Không phải judge cuối cùng** — suspend ≠ cheat | Quyết định kỷ luật cần người review; false positive gây tổn hại học sinh |
| **False positive rate < 0.5%** (rolling 30d qua appeal overturned) | SLA uy tín; compliance audit |
| **Real-time path < 500ms** (event → risk_score updated + alert published) | Kịp ngăn chặn trong kỳ thi |
| **Fail-open** — CDS down không block exam | ADR-002 §Consequences: tách hot path, exam vẫn chạy, mất tính năng phát hiện |
| **Audit trail đầy đủ** | `cheat_events` retention 24 tháng + video 12 tháng → appeal có evidence |
| **Separation of concerns** | CDS phát hiện; Exam Service quyết định trạng thái; Proctoring (phase 2) xử lý video; giáo viên quyết định kỷ luật |

**Ranh giới với service khác:**

| Trách nhiệm CDS | Không thuộc phạm vi CDS |
| --------------- | ----------------------- |
| Thu thập sự kiện từ 6 tầng (L1-L6) | Ngăn chặn sự kiện client-side (thuộc `web/exam-client-lib`) |
| Tính `risk_score` real-time per attempt | Chuyển `attempt.status = suspended` (Exam Service) — CDS chỉ publish alert |
| Phát hiện tương đồng đáp án cross-attempt (L6) | Chấm điểm / hình thức xử lý kỷ luật |
| Phân tích hành vi (typing dynamics, timing) | Baseline typing từ 5 attempt đầu — thu thập được nhưng **Analytics Service** own lưu trữ lịch sử (ADR-002) |
| Vision AI inference: face, phone, gaze (L5) | Train model vision (ML platform team, Phase 3) |
| Phát cheat alert qua Kafka + WebSocket | Gửi email/SMS (Notification Service consume event) |
| Quản lý review queue cho proctor | Proctor dashboard UI (frontend admin) |
| Appeal workflow | Chính sách kỷ luật (Academic policy — ngoài phạm vi system) |

### 1.2 Stack công nghệ

> Bản này đã lock theo `CLAUDE.md §2`. Đổi công nghệ phải viết ADR mới — đừng tự ý
> thay trong design doc.

| Thành phần | Chọn | Lý do |
| ---------- | ---- | ----- |
| Runtime | **Java 21 LTS + Spring Boot 3.3+** | Thống nhất với các service Java khác (CLAUDE.md §2). `spring.threads.virtual.enabled=true` cho I/O path (Kafka consumer poll, Redis, JDBC, gRPC Triton). **KHÔNG áp dụng cho vision pre-processing pool** (CPU-bound JPEG decode + tensor reshape ~20-50ms) — dùng bounded `ThreadPoolTaskExecutor` (§VII.5) pattern tương tự Argon2 ở Auth Service. |
| Framework | Spring Web MVC + Spring Kafka + Spring gRPC | Layer hoá service/use-case chuẩn; Kafka listener concurrency theo partition |
| Stream processing (real-time L1-L5) | **Kafka Streams** | Per-attempt windowed aggregation, dùng state store RocksDB embedded |
| Stream processing (L6 cross-attempt batch) | **Apache Flink** | Session window per exam, Flink Kubernetes operator; job tách module khỏi app chính |
| ML serving | **NVIDIA Triton (GPU)** + **ONNX Runtime** in-process (CPU fallback) | Batching, multi-model per pod; CPU-only fallback dev/staging |
| Vision models | YOLOv8 (phone/book), RetinaFace (face detect), MediaPipe FaceMesh (gaze) | Nhẹ + chính xác, có TensorRT optimize |
| Behavior ML | XGBoost (typing anomaly) + Logistic Regression (answer speed) — ONNX export | Rule engine đủ cho MVP; ML mở rộng Phase 2 |
| Hot state | **Redis Cluster** (Lettuce client, async) | `risk:{attempt_id}`, `baseline:typing:*`, cooldown, IP set — xem §V.3 |
| DB | **PostgreSQL 16** | `cheat_events` (partition tháng), `proctoring_sessions`, `cheat_review_queue`, `cheat_appeals` — schema master `database/postgresql/schema.sql` §8, §12 |
| Analytics store | **ClickHouse** (shared cluster, DB riêng `cheat_analytics`) | ADR-002: shared cluster isolate DB; long-term aggregation cross-attempt |
| Migration | **Flyway** (`src/main/resources/db/migration`) | Versioned SQL, naming `V{epoch}__cheat_*.sql`; chỉ commit delta riêng service, schema master không đụng (CLAUDE.md §6) |
| Event bus | Spring Kafka + **Transactional Outbox** (ADR-001) cho event critical | `cheat.alert.generated.v1`, `cheat.review.decided.v1`, `cheat.appeal.resolved.v1` qua outbox; `cheat.event.detected.v1` fire-and-forget |
| Schema contracts | **Apicurio Schema Registry + Avro**, BACKWARD compat (CLAUDE.md §2) | Event versioning an toàn giữa producer/consumer |
| GeoIP | **MaxMind GeoLite2** (offline mmdb mount PVC) | Không phụ thuộc network; update CronJob hàng tuần |
| IP reputation | IPQualityScore API + local blocklist fallback | VPN/proxy detection L3 |
| JWT verify (từ Auth) | `spring-security-oauth2-resource-server` + JWKS | Offline verify qua `jwk-set-uri` Auth Service; cache 1h |
| Observability | **Micrometer → Prometheus**, **OpenTelemetry OTLP** (traces+metrics), **Loki** (log push) | Stack chuẩn repo (CLAUDE.md §2) |
| Logging | SLF4J + Logback JSON encoder (logstash-logback-encoder) + **MDC** (`trace_id`, `attempt_id`, `user_id`, `event_layer`) + masking filter (video S3 key, IP raw, keystroke) | Format AI-friendly, tracing Claude Code debug được |
| Secret store | HashiCorp Vault (Kubernetes auth) — MVP: K8s Secret | HMAC key cho event signature, IPQualityScore API key, Triton TLS cert |
| Build | **Gradle** (wrapper pinned) + **Spotless** (google-java-format) + **Checkstyle** + **JaCoCo** + Error Prone | Quality gate CI bắt buộc (CLAUDE.md §2) |
| Test | JUnit 5 + AssertJ + **Testcontainers** (PG 16, Redis 7, Kafka, Apicurio) + WireMock (Triton / IPQualityScore stub) | CLAUDE.md §2 mandate; integration hit container thật |

### 1.3 Port & giao thức

| Giao thức | Port | Mục đích |
| --------- | ---- | -------- |
| HTTP REST | `3005` | Admin UI (review queue, stats), student appeal, fallback ingest |
| gRPC | `4005` | Internal: `GetRiskScore`, `RecordManualEvent`, `RequestSuspendByProctor` cho Exam Service |
| OpenAPI spec | `3005/v3/api-docs` · `3005/swagger-ui.html` | Contract FE + service consumer |
| Actuator | `9005/actuator/*` | Prometheus scraping + K8s probes |

### 1.4 Yêu cầu phi chức năng (NFR)

| Chỉ số | Mục tiêu | Nguồn ràng buộc |
| ------ | -------- | --------------- |
| Event ingestion throughput | 10.000 events/s/pod | §XIII.1 capacity profile |
| Event → risk_score updated + alert p99 | < 500ms | SLA real-time; vi phạm = page on-call |
| Alert publish p99 (outbox → Kafka → Exam Service consume) | < 5s | ADR-001 RPO — `cheat.alert.generated.v1` là event critical drive state change Exam |
| False positive rate (confirmed overturned appeal / total confirmed) rolling 30d | **< 0.5%** | SLA uy tín; bật `FalsePositiveRateSpike` alert |
| False negative rate (L6 post-exam catches missed in realtime) | < 5% | Đo qua red-team dataset (§XVI.4) |
| Availability | **99.9% single-region** (ADR-001 §1) | Nếu down: exam vẫn chạy (fail-open), mất tính năng phát hiện |
| Proctor review SLA (critical alert picked up) | < 2 phút trong thi đang diễn ra | §X.2 |
| Storage retention | `cheat_events` 24 tháng (partition tháng), `proctoring_sessions` video 12 tháng, L6 sao chép cross-attempt **vĩnh viễn** (evidence audit) | §VI.1 |

---

## II. KIẾN TRÚC BÊN TRONG

### 2.1 Sơ đồ lớp (layered architecture)

```
┌──────────────────────────────────────────────────────────────┐
│  Controllers (REST + gRPC)                                   │
│  ─ ReviewController (proctor UI)                             │
│  ─ AppealController (student)                                │
│  ─ CheatAdminController (stats, config, whitelist)           │
│  ─ CheatIngestFallbackController (HTTP event ingest)         │
│  ─ CheatGrpcService (Exam Service internal)                  │
├──────────────────────────────────────────────────────────────┤
│  Application Services (use cases)                            │
│  ─ IngestEventUseCase, EvaluateRiskUseCase,                  │
│    DispatchAlertUseCase, ReviewPickupUseCase,                │
│    ReviewDecideUseCase, AppealSubmitUseCase,                 │
│    AppealResolveUseCase                                      │
├──────────────────────────────────────────────────────────────┤
│  Detection Pipelines (per-layer)                             │
│  ─ L1_ClientBehaviorPipeline (VT)                            │
│  ─ L2_BrowserIntegrityPipeline (VT)                          │
│  ─ L3_NetworkAnomalyPipeline (VT + GeoIP offline)            │
│  ─ L4_BehaviorAnalyticsPipeline (Kafka Streams + XGBoost)    │
│  ─ L5_VisionPipeline (bounded pool pre-proc + Triton gRPC)   │
│  ─ L6_StatisticalCorrelationJob (Flink, separate module)     │
├──────────────────────────────────────────────────────────────┤
│  Domain Services                                             │
│  ─ RiskScoreCalculator (Lua atomic on Redis + decay)         │
│  ─ ThresholdEvaluator (0-29/30-59/60-79/80+)                 │
│  ─ CheatOutboxPublisher (MANDATORY TX — ADR-001)             │
│  ─ ReviewQueueManager (auto-assign round-robin)              │
│  ─ AppealPolicy (30-day window enforcement)                  │
├──────────────────────────────────────────────────────────────┤
│  Repositories                                                │
│  ─ CheatEventRepo, ProctoringRepo,                           │
│    ReviewQueueRepo, AppealRepo (PG)                          │
│  ─ RiskStateStore, TypingBaselineStore,                      │
│    IpSeenStore, CooldownStore (Redis)                        │
│  ─ CheatAnalyticsClient (ClickHouse JDBC)                    │
├──────────────────────────────────────────────────────────────┤
│  Integrations                                                │
│  ─ KafkaRawEventConsumer, KafkaAttemptCompletedConsumer      │
│  ─ OutboxRelayer (leader-elect, ADR-001 §impl)               │
│  ─ TritonGrpcClient (vision inference)                       │
│  ─ GeoIpResolver (MaxMind offline)                           │
│  ─ IpReputationClient (IPQualityScore + circuit breaker)     │
│  ─ WebSocketPublisher (Redis pub/sub → Exam Service WS)      │
│  ─ AuthJwksClient (JWT verify)                               │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 Module Gradle multi-project

Nằm trong root `services/cheating-detection/` (xem CLAUDE.md §4). Gradle wrapper pin (`gradle-wrapper.properties`), version catalog dùng chung ở `/gradle/libs.versions.toml`.

```
services/cheating-detection/
├── settings.gradle.kts        # include: app, api-grpc, flink-job-l6, domain-test-fixtures
├── build.gradle.kts           # root — convention plugins (spotless, checkstyle, jacoco, errorprone)
├── gradle/                    # wrapper (pinned)
├── api-grpc/                  # .proto + generated stubs (publish → mavenLocal để Exam Service consume)
│   ├── src/main/proto/cheat_detection.proto
│   └── build.gradle.kts
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/vn/smartquiz/cheat/
│       │   ├── CheatDetectionApplication.java
│       │   ├── config/        # KafkaConfig, RedisConfig, TritonConfig, OutboxConfig, SecurityConfig
│       │   ├── controller/    # @RestController + @ControllerAdvice (RFC 7807)
│       │   ├── grpc/          # CheatDetectionGrpcService
│       │   ├── application/   # UseCase
│       │   ├── domain/
│       │   │   ├── event/     # CheatEvent, EventType (enum map với cheat_event_type DDL)
│       │   │   ├── attempt/   # AttemptRiskContext (aggregate — hot state)
│       │   │   ├── review/    # ReviewQueueItem, ReviewDecision
│       │   │   ├── appeal/    # Appeal, AppealDecision
│       │   │   └── policy/    # WeightConfig, ThresholdConfig (YAML-driven)
│       │   ├── pipeline/
│       │   │   ├── l1/        # ClientBehaviorPipeline
│       │   │   ├── l2/        # BrowserIntegrityPipeline
│       │   │   ├── l3/        # NetworkAnomalyPipeline + GeoIpResolver
│       │   │   ├── l4/        # BehaviorAnalyticsPipeline (Kafka Streams topology)
│       │   │   ├── l5/        # VisionPipeline (Triton client + bounded pool)
│       │   │   └── common/    # EventContext, LayerResult, CooldownCheck
│       │   ├── scoring/       # RiskScoreCalculator, ThresholdEvaluator
│       │   ├── infrastructure/
│       │   │   ├── persistence/   # JPA entities + repo (cheat_events, proctoring_sessions, review_queue, appeals)
│       │   │   ├── redis/         # RiskStateStore, CooldownStore (Lua script)
│       │   │   ├── clickhouse/    # CheatAnalyticsClient (read-only query, ADR-002)
│       │   │   ├── kafka/         # Relayer + Avro serde (Apicurio)
│       │   │   ├── triton/        # TritonGrpcClient
│       │   │   ├── geoip/         # MaxMindResolver
│       │   │   └── ipqs/          # IpReputationClient + Resilience4j CB
│       │   └── common/        # Exception, ErrorCode, MdcFilter, HmacVerifyFilter
│       ├── main/resources/
│       │   ├── application.yml
│       │   ├── logback-spring.xml      # JSON encoder + mask filter (IP raw, video S3 key, keystroke data)
│       │   ├── db/migration/           # Flyway V{epoch}__cheat_*.sql — chỉ delta riêng
│       │   ├── config/
│       │   │   ├── weights.yaml        # base_weight per event_type (§VII)
│       │   │   ├── thresholds.yaml     # 0-29/30-59/60-79/80+
│       │   │   └── layer-confidence.yaml
│       │   └── static/openapi.yaml     # OpenAPI 3.1 spec
│       └── test/java/...
├── flink-job-l6/              # L6 post-exam statistical job — module riêng
│   ├── build.gradle.kts
│   └── src/main/java/vn/smartquiz/cheat/l6/
│       └── StatisticalCorrelationJob.java
└── README.md
```

**Rule Flyway:** schema master ở `database/postgresql/schema.sql` (nhiều service share).
CDS chỉ commit migration delta riêng vào `app/src/main/resources/db/migration` với prefix
`V{yyyymmddhhmm}__cheat_*.sql`. Không được đổi migration đã release (immutable).

### 2.3 Build quality gate

| Tool | Cấu hình | Gate fail khi |
| ---- | -------- | ------------- |
| Spotless | `googleJavaFormat('1.19.2')` + `removeUnusedImports()` + `trimTrailingWhitespace()` | Format lệch → CI fail, gợi ý `./gradlew spotlessApply` |
| Checkstyle | `config/checkstyle/checkstyle.xml` (Google style + project override) | Bất kỳ error → CI fail |
| JaCoCo | Report HTML + XML → Codecov | `domain/` line coverage < **80%**, `application/` < **70%**, global < **75%** |
| OWASP dependency-check | `./gradlew dependencyCheckAggregate` trong CI nightly | CVSS ≥ 7.0 trên compile deps |
| Error Prone | Google static analysis — bật cho `main` | Any new warning → review block |
| Avro compat check | `./gradlew :shared-contracts:avroCompatCheck` | BACKWARD violation → CI fail (CLAUDE.md §2) |
| False positive regression | Dataset `test/resources/fp-regression/*.json` (1k honest attempt) chạy qua pipeline | > 0.5% flagged → CI fail (§XVI.4) |

---

## III. DOMAIN MODEL

### 3.1 Aggregate: `AttemptRiskContext` (Redis **authoritative hot-state** — hot path)

```java
public class AttemptRiskContext {
    private final UUID attemptId;
    private final UUID userId;
    private final UUID examId;
    private final Instant attemptStartedAt;
    private final int proctoringLevel;        // 0=none | 1=basic | 2=video
    private int totalRiskScore;                // 0-100+, sum decayed weights
    private Severity currentSeverity;          // low|medium|high|critical
    private boolean autoSuspended;             // đã publish cheat.alert.generated với recommend=suspend
    private long stateVersion;                 // fencing token — pair với exam_attempts.state_version
    private final List<CheatEventRef> recentEvents;  // last 100, for context

    public RiskEvaluation applyEvent(CheatEvent e, WeightConfig weights, Clock clock) { ... }
    public boolean isInGracePeriod(Clock clock, Duration grace) { ... }
    public boolean cooldownFor(EventType type, Duration cooldown, Clock clock) { ... }
}
```

> **Phân tầng truth:**
> - **Hot path (real-time scoring, threshold evaluation)**: Redis hash `risk:{attempt_id}` là
>   **authoritative hot-state** — Lua CAS chạy ở đây, không query PG trong hot path.
> - **Audit truth (append-only, compliance, appeal evidence)**: PG `cheat_events` — mỗi state
>   change được **double-write** async qua buffered writer (batch 1000 row/500ms).
> - Redis mất → rebuild từ PG + Kafka replay (`cheat.event.raw.v1` retention 30 ngày) — xem §XVIII.7.
> - Không có mâu thuẫn với §5.2 "Redis KHÔNG phải nguồn truth cho audit" — hai role khác nhau.

### 3.2 Aggregate: `CheatEvent` (append-only — PG là nguồn truth)

```java
public record CheatEvent(
    UUID id,
    UUID attemptId,
    UUID userId,
    EventType eventType,           // enum map với DDL cheat_event_type (schema.sql:50-58)
    int eventLayer,                // 1..6
    Severity severity,
    int riskDelta,                 // weight đã nhân decay + frequency multiplier + confidence
    JsonNode eventData,            // JSONB — payload tầng-đặc-trưng
    Instant clientTimestamp,
    Instant serverTimestamp,
    Integer questionIndex,         // nullable
    String autoAction,             // nullable — "warn" | "suspend" | "terminate"
    UUID reviewedBy,               // nullable
    String reviewDecision,         // nullable — "confirmed" | "dismissed"
    Instant reviewedAt
) { }
```

`EventType` Java enum **phải map 1:1** với DDL `cheat_event_type` ENUM (`schema.sql:50-58`). Khi thêm event type:
1. Thêm vào `cheat_event_type` qua Flyway delta trong `schema.sql` (schema master).
2. Cập nhật Java enum + weight config YAML.
3. Register Avro schema `cheat.event.detected.v1` với field mới có `default`.

### 3.3 Value object: `LayerResult` + `RiskScore`

```java
public record LayerResult(
    int layer,                     // 1..6
    EventType eventType,
    int baseWeight,
    double confidence,             // [0.0, 1.0]
    JsonNode debugPayload          // chi tiết feature trigger (chỉ log, không gửi client)
) { }

public record RiskScore(
    int total,                     // 0-100+, cap hiển thị 100
    Severity severity,
    List<CheatEventRef> contributingEvents,
    Instant lastUpdatedAt,
    long stateVersion              // tăng monotonic mỗi lần update
) { }

public enum Severity { LOW, MEDIUM, HIGH, CRITICAL }
```

### 3.4 Policy: `WeightConfig` + `ThresholdConfig` (YAML-driven, hot-reloadable)

> **⚠️ Constraint:** key trong `weights.yaml` **PHẢI** là subset của DDL ENUM
> `cheat_event_type` (`schema.sql:50-58`). Event type mới phải extend ENUM qua Flyway
> delta trong §18.1 **trước** khi thêm vào YAML. CI gate `weights-vs-enum-check`
> assert hai tập trùng nhau.

```yaml
# config/weights.yaml — load tại startup + reload qua /admin/cheat/weights PATCH
weights:
  # L1 — Client behavior (schema.sql:51)
  tab_switch:            { base: 3,  subsequent: 5, cooldown_sec: 2,   layer: 1, confidence: 0.80 }
  window_blur:           { base: 2,  subsequent: 3, cooldown_sec: 2,   layer: 1, confidence: 0.80 }
  fullscreen_exit:       { base: 5,                 cooldown_sec: 5,   layer: 1, confidence: 0.85 }
  copy_event:            { base: 5,                 cooldown_sec: 3,   layer: 1, confidence: 0.80 }
  paste_event:           { base: 7,                 cooldown_sec: 3,   layer: 1, confidence: 0.80 }
  right_click:           { base: 2,                 cooldown_sec: 5,   layer: 1, confidence: 0.70 }
  context_menu:          { base: 2,                 cooldown_sec: 5,   layer: 1, confidence: 0.70 }
  # L2 — Browser integrity (schema.sql:52 + extension v2.1)
  devtools_open:         { base: 15,                cooldown_sec: 10,  layer: 2, confidence: 0.90 }
  keyboard_shortcut:     { base: 10,                cooldown_sec: 10,  layer: 2, confidence: 0.75 }
  extension_detected:    { base: 15,                cooldown_sec: 60,  layer: 2, confidence: 0.70 }   # +v2.1 ENUM
  emulator_detected:     { base: 15,                cooldown_sec: 60,  layer: 2, confidence: 0.80 }   # +v2.1 ENUM
  headless_browser:      { base: 25,                cooldown_sec: 60,  layer: 2, confidence: 0.95 }   # +v2.1 ENUM (alias kiểm: Phase 2 thêm khi DDL enum mở rộng)
  # L3 — Network anomaly (schema.sql:53 + extension v2.1)
  ip_change:             { base: 25,                cooldown_sec: 30,  layer: 3, confidence: 0.90 }
  geolocation_change:    { base: 30,                cooldown_sec: 60,  layer: 3, confidence: 0.90 }
  vpn_detected:          { base: 20,                cooldown_sec: 300, layer: 3, confidence: 0.85 }
  multiple_ip:           { base: 25,                cooldown_sec: 60,  layer: 3, confidence: 0.90 }
  proxy_detected:        { base: 15,                cooldown_sec: 300, layer: 3, confidence: 0.75 }   # +v2.1 ENUM
  high_latency_jump:     { base: 5,                 cooldown_sec: 30,  layer: 3, confidence: 0.50 }   # +v2.1 ENUM (low confidence signal)
  # L4 — Behavior analytics (schema.sql:54)
  typing_anomaly:        { base: 8,                 cooldown_sec: 30,  layer: 4, confidence: 0.60 }
  answer_speed_anomaly:  { base: 6,                 cooldown_sec: 30,  layer: 4, confidence: 0.60 }
  idle_too_long:         { base: 3,                 cooldown_sec: 60,  layer: 4, confidence: 0.40 }
  answer_pattern:        { base: 10,                cooldown_sec: 60,  layer: 4, confidence: 0.65 }
  # L5 — Vision proctoring (schema.sql:55-56)
  face_missing:          { base: 20,                cooldown_sec: 5,   layer: 5, confidence: 0.85 }
  multiple_faces:        { base: 35,                cooldown_sec: 5,   layer: 5, confidence: 0.90 }
  phone_detected:        { base: 30,                cooldown_sec: 5,   layer: 5, confidence: 0.90 }
  gaze_off_screen:       { base: 10,                cooldown_sec: 3,   layer: 5, confidence: 0.70 }
  audio_detected:        { base: 15,                cooldown_sec: 10,  layer: 5, confidence: 0.70 }
  environment_issue:     { base: 8,                 cooldown_sec: 30,  layer: 5, confidence: 0.60 }
  # L6 — Statistical (post-exam, report_only) (schema.sql:57 + extension v2.1)
  answer_similarity:     { base: 0, layer: 6, confidence: 0.70, report_only: true }
  sync_submission:       { base: 0, layer: 6, confidence: 0.60, report_only: true }
  score_anomaly:         { base: 0, layer: 6, confidence: 0.50, report_only: true }
  wrong_answer_match:    { base: 0, layer: 6, confidence: 0.85, report_only: true }   # +v2.1 ENUM
  submission_time_cluster: { base: 0, layer: 6, confidence: 0.70, report_only: true } # +v2.1 ENUM
  answer_change_pattern: { base: 0, layer: 6, confidence: 0.55, report_only: true }   # +v2.1 ENUM
  # Misc — non-scoring, audit only
  manual_proctor_note:   { base: 0, layer: 0, confidence: 1.00, report_only: true }   # +v2.1 ENUM
  suspicious_clock_skew: { base: 0, layer: 0, confidence: 1.00, report_only: true }   # +v2.1 ENUM (HMAC §13.1)
```

**Rule `report_only: true`**: event ghi `cheat_events` với `risk_delta=0`, **không** cộng vào risk_score và **không** trigger threshold. L6 Flink output + L0 audit note đều ở nhóm này.

```yaml
# config/thresholds.yaml
thresholds:
  low:      { max: 29 }
  medium:   { max: 59, action: warn_student, dispatch: websocket }
  high:     { max: 79, action: recommend_suspend, dispatch: [websocket, exam_alert, proctor_notify] }
  critical: { min: 80, action: recommend_terminate, dispatch: [websocket, exam_alert, proctor_notify, pager] }

grace_period_sec: 30       # 30s đầu attempt không trigger alert (client-side boot)
decay_half_life_min:
  l1: 30
  l2: 30
  l3: 60
  l4: 20
  l5: 15
```

**Enforcement pattern**:

```java
// ❌ KHÔNG hardcode
if (score >= 60) { ... }

// ✅ Làm thế này — YAML-driven, admin PATCH được runtime
Severity sev = thresholdConfig.classify(score);
if (sev.isAtLeast(HIGH)) { dispatcher.recommendSuspend(ctx); }
```

### 3.5 RBAC — dùng permission code từ Auth (không hardcode role)

Tham chiếu `docs/auth-service-design.md` §3.4 — ma trận permission có sẵn cho CDS:

| Permission code | Endpoint / action |
| --------------- | ----------------- |
| `cheat.review` | GET review queue, GET attempt timeline |
| `cheat.decide` | POST decide (confirm / dismiss / escalate) |
| `cheat.video.view` | GET proctoring video clip |
| `cheat.appeal.submit` | POST appeal (student only, của attempt mình) |
| `cheat.appeal.resolve` | POST appeal resolve (instructor own exam / admin) |
| `cheat.config.weights` | PATCH weight YAML (platform_admin only) |
| `attempt.read.org` | GET attempt context khi review |
| `attempt.suspend` | **Không gán CDS** — CDS chỉ *recommend*, Exam Service own quyền suspend |

Enforce ở `@PreAuthorize` với permission check (không role check):

```java
@PreAuthorize("hasAuthority('cheat.decide') and @reviewPolicy.canAct(authentication, #queueId)")
@PostMapping("/api/v1/cheat/review/{queueId}/decide")
public void decide(@PathVariable UUID queueId, @RequestBody DecisionDto dto) { ... }
```

`@reviewPolicy.canAct` là bean tự viết, verify: queue item thuộc org của proctor, assigned_to matches JWT sub (hoặc admin override), status in `['pending','in_review']`.

---

## IV. DATA MODEL — invariants & business rules

> **⚠️ DDL là nguồn truth duy nhất ở `database/postgresql/schema.sql` §8 "Cheat events & proctoring"
> và §12 "Cheating detection mở rộng".** Section này **KHÔNG copy cột** — chỉ mô tả invariant,
> retention, policy, usage pattern mà DDL không thể diễn đạt. Nếu cần thêm/đổi cột: sửa
> `schema.sql` trước, rồi update Flyway migration delta (§XVIII.1), rồi mới động tới prose ở đây.

CDS thao tác các bảng sau (line numbers có thể dịch khi schema edit — nếu mismatch, `grep -n '^CREATE TABLE <name>' schema.sql`):

### 4.1 `cheat_events` (schema §8, line 405)

**Invariants business-level:**
- **Append-only**: insert qua buffered writer (1000 row/batch, flush mỗi 500ms). KHÔNG UPDATE trừ khi review: `UPDATE SET reviewed_by=?, review_decision=?, reviewed_at=NOW() WHERE id=? AND reviewed_by IS NULL` (idempotent, chỉ first-write thắng).
- **Partition theo tháng**: `PARTITION BY RANGE (server_timestamp)` — tạo partition `y2026m04 → y2026m12` upfront; cron tạo partition mới cuối tháng; partition cũ > 24 tháng detach + dump S3 Glacier.
- **event_data JSONB** schema phụ thuộc `event_type`:
  - `tab_switch`: `{"duration_ms": int, "tab_count": int}`
  - `ip_change`: `{"old_ip": "1.2.3.4", "new_ip": "5.6.7.8", "old_country": "VN", "new_country": "US"}` — **IP raw chỉ lưu ở PG, KHÔNG log ra Loki** (masking filter §XIV.4)
  - `face_missing`: `{"duration_sec": int, "frame_s3_keys": ["..."]}`
  - `answer_similarity`: `{"similar_attempt_id": "UUID", "similarity_score": 0.93, "matched_questions": [1,5,7]}` (L6)
- **Retention**: 24 tháng. Partition old → S3 Glacier parquet dump.
- **Ownership**: student có thể `GET /api/v1/cheat/attempts/{id}/events` **sau khi exam completed** (privacy — không xem được risk của mình trong thi).

### 4.2 `proctoring_sessions` (schema §8, line 431)

**Invariants:**
- **Unique per attempt** (`attempt_id UNIQUE NOT NULL`). Tạo khi L5 bật (`exam.proctoring_level = 2`).
- **Video S3 key** lưu reference, không lưu binary. S3 bucket `proctoring-frames` + `proctoring-video` encrypted SSE-KMS, TTL 12 tháng via lifecycle rule.
- **Counter fields** (`total_frames_analyzed`, `face_detected_frames`, `face_missing_frames`, ...) update incremental qua Redis atomic, flush mỗi 30s vào PG.
- **ended_at** set khi attempt `submitted|expired|cancelled` — trigger video finalize (concat frame → mp4, upload, update `video_s3_key`).
- **`ai_risk_summary` JSONB**: aggregate summary cho review UI — `{"suspicious_frame_ratio": 0.15, "phone_sightings": 3, "gaze_off_pct": 0.22}`.

### 4.3 `cheat_review_queue` (schema §12, line 637)

**Invariants:**
- **State machine** `status`:
  ```
  pending ──pickup──► in_review ──decide──► resolved
                           │
                           └──escalate──► escalated ──admin decide──► resolved
  ```
- **Trigger insert**: khi `risk_score` cross threshold `high|critical` → Use Case `DispatchAlertUseCase` INSERT row status=`pending`, `risk_score_at_trigger` = snapshot.
- **Auto-assign**: round-robin trong danh sách proctor online cùng org (Redis `proctor:online:{org_id}` sorted set by `last_heartbeat`). Nếu không có proctor online → status=`pending` + page admin.
- **SLA enforcement**: cron mỗi 30s scan `status in ('pending','in_review') AND created_at < NOW() - interval <sla_by_severity>` → auto-escalate + page (§X.2).
- **Idempotent pickup**: `UPDATE ... SET assigned_to=?, status='in_review' WHERE id=? AND status='pending'` → 0 row = đã assign (409).
- **Retention**: giữ mãi (evidence audit cho appeal); partition theo tháng `created_at` khi > 10M row.

### 4.4 `cheat_appeals` (schema §12, line 655)

**Invariants:**
- **Appeal window**: 30 ngày sau `exam_attempts.completed_at`. Quá hạn → `410 CHEAT_APPEAL_WINDOW_CLOSED`. Enforce ở application layer, không qua DB constraint (để doc policy rõ ràng + dễ đổi).
- **evidence_s3_keys**: TEXT[] trỏ tới S3 bucket `appeal-evidence/{user_id}/{appeal_id}/`, student tự upload qua presigned URL. Max 10 file × 10 MB.
- **status machine** `pending → under_review → resolved`. Quyết định: `upheld` (giữ suspend) hoặc `overturned` (khôi phục attempt).
- **Assign**: instructor chủ exam (`exams.created_by`) — load qua gRPC `ExamService.GetExamOwner(exam_id)` lúc insert.
- **Publish event khi resolved**: `cheat.appeal.resolved.v1` qua **outbox** (Exam Service consume để restore attempt nếu overturned).

### 4.5 `outbox` & `processed_events` (schema §13, line 679-713)

CDS là **producer** của `outbox` cho event critical (§XII.1), **consumer** dùng `processed_events` dedup cho:
- `exam.attempt.completed.v1` (trigger L6 Flink job)
- `exam.answer.submitted.v1` (feed L4 answer pattern)
- `auth.role.changed.v1` (invalidate permission cache proctor assignment)

Consumer group names (ADR-002 §Implementation):
- `cheat-detection-raw-consumer` — `cheat.event.raw.v1`
- `cheat-detection-answer-consumer` — `exam.answer.submitted.v1`
- `cheat-detection-attempt-consumer` — `exam.attempt.completed.v1`
- `cheat-detection-auth-consumer` — `auth.role.changed.v1`, `auth.account.locked.v1`

Tách **riêng với Analytics** (`analytics-*-consumer`) — Kafka fan-out đúng pattern, không share group.

---

## V. HOT STATE — REDIS

### 5.1 Key schema (đã có trong `database/redis/schema.md` Nhóm 7, bổ sung rule TTL chặt)

| Key | Kiểu | TTL | Nội dung | Rebuild path khi mất |
| --- | ---- | --- | -------- | -------------------- |
| `risk:{attempt_id}` | Hash | `attempt_duration + 2h` | `total`, `severity`, `events` (JSON array last 100), `last_update_ms`, `state_version`, `auto_suspended` | Kafka replay `cheat.event.raw.v1` từ `attempt.started_at` + PG `cheat_events` |
| `baseline:typing:{user_id}` | Hash | 30 ngày, refresh mỗi login | `mean_kpi`, `stddev`, `samples` (count) | ClickHouse `cheat_analytics.typing_baseline` (Analytics Service own, read-only cross-service) |
| `attempt:ips:{id}` | Set | `attempt_duration + 1h` | Set of IPs đã thấy | Rebuild từ `cheat_events` WHERE `event_data @> '{"ip": ...}'` |
| `attempt:geo:{id}` | Hash | `attempt_duration + 1h` | `country`, `city`, `lat`, `lon` | Lost = loss function low (new events enrich lại) |
| `proctor:online:{org_id}` | Sorted set | heartbeat 30s → score=ts | `{proctor_id → last_heartbeat_ms}` | Heartbeat rebuild tự động |
| `proctor:queue:{proctor_id}` | List | 1h | Assigned queue item IDs (FIFO) | Rebuild từ `cheat_review_queue WHERE assigned_to=? AND status='in_review'` |
| `cooldown:{attempt_id}:{event_type}` | String `NX PX` | 2-300s tùy type | `"1"` (sentinel) | Not rebuild (transient) |
| `dedup:cheat:event:{event_id}` | String `NX EX 3600` | 1h | `"1"` — Kafka consumer dedupe | Trước khi có `processed_events`; ADR-001 dùng `processed_events` làm truth, Redis chỉ cache nhanh |

### 5.2 Rule Redis

- **Redis KHÔNG phải nguồn truth** cho audit — PG `cheat_events` là truth. Redis mất → rebuild từ Kafka + PG (§XVIII).
- **Lua atomic script** cho update risk score (§VIII.2) — đảm bảo compare-and-update state_version.
- **Cluster mode**: key pattern đảm bảo cùng attempt → cùng hash slot qua `{attempt_id}` hash tag:
  ```
  risk:{a1b2c3d4-...}       → slot X
  attempt:ips:{a1b2c3d4-...} → slot X  (cùng attempt → cùng node, multi-key Lua OK)
  ```

---

## VI. DATA RETENTION & PRIVACY

| Dữ liệu | Retention | Storage | Privacy rule |
| ------- | --------- | ------- | ------------ |
| `cheat_events` | 24 tháng (partition month) | PG → S3 Glacier parquet | IP raw chỉ ở PG, mask ở log (§XIV.4) |
| `proctoring_sessions.video_s3_key` | 12 tháng | S3 SSE-KMS | Chỉ `cheat.video.view` permission; log mọi GET |
| `cheat_review_queue` | Vĩnh viễn | PG (partition month > 10M) | Kèm `reviewed_by`, audit immutable |
| `cheat_appeals` + evidence S3 | 5 năm sau resolve | PG + S3 | Student own data |
| L6 similarity records | Vĩnh viễn (subset `cheat_events` event_type=`answer_similarity`) | PG + ClickHouse `cheat_analytics` | Anonymize user_id khi share cross-org research |
| Typing keystroke raw (L4) | **KHÔNG LƯU** — chỉ derived features (`mean_kpi`, `stddev`) | Redis baseline + ClickHouse aggregate | PII risk cao (có thể identify qua rhythm) |
| Video frame raw (1fps) | 30 ngày sau exam completed (S3 lifecycle) | S3 `proctoring-frames/` | Video mp4 concat giữ 12 tháng |

**GDPR / user deletion flow**:
- Consume `auth.user.deleted.v1` → anonymize `cheat_events.user_id` set NULL + `event_data` strip PII; `cheat_appeals` giữ (legal hold); video S3 delete qua lifecycle + manual trigger.
- Event `cheat.user.anonymized.v1` publish cho Analytics sync.

---

## VII. 6 LAYER DETECTION

### 7.1 L1 — Client Behavior (browser events)

**Event types & base weights** (đầy đủ ở `config/weights.yaml`, bảng dưới là summary):

| Event | Trigger client-side | Base weight | Severity |
| ----- | ------------------ | ----------- | -------- |
| `tab_switch` | `visibilitychange` API | +3 first, +5 subsequent | low |
| `window_blur` | `blur` event | +2 first, +3 subsequent | low |
| `fullscreen_exit` | `fullscreenchange` | +5 | medium |
| `copy_event` | `copy` / `cut` | +5 | medium |
| `paste_event` | `paste` | +7 | medium |
| `right_click` | `contextmenu` (nếu blocked) | +2 | low |
| `keyboard_shortcut` | Ctrl+C/V/U/Shift+I | +3-10 tuỳ shortcut | low-medium |
| `context_menu` | `contextmenu` attempt | +2 | low |

**Frequency multiplier**: `multiplier = min(3.0, 1 + 0.2 × N_recent_events_in_60s)`
**Time decay**: `decayed_weight = base × exp(-age_minutes / half_life_L1)` với `half_life_L1=30 min`.

**Thread model**: Virtual Threads (I/O path — Redis pipe + PG async batch). Pipeline stateless per event, trạng thái chỉ ở Redis.

### 7.2 L2 — Browser Integrity

| Event | Detection method | Base weight | Severity |
| ----- | ---------------- | ----------- | -------- |
| `devtools_open` | timing `debugger` stmt; window size heuristic | +15 | high |
| `extension_detected` | fingerprint known extensions (Grammarly, Chegg) | +15 | medium-high |
| `emulator_detected` | canvas/WebGL fingerprint mismatch | +15 | high |
| `headless_browser` | `navigator.webdriver` + canvas | +25 | critical |
| `keyboard_shortcut` | Ctrl+Shift+I, F12, Ctrl+C/V/U attempts bị block | +10 | medium |

### 7.3 L3 — Network Anomaly

| Event | Detection | Base weight |
| ----- | --------- | ----------- |
| `ip_change` | IP khác giữa các request (trừ 1 lần reconnect hợp lệ trong 30s) | +25 |
| `geolocation_change` | GeoIP country/city > 100km trong < 5 phút | +30 |
| `vpn_detected` | IPQualityScore + local blocklist | +20 |
| `proxy_detected` | Header `X-Forwarded-For` patterns | +15 |
| `multiple_ip` | Cùng attempt có > 2 IP khác nhau | +25 |
| `high_latency_jump` | RTT bất thường giữa các request | +5 (low confidence) |

**Implementation:**
- Mỗi request HTTP/WS từ student (qua Exam Service) forward IP → CDS enrich GeoIP offline (MaxMind mmdb mount).
- So sánh với `attempt:geo:{id}` (lưu khi start). First IP → set baseline, không trigger.
- Redis `attempt:ips:{id}` Set → bất kỳ IP mới → trigger event.
- **Circuit breaker** cho IPQualityScore (Resilience4j): `ringBufferSize=100, failureRateThreshold=50%, waitDurationInOpenState=30s`. Fallback: `vpn_detected` chỉ dựa local blocklist, confidence giảm 0.85 → 0.65.

### 7.4 L4 — Behavior Analytics (Kafka Streams)

**Typing dynamics:**
- Khoảng thời gian giữa các phím gõ (KPI — Keystroke Press Interval) thu qua `keystroke.sample.v1` event.
- Baseline user thu qua 5 attempt đầu (Analytics Service maintain, CDS read qua Redis `baseline:typing:{user_id}`).
- z-score per question; `|z| > 3` trên phần lớn câu → `typing_anomaly`, weight +8.
- **KHÔNG lưu raw keystroke** (PII) — chỉ derived features (mean, stddev, distribution percentile).

**Answer speed:**
- Thời gian trung bình cho từng loại câu hỏi (theo `metadata.estimated_time_seconds` từ Question Service).
- < 10% thời gian dự kiến AND đúng → `answer_speed_anomaly`, weight +6.
- > 3x thời gian dự kiến AND đúng → có thể copy, weight +4.

**Answer pattern:**
- Student trả lời tất cả câu đúng liên tục không sai câu nào cho exam khó → anomaly +10.
- Pattern copy: thời gian nhàn rỗi rồi đột ngột trả lời nhanh đúng → +8.

**Idle too long:**
- Không hoạt động > 5 phút giữa câu hỏi → `idle_too_long`, weight +3 (context: có thể đi vệ sinh, chưa chắc cheat).

**Kafka Streams topology:**

```java
KStream<AttemptId, KeystrokeSample> keystrokes = builder.stream("exam.keystroke.sample.v1",
    Consumed.with(keySerde, keystrokeSerde));

keystrokes
    .groupByKey()
    .windowedBy(TimeWindows.ofSizeAndGrace(Duration.ofSeconds(60), Duration.ofSeconds(5)))
    .aggregate(
        KeystrokeStats::empty,
        (attemptId, sample, agg) -> agg.addSample(sample),
        Materialized.<AttemptId, KeystrokeStats>as(Stores.persistentKeyValueStore("keystroke-stats"))
                    .withKeySerde(attemptIdSerde)
                    .withValueSerde(keystrokeStatsSerde)
    )
    .toStream()
    .filter((winKey, stats) -> {
        var baseline = baselineStore.get(stats.userId());
        return baseline != null && stats.zScore(baseline) > 3.0;
    })
    .mapValues(stats -> TypingAnomalyDetected.newBuilder()
        .setAttemptId(stats.attemptId())
        .setZScore(stats.zScore())
        .setSampleCount(stats.samples())
        .build())
    .to("cheat.event.detected.v1", Produced.with(attemptIdSerde, typingAnomalySerde));
```

### 7.5 L5 — Vision (Proctoring — chỉ `proctoring_level=2`)

**Trigger**: `exam.proctoring_level = 2` khi start attempt. Client extract 1 fps frame → upload S3 bucket `proctoring-frames` (presigned URL) → publish `proctoring.frame.captured.v1`.

**Pipeline thread model** (quan trọng — **KHÔNG dùng Virtual Thread cho vision pre-processing**):

```yaml
cheat:
  vision:
    preproc-pool-size: 3          # = vCPU - 1 (chừa headroom cho VT I/O S3 download +
                                  # Triton gRPC client + Spring main loop trên pod cpu=4).
                                  # Pool=4 đã từng gây throttle lúc peak — §M7 review.
    preproc-queue-capacity: 256   # overflow → drop frame + metric (fail-fast, không back-pressure
                                  # để tránh chồng lag vào S3 downloader VT)
    triton-timeout-ms: 800        # p99 single batch 8 frames ~400ms, budget 2x
    batch-size: 8                 # latency vs GPU utilization trade-off
    batch-max-wait-ms: 150        # nếu không đủ 8 frame trong 150ms, flush batch
```

Rejection policy chọn `AbortPolicy` (thay vì `CallerRunsPolicy`) có chủ ý: vision pipeline
**fail-open** — drop frame khi quá tải vẫn an toàn (frames 1fps, mất 1-2 frame không ảnh hưởng
detection chất lượng). Dùng `CallerRunsPolicy` sẽ đẩy back-pressure ngược vào Kafka consumer
thread → consumer lag toàn bộ topic `proctoring.frame.captured.v1` → ảnh hưởng mọi attempt
cùng pod, đáng ghét hơn drop frame cục bộ.

```java
@Bean(name = "visionPreprocExecutor")
ThreadPoolTaskExecutor visionPreprocExecutor(@Value("${cheat.vision.preproc-pool-size}") int cores,
                                             @Value("${cheat.vision.preproc-queue-capacity}") int queue) {
    var exec = new ThreadPoolTaskExecutor();
    exec.setCorePoolSize(cores);
    exec.setMaxPoolSize(cores);
    exec.setQueueCapacity(queue);
    exec.setThreadNamePrefix("vision-pre-");
    exec.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());  // fast-fail → drop + metric
    return exec;
}
```

**Inference pipeline:**

```
proctoring.frame.captured.v1
        │
        ▼
FrameConsumer (Virtual Thread I/O — S3 download)
        │
        ▼
VisionPreprocExecutor (bounded platform pool — CPU decode + reshape)
        │
        ▼
TritonBatcher (accumulate 8 frames or 150ms, whichever first)
        │
        ▼
TritonGrpcClient.infer(batch)  ── async gRPC ──► Triton pod (GPU)
        │                                            │
        │                                            ├─ face_detection (RetinaFace)
        │                                            ├─ object_detection (YOLOv8)
        │                                            └─ gaze_estimation (FaceMesh)
        ▼
Per-frame result aggregator (VT I/O):
  - 0 face > 5s liên tục  → face_missing (+20)
  - >1 face                 → multiple_faces (+35)
  - phone/book/earphone     → phone_detected (+30)
  - gaze off-screen > 3s    → gaze_off_screen (+10)
        │
        ▼
Publish cheat.event.detected.v1 (fire-and-forget) + update AttemptRiskContext
```

**Cost control:** L5 chỉ bật cho `proctoring_level=2`. 1000 student × 60 phút × 1 fps = 3.6M frames → ~10 GPU pod (A10G) peak. Chỉ enterprise plan enable.

### 7.6 L6 — Statistical Cross-Attempt (post-exam Flink)

Chạy **sau khi exam completed** (consume `exam.attempt.completed.v1`) trên Flink batch job. Module riêng `flink-job-l6/`.

| Indicator | Phương pháp | Confidence | Auto-action |
| --------- | ----------- | ---------- | ----------- |
| `answer_similarity` | Cosine similarity giữa answer vectors (MC option sequence) cùng exam | Flag nếu > 0.92 | Insert `cheat_events` layer=6; KHÔNG suspend (exam xong) |
| `wrong_answer_match` | 2 students có ≥ 5 câu sai giống nhau + cùng option sai | High | Insert + auto review queue escalate |
| `submission_time_cluster` | 2+ attempts submit trong < 10s, từ IP khác | Medium | Flag, proctor review batch |
| `score_anomaly` | Score vượt 3σ baseline class | — | Report only, không block |
| `answer_change_pattern` | Change đáp án trước submit theo pattern bị coached | Low | Report only |

**Flink job pseudo:**

```java
DataStream<AttemptCompleted> attempts = env.fromSource(
    KafkaSource.<AttemptCompleted>builder()
        .setBootstrapServers(bootstrap)
        .setTopics("exam.attempt.completed.v1")
        .setGroupId("cheat-l6-flink-consumer")
        .setValueOnlyDeserializer(avroDeserializer)
        .build(),
    WatermarkStrategy.forBoundedOutOfOrderness(Duration.ofMinutes(5)),
    "attempts");

// Window chiến lược — xem tradeoff note bên dưới
attempts
    .keyBy(AttemptCompleted::examId)
    .window(SessionWindows.withGap(Time.hours(1)))
    .process(new SimilarityDetector(threshold = 0.92))
    .addSink(new KafkaSink<>("cheat.statistical.result.v1", ...));
```

**Window strategy — tradeoff:**

| Strategy | Ưu | Nhược | Dùng khi |
| -------- | -- | ----- | -------- |
| `SessionWindows.withGap(1h)` *(mặc định)* | Gom tự nhiên các attempt submit gần nhau; fire 1 lần sau gap 1h idle | Exam mở cả tuần (self-paced) → window có thể fire nhiều lần liên tiếp → compute similarity chồng lấn; state size lớn | Exam scheduled (cùng khung giờ cố định) — 80% use case |
| `TumblingEventTimeWindows.of(Time.hours(1))` + keyed by `examId` | Fire định kỳ, state bounded, cost predictable | Có thể chia cắt sai attempt nếu submit sát boundary | — |
| **Trigger theo `exam.ends_at`** (custom trigger fire khi watermark > `ends_at + 30min`) | Chính xác 1 lần per exam; cost tối ưu | Phải inject `ends_at` vào state; implementation phức tạp | Exam self-paced hoặc exam dài > 24h |

**Quyết định MVP:** SessionWindow 1h. Re-evaluate khi có ≥ 1 exam self-paced > 24h hoặc khi
metric `cheat_flink_window_fire_duplicates_total` > 0 — chuyển sang custom trigger theo `ends_at`.

Output → main service consume → INSERT `cheat_events` với `event_layer=6`, severity `low|medium`.

---

## VIII. RISK SCORE ALGORITHM

### 8.1 Công thức

```
risk_score(attempt) = Σ_events ( base_weight × frequency_mult × time_decay × layer_confidence )

- base_weight: YAML config (§3.4)
- frequency_mult: 1 + 0.2 × recent_same_type_count, cap 3.0
- time_decay: exp(-age_minutes / half_life_L{layer})
    L1: 30m, L2: 30m, L3: 60m, L4: 20m, L5: 15m, L6: ∞ (không decay — post-exam fact)
- layer_confidence: [0.0, 1.0] từ YAML
```

**Score cap hiển thị**: UI cap 100; internal có thể > 100 để rule critical vẫn trigger.

### 8.2 Triển khai — Redis Lua atomic (với state_version CAS)

```lua
-- KEYS[1]=risk:{attempt_id}
-- ARGV[1]=event_json, ARGV[2]=expected_state_version, ARGV[3]=weight_config_version
-- Return: {new_total, new_severity, new_state_version, crossed_threshold}

-- HMGET trả mảng theo THỨ TỰ keys yêu cầu — field thiếu trả nil.
-- KHÔNG dùng HGETALL ở đây: Lua/Redis trả HGETALL thành flat array
-- {k1, v1, k2, v2, ...}, không phải named hash → truy cập theo tên
-- (current.state_version) sẽ luôn nil → fencing CAS bị bypass.
local vals = redis.call('HMGET', KEYS[1],
    'state_version', 'total', 'severity', 'events_json')

local state_version = tonumber(vals[1] or 0)
local total         = tonumber(vals[2] or 0)
local severity_prev = vals[3] or 'low'

if ARGV[2] ~= '*' and tonumber(ARGV[2]) ~= state_version then
    return {-1, 'CAS_FAIL', state_version, 0}
end

local event = cjson.decode(ARGV[1])
-- Apply decay to existing events (re-compute từ events_json)
-- Apply frequency multiplier lookup last 60s same type
-- ... (đầy đủ trong src/main/resources/scripts/risk_score_update.lua)

local new_total     = math.floor(total + event.weighted_delta)
local new_severity  = severity_classify(new_total)
local crossed       = (new_severity ~= severity_prev) and 1 or 0
local new_version   = state_version + 1

redis.call('HSET', KEYS[1],
    'total', new_total,
    'severity', new_severity,
    'state_version', new_version,
    'last_update_ms', ARGV[4])
redis.call('EXPIRE', KEYS[1], 7200)

return {new_total, new_severity, new_version, crossed}
```

Java wrapper gọi script load-once qua `SCRIPT LOAD`, execute qua `EVALSHA`.

#### 8.2.1 Caller retry pattern cho CAS_FAIL

Khi script trả `{-1, 'CAS_FAIL', current_version, 0}`, caller phải **read-then-apply retry**:

```java
static final int MAX_RETRY = 3;
static final long[] BACKOFF_MS = {10, 50, 200};

public RiskEvaluation applyEvent(UUID attemptId, CheatEvent event) {
    for (int attempt = 0; attempt <= MAX_RETRY; attempt++) {
        long currentVersion = riskStateStore.readVersion(attemptId);  // HGET state_version
        var result = luaScript.execute(attemptId, event, currentVersion);
        if (!"CAS_FAIL".equals(result.status())) {
            return result;
        }
        if (attempt < MAX_RETRY) {
            Thread.sleep(BACKOFF_MS[attempt]);
        }
    }
    // Kiệt retry — drop event, metric + log warn (không fail pipeline, fail-open)
    metrics.casExhausted.increment(event.eventType().name());
    log.warn("CAS exhausted for attempt={} event={}", attemptId, event.eventType());
    return RiskEvaluation.dropped(attemptId);
}
```

Metric mới: `cheat_cas_exhausted_total{event_type}` — alert warning > 5/min (concurrency quá cao, cần debug).

### 8.3 Thresholds & actions

| Score | Severity | Action auto | Alert dispatch | Event outbox |
| ----- | -------- | ----------- | -------------- | ------------ |
| 0-29 | low | — (log only) | — | `cheat.event.detected.v1` (fire-and-forget) |
| 30-59 | medium | `flagged_for_review=true` trên Exam Service attempt; WebSocket warning học sinh | WS student + email proctor (async) | `cheat.event.detected.v1` fire-and-forget |
| 60-79 | high | Recommend Exam Service `suspend` | WS proctor + WS student + email proctor | **`cheat.alert.generated.v1` qua outbox** (critical) |
| 80+ | critical | Recommend Exam Service `terminate` | Proctor WS + **PagerDuty** on-call | **`cheat.alert.generated.v1` qua outbox** (critical) + `cheat.alert.critical.v1` fire-and-forget cho pager |

### 8.4 Fencing token — tránh race với Exam Service suspend

Exam Service có `exam_attempts.state_version BIGINT` (CLAUDE.md §9.4 prereq). Khi CDS publish `cheat.alert.generated.v1` payload kèm `state_version_at_detection`. Exam Service compare:

```sql
UPDATE exam_attempts
SET status = 'suspended', state_version = state_version + 1, ...
WHERE id = ? AND state_version = ?;   -- fencing — nếu user đã submit trước CDS alert đến, state_version sẽ khác → update 0 row → skip suspend
```

Nếu `affected_rows=0` → Exam Service publish `exam.attempt.suspend_skipped.v1` lý do `STALE_STATE_VERSION` → CDS log warning, không retry suspend.

### 8.5 Ngăn ngừa false positive

- **Cooldown** giữa các event cùng loại (`cooldown:{attempt_id}:{event_type}` TTL 2-300s) → không cộng dồn.
- **Context awareness**: `proctoring_level=0` (casual) → không publish `cheat.alert.generated.v1` dù score high; chỉ log + medium warning.
- **Grace period 30s đầu attempt**: không trigger alert (client-side code đang khởi động).
- **Whitelist**: giáo viên PATCH `/admin/cheat/whitelist` `{org_id, exam_id?, event_type}` — event type bị whitelist không cộng risk.
- **Layer cap**: mỗi layer L1-L5 contribution cap 40 điểm — tránh 1 tầng (vd L1 tab_switch spam) vượt 100 một mình.

---

## IX. EVENT INGESTION

### 9.1 Path chính — Kafka

Client → Exam Service WebSocket → **Exam Service HMAC-sign** event → publish Kafka `cheat.event.raw.v1`.

```
cheat.event.raw.v1 (Kafka, 20 partition, retention 30 ngày)
        │
        ▼
KafkaRawEventConsumer (Spring Kafka, concurrency=10, VT)
        │
        ▼
HMAC verify (shared secret với Exam Service, rotate mỗi 30 ngày)
  - fail → publish to DLQ `cheat.event.raw.dlq.v1` + metric
        │
        ▼
Idempotent check: processed_events[event_id, 'cheat-detection-raw-consumer']
  - exists → skip (log count)
  - insert within same TX as business logic
        │
        ▼
Enrichment (parallel):
   - GeoIP (MaxMind offline)
   - User context (Auth gRPC BatchGetUsers, cache 60s)
   - Attempt context (Exam gRPC GetAttempt, cache 60s)
        │
        ▼
Route to Pipeline (L1/L2/L3/L4 handler per event_type)
        │
        ▼
Pipeline output: LayerResult {severity, weight, confidence}
        │
        ▼
RiskScoreCalculator.apply(attempt_id, LayerResult)  ── Lua atomic Redis
        │
        ▼
ThresholdEvaluator.evaluate(new_score, context)
        │
        ├─ medium → WS warning (Redis pub/sub ws:exam:{attempt})
        ├─ high   → DispatchAlertUseCase.publishAlert() qua outbox
        ├─ critical → DispatchAlertUseCase + pager fire-and-forget
        └─ always → cheat.event.detected.v1 fire-and-forget (analytics, CH)
        │
        ▼
CheatEventBufferedWriter.enqueue(CheatEvent)   (batch 1000/500ms → INSERT PG)
```

### 9.2 Path fallback — HTTP (mobile SDK offline buffer)

```
POST /api/v1/cheat/events
Body: [{CheatEventRaw}, ...]  (max 200 events/batch, max 1 MB body)
Headers:
  X-Cheat-HMAC: <hex signature>
  X-Cheat-Attempt-Id: <uuid>
  Authorization: Bearer <jwt>
  Idempotency-Key: <uuid>
```

- Server verify HMAC + JWT + attempt ownership.
- Publish batch vào `cheat.event.raw.v1` → path chính xử lý.
- Rate limit: 10 req/min/attempt (1 batch = 1 request).

### 9.3 Throughput target & consumer tuning

- **10.000 events/s/pod** với concurrency=10, partition=20.
- Buffered writer PG: 1000 events/batch, flush mỗi 500ms hoặc full, `@Async` trên VT pool.
- Consumer config: `max.poll.records=500, fetch.min.bytes=32KB, fetch.max.wait.ms=50`.

---

## X. REVIEW WORKFLOW

### 10.1 State machine

```
Alert high/critical
        │
        ▼
INSERT cheat_review_queue status='pending' + outbox cheat.review.queued.v1
        │
        ▼
Auto-assign proctor (round-robin online trong cùng org)
   └─ không có proctor online → status='pending', page admin
        │
        ▼
Proctor click "Pick up" → UPDATE ... WHERE status='pending' → status='in_review'
  - idempotent: 0 row = 409 CHEAT_REVIEW_ALREADY_ASSIGNED
        │
        ▼
Proctor xem:
   - Risk timeline (events list với time, layer, weight)
   - Video clip 30s trước/sau sự kiện critical (qua `cheat.video.view` permission)
   - Answer pattern so với class average (gRPC Analytics Service)
   - IP/geo history
        │
        ▼
Proctor decide qua POST /api/v1/cheat/review/{id}/decide { decision, reason }
  ├─ "confirmed" → publish cheat.review.decided.v1 (outbox) — Exam terminate
  ├─ "dismissed" → publish cheat.review.decided.v1 (outbox) — Exam resume, reset flag
  └─ "escalate"  → status='escalated', admin + giáo viên chính review; no Exam effect yet
        │
        ▼
UPDATE cheat_review_queue SET status='resolved', decision=?, reviewed_at=NOW()
        │
        ▼
Notification qua event consumer (student/giáo viên) — async
```

### 10.2 SLA enforcement

| Severity | Proctor pickup SLA | Escalation khi miss |
| -------- | ------------------ | ------------------- |
| critical | 1 phút | Auto-terminate attempt + page admin |
| high | 2 phút | Auto-terminate + notify giáo viên |
| medium | 10 phút (trong thi) | Batch review sau thi, no auto action |

Cron mỗi 30s — SQL core:

```sql
UPDATE cheat_review_queue
SET status = 'escalated', decision = 'auto_sla_miss'
WHERE status IN ('pending','in_review')
  AND created_at < NOW() - CASE severity
        WHEN 'critical' THEN INTERVAL '1 minute'
        WHEN 'high' THEN INTERVAL '2 minutes'
        WHEN 'medium' THEN INTERVAL '10 minutes'
      END
RETURNING id, attempt_id, severity, assigned_to;
```

**Implementation — UPDATE RETURNING + outbox PHẢI trong cùng TX** (ADR-001 invariant):

```java
@Component
class ReviewQueueSlaScanner {
    private final ReviewQueueRepository repo;
    private final CheatOutboxPublisher outbox;   // MANDATORY TX
    private final MeterRegistry metrics;

    @Scheduled(fixedDelay = 30_000)
    @SchedulerLock(name = "cheat-review-sla-scan", lockAtMostFor = "25s")  // ShedLock: chỉ 1 pod chạy
    @Transactional                                // REQUIRED — mở TX domain change + outbox
    public void scanSlaMiss() {
        List<EscalatedRow> escalated = repo.autoEscalateAndReturn();  // JDBC RETURNING → List

        for (EscalatedRow row : escalated) {
            var payload = ReviewSlaMissedPayload.builder()
                .queueId(row.id())
                .attemptId(row.attemptId())
                .severity(row.severity())
                .minutesOverdue(row.computeOverdueMinutes())
                .escalatedTo(row.assignedTo())   // null = page admin broadcast
                .build();
            outbox.publishReviewSlaMissed(payload);   // cùng TX với UPDATE
        }

        metrics.counter("cheat_sla_escalated_total").increment(escalated.size());
    }
}
```

Nếu pod crash giữa chừng: UPDATE + outbox INSERT cùng TX → PG rollback cả 2 → cron lần sau retry. **Không** split vào 2 TX riêng — sẽ dẫn tới escalated nhưng mất event.

### 10.3 Appeal workflow

```
POST /api/v1/cheat/appeals  (student auth, permission cheat.appeal.submit)
Body: { attempt_id, reason, evidence_urls[] }
        │
        ▼
Enforce appeal window: 30 ngày sau exam_attempts.completed_at
  - quá hạn → 410 CHEAT_APPEAL_WINDOW_CLOSED
        │
        ▼
INSERT cheat_appeals status='pending' + outbox cheat.appeal.submitted.v1
        │
        ▼
Assign giáo viên chủ exam (owner qua gRPC ExamService.GetExamOwner)
        │
        ▼
Giáo viên review:
   - Nguyên bản evidence từ proctor decision (timeline, video)
   - Student's argument + evidence S3 URLs
        │
        ▼
Giáo viên POST /api/v1/cheat/appeals/{id}/resolve { decision, reason }
  ├─ "upheld"      → attempt vẫn 0 điểm; publish cheat.appeal.resolved.v1 (outbox, decision=upheld)
  └─ "overturned"  → publish cheat.appeal.resolved.v1 (outbox, decision=overturned) — Exam Service restore attempt
        │
        ▼
Notification student + update cheat_appeals.resolved_at
```

---

## XI. API ENDPOINTS

### 11.0 API conventions

Áp dụng **y hệt Auth Service §12.0** (nguồn truth). Tóm tắt rule quan trọng:

- **Versioning**: `/api/v1/` prefix; breaking change → `/api/v2/` giữ v1 tối thiểu 6 tháng + `Sunset` header (RFC 8594).
- **Literal path vs shorthand**: bảng dưới viết tắt `/cheat/...`, nhưng literal config (cookie `Path=`, CORS origin, OpenAPI) **phải** dùng `/api/v1/cheat/...`.
- **Content-Type**: `application/json; charset=utf-8`. Body > 1 MB → `413`.
- **Naming**: path kebab-case, JSON snake_case, UUID lowercase hyphenated, timestamp ISO 8601 UTC with `Z`.
- **Status code**: 200/201/202/204/400/401/403/404/409/410/413/422/429/500/503.
- **Error**: RFC 7807 Problem Details (§XV.1) + `errors[]` field-level cho 422.
- **Idempotency-Key**: bắt buộc cho POST `/cheat/events`, `/cheat/appeals`, `/cheat/review/{id}/decide`, `/cheat/review/{id}/pickup`, `/admin/cheat/weights` PATCH.
- **Rate limit headers**: `X-RateLimit-Limit`, `X-RateLimit-Remaining`, `X-RateLimit-Reset`, `Retry-After` (khi 429).
- **Pagination**: cursor-based `?cursor=&limit=` (max 100, default 20). List endpoints: `/cheat/review/queue`, `/cheat/appeals/my`, `/admin/cheat/stats`.
- **Standard headers**: `X-Request-Id` (echo), `X-Trace-Id` (OTel), `Cache-Control: no-store`, security headers (§XIII.7).
- **No envelope**: resource trả trực tiếp; error theo RFC 7807; list có `{items, page_info}`.

### 11.1 Ingest (fallback khi WS không available)

| Method | Path | Body | Response | Rate limit |
| ------ | ---- | ---- | -------- | ---------- |
| POST | `/cheat/events` | Array of `CheatEventRaw` (max 200, max 1 MB) | `202 {accepted: int, rejected: int}` | 10/min/attempt |

Require: `X-Cheat-HMAC`, `X-Cheat-Attempt-Id`, `Authorization: Bearer`, `Idempotency-Key`.

### 11.2 Proctor / review

| Method | Path | Permission | Sudo |
| ------ | ---- | ---------- | ---- |
| GET | `/cheat/review/queue?status=&severity=&cursor=&limit=` | `cheat.review` | — |
| POST | `/cheat/review/{queue_id}/pickup` | `cheat.review` | — |
| GET | `/cheat/review/{queue_id}` | `cheat.review` (assigned) | — |
| POST | `/cheat/review/{queue_id}/decide` body `{decision, reason}` | `cheat.decide` (assigned) | 🔒 tier A (5 phút) — decide impact student |
| POST | `/cheat/review/{queue_id}/escalate` body `{reason}` | `cheat.review` | — |
| GET | `/cheat/attempts/{attempt_id}/timeline` | `cheat.review` \| `attempt.read.org` | — |
| GET | `/cheat/attempts/{attempt_id}/evidence` | `cheat.video.view` | — |

### 11.3 Student

| Method | Path | Permission |
| ------ | ---- | ---------- |
| GET | `/cheat/appeals/my?cursor=&limit=` | `cheat.appeal.submit` |
| POST | `/cheat/appeals` body `{attempt_id, reason, evidence_urls[]}` | `cheat.appeal.submit` |
| GET | `/cheat/appeals/{id}` | owner \| `cheat.appeal.resolve` |
| GET | `/cheat/attempts/{attempt_id}/events` (**chỉ sau exam completed**) | owner \| `cheat.review` |

### 11.4 Instructor / admin

| Method | Path | Permission | Sudo |
| ------ | ---- | ---------- | ---- |
| POST | `/cheat/appeals/{id}/resolve` body `{decision, reason}` | `cheat.appeal.resolve` (owner exam) | 🔒 tier A |
| GET | `/admin/cheat/weights` | `cheat.config.weights` | — |
| PATCH | `/admin/cheat/weights` body YAML-delta | `cheat.config.weights` (platform_admin) | 🔒 tier B (per-action step-up token — impact system-wide) |
| GET | `/admin/cheat/stats?org_id=&from=&to=&cursor=&limit=` | `analytics.org` | — |
| GET | `/admin/cheat/false-positive-rate?window=30d` | `analytics.org` | — |
| POST | `/admin/cheat/whitelist` body `{org_id, exam_id?, event_type}` | `cheat.config.weights` | 🔒 tier A |

🔒 = sudo mode required (Auth §5.1.1). Tier B cho platform-wide weight PATCH (chống token theft → block-wide FP).

### 11.5 gRPC (internal)

```proto
syntax = "proto3";
package vn.smartquiz.cheat.v1;

service CheatDetectionService {
    rpc GetRiskScore(GetRiskScoreRequest) returns (RiskScoreResponse);
    rpc RecordManualEvent(ManualEventRequest) returns (google.protobuf.Empty);
    rpc RequestSuspendByProctor(RequestSuspendRequest) returns (google.protobuf.Empty);
    rpc GetAttemptTimeline(TimelineRequest) returns (TimelineResponse);
}

message GetRiskScoreRequest { string attempt_id = 1; }
message RiskScoreResponse {
    int32 total_score = 1;
    string severity = 2;
    int64 state_version = 3;
    int64 last_updated_ms = 4;
}

message ManualEventRequest {
    string attempt_id = 1;
    string event_type = 2;    // "manual_proctor_note"
    string proctor_note = 3;
    string proctor_id = 4;
}

message RequestSuspendRequest {
    string attempt_id = 1;
    string proctor_id = 2;
    string reason = 3;
    int64  state_version_seen = 4;   // fencing token, xem §8.4
}
```

**Ranh giới với Exam Service (align §3.5 RBAC):** CDS **không** own quyền `UPDATE exam_attempts`.
`RequestSuspendByProctor` là **HTTP→gRPC adapter** — CDS nhận request, ghi `cheat_events`
(manual proctor note), publish `cheat.alert.generated.v1` qua outbox với
`recommended_action='suspend'` + `state_version_at_detection`. Exam Service consume event +
apply fencing CAS UPDATE (§8.4). Nếu proctor cần response sync "đã suspend chưa", CDS poll
`GetAttempt` gRPC Exam Service cho đến khi `status='suspended'` hoặc timeout 3s (chính sách
retry phía FE quyết định).

Cache `GetRiskScore` 5s Redis cho Exam Service query khi render attempt detail UI.

### 11.6 Endpoint contract template — reference cho OpenAPI

Theo Auth §12.7 pattern. Ví dụ `POST /api/v1/cheat/review/{queue_id}/decide`:

**Request:**

```http
POST /api/v1/cheat/review/abc-123/decide HTTP/2
Content-Type: application/json; charset=utf-8
Authorization: Bearer eyJ...
Idempotency-Key: 7c9e6f8b-2a3d-4e5f-b1a0-1234567890ab
X-Request-Id: req-xyz

{
  "decision": "confirmed",
  "reason": "Student phát hiện phone detected 3 lần + gaze off-screen > 15s",
  "evidence_refs": ["cheat_event:uuid-1", "cheat_event:uuid-2"]
}
```

**Request JSON Schema:**

```yaml
DecideReviewRequest:
  type: object
  required: [decision, reason]
  additionalProperties: false
  properties:
    decision:
      type: string
      enum: [confirmed, dismissed, escalate]
    reason:
      type: string
      minLength: 10
      maxLength: 2000
    evidence_refs:
      type: array
      items: { type: string, pattern: '^cheat_event:[0-9a-f-]{36}$' }
      maxItems: 50
```

**Response 200:**

```yaml
DecideReviewResponse:
  type: object
  required: [queue_id, status, decision, attempt_action_recommended]
  properties:
    queue_id: { type: string, format: uuid }
    status: { type: string, enum: [resolved, escalated] }
    decision: { type: string, enum: [confirmed, dismissed, escalate] }
    attempt_action_recommended:
      type: string
      enum: [terminate, resume, no_action]
      description: "Action được publish qua cheat.review.decided.v1 cho Exam Service"
```

**Error matrix:**

| Status | Code | Khi nào |
| ------ | ---- | ------- |
| 400 | `CHEAT_MALFORMED_REQUEST` | JSON parse fail |
| 403 | `AUTH_FORBIDDEN` | Thiếu `cheat.decide` permission |
| 403 | `AUTH_SUDO_REQUIRED` | Sudo mode expired (§5.1.1 Auth) |
| 404 | `CHEAT_REVIEW_NOT_FOUND` | — |
| 409 | `CHEAT_REVIEW_NOT_IN_STATE` | Đã resolved/escalated |
| 409 | `IDEMPOTENCY_KEY_REUSE_DIFFERENT_BODY` | Idempotency conflict |
| 422 | `CHEAT_VALIDATION_FAILED` | `reason` < 10 ký tự, `decision` không hợp lệ |
| 429 | `CHEAT_RATE_LIMIT` | — |

---

## XII. EVENTS — TRANSACTIONAL OUTBOX + AVRO

> **⚠️ Nguồn truth topic name + schema: `shared-contracts/avro/TOPICS.md`** (catalog tất cả
> topic của repo) và `shared-contracts/avro/cheat/*.avsc` (schema từng event). Bảng ở §12.1
> là **view tóm tắt cho người đọc doc CDS** — khi có lệch, file shared-contracts thắng. PR
> đổi topic **phải cập nhật cả 2 nơi + CLAUDE.md §8** trong cùng commit.

Tuân thủ **ADR-001** và CLAUDE.md §3:

- **Không** gọi `kafkaTemplate.send()` trực tiếp từ UseCase cho event critical. Mọi state change → insert vào bảng `outbox` trong **cùng transaction**.
- Relayer (leader-elected qua `pg_try_advisory_lock`) poll `SELECT ... FOR UPDATE SKIP LOCKED`, publish Kafka, `UPDATE published_at`. Code pattern giống Auth §11.2 `OutboxRelayer` + `CheatOutboxPublisher` (propagation `MANDATORY`).
- Payload encode **Avro**, schema publish lên **Apicurio** với compat mode `BACKWARD`.

### 12.1 Phân loại event

**Critical — BẮT BUỘC qua outbox** (drive state change ở service khác):

| Topic (v1) | Aggregate key | Payload (Avro record) | Consumer |
| ---------- | ------------- | --------------------- | -------- |
| `cheat.alert.generated.v1` | `attempt_id` | `{attempt_id, user_id, exam_id, risk_score, severity, state_version_at_detection, triggered_events[], recommended_action: enum{suspend,terminate}, detected_at}` | **Exam Service** (apply suspend/terminate với state_version fencing), Notification, Analytics |
| `cheat.review.decided.v1` | `attempt_id` | `{queue_id, attempt_id, decision: enum{confirmed,dismissed,escalate}, attempt_action_recommended: enum{terminate,resume,no_action}, reviewed_by, reviewed_at}` | Exam Service (restore/terminate), Notification |
| `cheat.appeal.submitted.v1` | `appeal_id` | `{appeal_id, attempt_id, user_id, reason_hash, evidence_count, submitted_at}` | Notification (giáo viên), Analytics |
| `cheat.appeal.resolved.v1` | `appeal_id` | `{appeal_id, attempt_id, decision: enum{upheld,overturned}, resolved_by, resolved_at}` | Exam Service (restore attempt nếu overturned), Notification, Analytics |
| `cheat.review.sla_missed.v1` | `queue_id` | `{queue_id, attempt_id, severity, minutes_overdue, escalated_to}` | Notification (admin), Analytics |
| `cheat.user.anonymized.v1` | `user_id` | `{user_id, anonymized_at, trigger_event_id}` | Analytics (sync GDPR) |

**Fire-and-forget — KHÔNG qua outbox** (analytics / signal, mất vài event chấp nhận được):

| Topic (v1) | Key | Payload | Consumer |
| ---------- | --- | ------- | -------- |
| `cheat.event.detected.v1` | `attempt_id` | Enriched `CheatEvent` full | ClickHouse (Analytics), BI dashboard |
| `cheat.alert.critical.v1` | `attempt_id` | `{attempt_id, severity, pager_target}` | PagerDuty webhook, Notification |
| `cheat.statistical.result.v1` | `exam_id` | L6 Flink output | Notification (giáo viên), Analytics |

**Lý do tách**: `cheat.event.detected` có thể 10k/s peak — tốn write amp trên `outbox`. Analytics chấp nhận gap; `cheat_events` table trong PG vẫn là truth.

### 12.2 Consumed topics

| Topic | Nguồn | Consumer group | Handler |
| ----- | ----- | -------------- | ------- |
| `cheat.event.raw.v1` | Exam Service (forward từ client WS) | `cheat-detection-raw-consumer` | Main ingestion pipeline (§IX.1) |
| `proctoring.frame.captured.v1` | Client → Media Service → Kafka | `cheat-detection-vision-consumer` | L5 Vision pipeline |
| `exam.attempt.completed.v1` | Exam Service | `cheat-detection-attempt-consumer` (+ `cheat-l6-flink-consumer` cho Flink) | Trigger L6 + finalize proctoring session |
| `exam.answer.submitted.v1` | Exam Service | `cheat-detection-answer-consumer` | Feed L4 answer pattern |
| `auth.role.changed.v1` | Auth Service | `cheat-detection-auth-consumer` | Invalidate permission cache proctor |
| `auth.user.deleted.v1` | Auth Service | `cheat-detection-gdpr-consumer` | GDPR anonymize cheat_events.user_id |

### 12.3 Code pattern — outbox publisher (Cheat)

Giống Auth §11.2. Highlights:

```java
@Service
@Transactional(propagation = Propagation.MANDATORY)   // ❗ fail-fast nếu caller không có TX
class CheatOutboxPublisher {
    private final OutboxRepository repo;
    private final ObjectMapper jsonMapper;

    public void publishAlertGenerated(AlertGeneratedPayload payload) {
        publish("cheat.alert.generated.v1", "alert_generated",
                "exam_attempt", payload.attemptId().toString(),
                payload, payload.attemptId().toString());
    }

    public void publishReviewDecided(ReviewDecidedPayload payload) { ... }
    public void publishAppealResolved(AppealResolvedPayload payload) { ... }

    private void publish(String topic, String eventType, String aggregateType,
                         String aggregateId, Object payload, String partitionKey) {
        UUID eventId = UUID.randomUUID();
        OutboxRow row = new OutboxRow(
            eventId, aggregateType, aggregateId, topic, eventType,
            jsonMapper.valueToTree(payload),
            Map.of("trace_id", MDC.get("trace_id"), "schema_version", "1"),
            partitionKey);
        repo.save(row);
    }
}

// UseCase — BẮT BUỘC @Transactional
@Service
class DispatchAlertUseCase {
    private final CheatOutboxPublisher outbox;                   // MANDATORY TX
    private final AlertCooldownRepository alertCooldownRepo;
    private final CriticalPagerPublisher criticalPager;          // fire-and-forget (outside TX)

    @Transactional
    public void execute(UUID attemptId, RiskEvaluation eval) {
        var payload = AlertGeneratedPayload.from(eval);
        outbox.publishAlertGenerated(payload);                    // critical — ADR-001
        // cooldown ghi cùng TX để tránh dispatch lặp
        alertCooldownRepo.setCooldown(attemptId, eval.severity(), Duration.ofMinutes(2));
    }

    // Gọi SAU khi TX execute() đã commit — dùng ApplicationEventPublisher + @TransactionalEventListener(AFTER_COMMIT)
    // để không block TX và không dispatch nếu rollback.
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAlertCommitted(AlertCommittedEvent ev) {
        if (ev.severity() == Severity.CRITICAL) {
            // Fire-and-forget Kafka publish TRỰC TIẾP (không qua outbox) — align §8.3 dispatch table.
            // Pager là best-effort: nếu mất 1-2 event không tác động state Exam Service
            // (state change đã đi qua cheat.alert.generated.v1 outbox). ADR-001 cho phép.
            criticalPager.fireAndForget(CriticalAlertPayload.from(ev));
        }
    }
}
```

### 12.4 Avro schema convention

- File: `shared-contracts/avro/cheat/cheat.alert.generated.v1.avsc`
- Namespace: `vn.smartquiz.cheat.v1`
- Rule BACKWARD: chỉ add field với `default`, không remove/rename/đổi type.
- Breaking → `.v2` topic, dual-write 30 ngày rồi cut over.
- CI gate: `./gradlew :shared-contracts:avroCompatCheck`.

### 12.5 Producer/Relayer config

| Setting | Value | Lý do |
| ------- | ----- | ----- |
| `acks` | `all` | Đợi ISR — không mất event |
| `enable.idempotence` | `true` | Chống duplicate do retry |
| `max.in.flight.requests.per.connection` | `5` | Cùng idempotence giữ order per partition |
| `compression.type` | `zstd` | Giảm network cost |
| `delivery.timeout.ms` | `30000` | Broker slow → fail fast |
| `request.timeout.ms` | `5000` | — |
| Poll interval relayer | `100ms` fixedDelay | RPO target 5s |
| Batch budget relayer | `3000ms` wall-clock | Leave rest cho poll tiếp |
| Batch size relayer | `500 rows / poll` max | — |
| `spring.lifecycle.timeout-per-shutdown-phase` | `30s` | SIGTERM → drain + flush |

### 12.6 Metric outbox bắt buộc

| Metric | Alert |
| ------ | ----- |
| `cheat_outbox_pending_size` (gauge) | warning > 1k, critical > 10k |
| `cheat_outbox_publish_lag_seconds` (histogram) | p99 > 5s = critical (vi phạm RPO) |
| `cheat_outbox_publish_failed_total{reason}` (counter) | spike > 10/min → page |

---

## XIII. SECURITY HARDENING

### 13.1 Event authenticity — HMAC signature

Mỗi event client → Exam Service WS được **Exam Service HMAC-sign** với secret shared Auth Service cấp lúc `attempt.start`:

```
attempt_secret = HKDF(master_key, salt=attempt_id, info="cheat_hmac_v1", length=32)
signature     = HMAC-SHA256(attempt_secret, canonical_json(event))
header         X-Cheat-HMAC-Kid: <master_key_id>   // "2026-04-key0" — CDS dùng để chọn key verify
```

- `master_key` ở Vault `transit/cheat_hmac_master`, rotate 30 ngày.
- CDS verify khi consume `cheat.event.raw.v1` — fail → DLQ + `cheat_hmac_failures_total` metric; spike > 100/min → page (possible attack).
- `client_ts` vs `server_ts` skew > 5 phút → discard + flag attempt event `suspicious_clock_skew`.

**Rotation semantic — tránh break attempt đang active:**

`attempt_secret` được **derive 1 lần duy nhất** lúc `attempt.start` từ master_key active tại
thời điểm đó. Exam Service **cache** secret trong bộ nhớ trong (Redis `attempt:hmac:{id}` TTL
= `duration + 1h`) suốt attempt — không re-derive kể cả khi master_key rotate giữa chừng. Lý
do: attempt kéo dài 90-180 phút, rotation 30 ngày có thể rơi vào giữa — nếu re-derive thì
signature từ client (đã compute với secret cũ) sẽ fail verify.

CDS verify dùng **dual-key verify** trong grace window (30 ngày sau rotation):

```java
// HmacVerifier
public boolean verify(byte[] payload, byte[] signature, UUID attemptId, String kidHint) {
    // Ưu tiên kid từ header → giảm compute (chỉ verify 1 key)
    if (kidHint != null) {
        byte[] secret = deriveSecret(loadMasterByKid(kidHint), attemptId);
        return constantTimeEquals(hmac(secret, payload), signature);
    }
    // Fallback: thử cả active + previous trong grace window
    for (MasterKey key : List.of(activeMaster, previousMaster)) {
        if (key == null) continue;
        byte[] secret = deriveSecret(key, attemptId);
        if (constantTimeEquals(hmac(secret, payload), signature)) {
            metrics.hmacVerifiedBy.increment(key.kid());
            return true;
        }
    }
    return false;
}
```

Rotation playbook:
1. `T0`: Vault rotate → generate `key_new`, `key_old` chuyển trạng thái `previous`, TTL 30 ngày.
2. Exam Service reload master key list (Vault agent sidecar auto-refresh).
3. Attempts started **trước** T0 tiếp tục ký bằng `key_old` (cached secret); attempts mới ký bằng `key_new`.
4. CDS verify thử cả 2 master trong 30 ngày → cover mọi attempt active.
5. `T0 + 30d`: `key_old` drop khỏi master list; attempts quá hạn 30 ngày đã expire hết (max attempt duration + grace << 30 ngày).

### 13.2 Rate limit (Redis sliding window, giống Auth §9.1)

| Endpoint | Key | Giới hạn |
| -------- | --- | -------- |
| POST `/cheat/events` | `rate:cheat_ingest:{attempt_id}` | 100/s/attempt (legitimate max) |
| POST `/cheat/appeals` | `rate:cheat_appeal:{user_id}` | 5/day/user |
| POST `/cheat/review/{id}/pickup` | `rate:cheat_pickup:{proctor_id}` | 30/min |
| GET `/admin/cheat/stats` | `rate:cheat_stats:{user_id}` | 60/min |

### 13.3 Data privacy

- **Video S3**: bucket `proctoring-video` SSE-KMS, IAM policy chỉ CDS pod role + proctor view role (STS AssumeRole temporary 15 phút presigned URL).
- **Keystroke raw**: **KHÔNG LƯU** — chỉ derived feature aggregate.
- **IP raw**: chỉ ở PG `cheat_events.event_data`; log Loki mask `x.x.x.***`.
- **Anonymization**: consume `auth.user.deleted.v1` → set `cheat_events.user_id = NULL`, strip PII từ `event_data`, video S3 delete (sync với Notification GDPR confirm mail).

### 13.4 CORS & CSRF

- CORS: whitelist origin ConfigMap (`web.smartquiz.vn`, `admin.smartquiz.vn`).
- CSRF: stateless JWT, disable filter (same pattern Auth §13.3).

### 13.5 TLS & mTLS

- TLS 1.3 ở gateway.
- mTLS service-to-service qua Istio.
- Triton gRPC: mTLS client cert từ Vault (rotate 30 ngày).

### 13.6 Secrets rotation

| Secret | Tần suất | Cơ chế |
| ------ | -------- | ------ |
| HMAC master key | 30 ngày | Vault Transit rotate; grace period 30 ngày (dual-key verify) |
| IPQualityScore API key | 90 ngày | Manual |
| Triton client cert | 30 ngày | Vault PKI auto-rotate |
| DB password | 30 ngày | Vault Dynamic Secrets (Postgres plugin) |

### 13.7 Security headers (mặc định)

```
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Referrer-Policy: strict-origin-when-cross-origin
Permissions-Policy: geolocation=(), camera=()
Strict-Transport-Security: max-age=31536000; includeSubDomains; preload
```

### 13.8 Access control matrix (permission-based)

| Resource | student | proctor | instructor (own exam) | admin | platform_admin |
| -------- | ------- | ------- | --------------------- | ----- | -------------- |
| Xem risk_score của mình trong thi | ✖ | — | — | — | — |
| Xem events của attempt mình **sau thi** | ✔ | — | — | — | — |
| Xem review queue org | — | ✔ | ✔ | ✔ | ✔ |
| Review + decide | — | ✔ | — | ✔ | ✔ |
| Appeal submit | ✔ (own) | — | — | — | — |
| Appeal resolve | — | — | ✔ (own exam) | ✔ | ✔ |
| Config weights PATCH | — | — | — | — | ✔ (🔒 tier B) |
| View video | — | ✔ | ✔ (own exam) | ✔ | ✔ |

---

## XIV. OBSERVABILITY

Tuân thủ stack CLAUDE.md §2: **Micrometer → Prometheus**, **OpenTelemetry OTLP**, **Loki**.

### 14.1 Metrics (Prometheus, `/actuator/prometheus`)

| Metric | Type | Label |
| ------ | ---- | ----- |
| `cheat_events_ingested_total` | counter | `event_type`, `layer`, `source=kafka\|http` |
| `cheat_events_processing_duration_seconds` | histogram | `layer` |
| `cheat_hmac_failures_total` | counter | `reason=sig_mismatch\|skew\|no_secret` |
| `cheat_risk_score_distribution` | histogram | — |
| `cheat_alerts_published_total` | counter | `severity` |
| `cheat_review_queue_size` | gauge | `severity`, `status` |
| `cheat_review_decision_total` | counter | `decision` |
| `cheat_appeal_submitted_total` | counter | — |
| `cheat_appeal_resolved_total` | counter | `decision` |
| `cheat_false_positive_rate` | gauge | rolling 30d từ appeal overturned |
| `cheat_vision_inference_duration_seconds` | histogram | `model` |
| `cheat_vision_preproc_queue_depth` | gauge | — |
| `cheat_vision_frames_dropped_total` | counter | `reason=queue_full\|triton_timeout` |
| `cheat_vision_gpu_utilization` | gauge | `gpu_id` |
| `cheat_flink_job_lag_seconds` | gauge | — |
| `cheat_kafka_consumer_lag` | gauge | `topic`, `consumer_group` |
| `cheat_outbox_pending_size` | gauge | — |
| `cheat_outbox_publish_lag_seconds` | histogram | — |

### 14.2 Tracing (OpenTelemetry)

- Instrument qua `io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter`.
- Export OTLP gRPC → `otel-collector:4317`.
- Span attributes bắt buộc: `cheat.attempt_id`, `cheat.user_id`, `cheat.event_type`, `cheat.layer`, `cheat.severity`.
- **Cấm** set raw IP, video S3 key, keystroke data làm attribute.
- Trace propagation: header `traceparent` (W3C).

### 14.3 SLO

| SLI | Target | Ghi chú |
| --- | ------ | ------- |
| Event ingest → risk updated p99 | < 500ms | Vi phạm = page |
| Alert → Exam Service consume p99 | < 5s | ADR-001 RPO |
| Review decide endpoint p99 | < 300ms | — |
| Availability | **99.9%** (ADR-001 §1) | Fail-open — exam không block |
| False positive rate (rolling 30d) | **< 0.5%** | Regression → CRITICAL alert |
| False negative (L6 post-exam catches) | < 5% | Red-team dataset |
| Proctor SLA compliance | > 95% | critical ≤ 1m, high ≤ 2m |

### 14.4 Logs — structured JSON + MDC

Logback `net.logstash.logback:logstash-logback-encoder`. Filter `MdcFilter` set: `trace_id`, `span_id`, `attempt_id`, `user_id`, `org_id`, `event_layer`, `request_id`.

```json
{
  "ts": "2026-04-22T10:05:22.123Z",
  "level": "INFO",
  "service": "cheat-detection",
  "trace_id": "7c9e6f8b2a3d4e5f...",
  "span_id": "1a2b3c4d...",
  "request_id": "req-abc123",
  "attempt_id": "att-xxx",
  "user_id": "usr-yyy",
  "org_id": "org-zzz",
  "event_layer": 3,
  "event": "risk.threshold.crossed",
  "severity": "high",
  "risk_score": 62,
  "client_ip": "1.2.3.***"
}
```

**Masking filter bắt buộc** (`MaskingPatternLayout`):
- IP raw → `x.x.x.***` (giữ /24 cho debug geo cluster).
- Video S3 key → `proctoring-video/***`.
- Keystroke raw data → `***REDACTED***`.
- HMAC signature header → `***REDACTED***`.
- Email → `h***@hust.edu.vn`.

Ship qua promtail → Loki (retention 14 ngày). Index theo `service`, `level`, `event_layer`, `severity`.

### 14.5 Alerts

| Alert | Điều kiện | Severity |
| ----- | --------- | -------- |
| `CheatServiceDown` | up == 0 trong 2 phút | critical |
| `CheatConsumerLagHigh` | lag > 10k messages trong 2 phút | warning |
| `CheatProcessingLatencyHigh` | p99 > 1s trong 5 phút | warning |
| `CheatAlertDispatchLag` | `cheat_outbox_publish_lag_seconds` p99 > 5s | critical (RPO violation) |
| `FalsePositiveRateSpike` | `cheat_false_positive_rate` > 1% rolling 7d | critical (regression — weight PATCH cần rollback) |
| `VisionGpuUnavailable` | Triton pod unhealthy > 2 phút | critical |
| `VisionQueueOverflow` | `cheat_vision_preproc_queue_depth` > 200 trong 1 phút | warning |
| `ReviewQueueBacklog` | pending > 20 quá 10 phút | warning |
| `CriticalAlertUnactioned` | critical queue item > 2 phút chưa pickup | critical (page admin) |
| `HmacFailureSpike` | `cheat_hmac_failures_total` > 100/min | warning (possible attack) |
| `OutboxBacklog` | `cheat_outbox_pending_size` > 10k | critical |
| `SLOBurnRate` | Error budget tiêu > 2%/hour | critical |

### 14.6 Dashboards

1. **Operational** — events/sec, consumer lag, processing latency
2. **Risk distribution** — histogram risk_score + threshold bands qua thời gian
3. **Layer efficacy** — contribution của từng layer vào decisions
4. **Review workflow** — queue size, SLA compliance, decision distribution
5. **False positive watch** — appeals overturned/confirmed rate rolling 30d
6. **Vision GPU** — GPU util, batch size, Triton latency, frames dropped

---

## XV. ERROR HANDLING

### 15.1 Format lỗi chuẩn (RFC 7807 Problem Details)

Giống Auth §15.1. Ví dụ:

```json
{
  "type": "https://smartquiz.vn/errors/review-not-in-state",
  "title": "Review đã được xử lý",
  "status": 409,
  "code": "CHEAT_REVIEW_NOT_IN_STATE",
  "trace_id": "abc123",
  "timestamp": "2026-04-22T10:05:22Z"
}
```

Cho 422: body có `errors[]` field-level.

### 15.2 Bảng mã lỗi

| Code | HTTP | Ý nghĩa |
| ---- | ---- | ------- |
| `CHEAT_MALFORMED_REQUEST` | 400 | JSON parse fail, header missing |
| `CHEAT_VALIDATION_FAILED` | 422 | Semantic validation (reason < 10 ký tự, decision enum invalid) |
| `CHEAT_EVENT_INVALID` | 422 | Event schema validation fail |
| `CHEAT_EVENT_SIGNATURE_INVALID` | 401 | HMAC mismatch |
| `CHEAT_EVENT_CLOCK_SKEW` | 422 | `client_ts` vs `server_ts` skew > 5 phút |
| `CHEAT_RATE_LIMIT` | 429 | Header `Retry-After`, `X-RateLimit-*`; body kèm `retry_after, limit, window` |
| `IDEMPOTENCY_KEY_REUSE_DIFFERENT_BODY` | 409 | — |
| `CHEAT_REVIEW_NOT_FOUND` | 404 | — |
| `CHEAT_REVIEW_ALREADY_ASSIGNED` | 409 | Pickup race — proctor khác đã assign |
| `CHEAT_REVIEW_NOT_IN_STATE` | 409 | Decide 1 review đã resolved/escalated |
| `CHEAT_APPEAL_NOT_FOUND` | 404 | — |
| `CHEAT_APPEAL_WINDOW_CLOSED` | 410 | > 30 ngày sau `completed_at` |
| `CHEAT_APPEAL_DUPLICATE` | 409 | User đã submit appeal cho attempt này, status != resolved |
| `CHEAT_CONFIG_VALIDATION` | 422 | YAML weight PATCH không hợp lệ (thiếu field, số âm) |
| `CHEAT_ATTEMPT_NOT_FOUND` | 404 | `attempt_id` không tồn tại (gRPC Exam return NOT_FOUND) |
| `CHEAT_PROCTOR_NOT_ASSIGNED` | 403 | Proctor decide review không phải của mình, không có override permission |
| `AUTH_SUDO_REQUIRED` | 403 | Sudo tier A required (giống Auth §15.2) |
| `AUTH_STEPUP_REQUIRED` | 400 | Sudo tier B required (weight PATCH) |
| `AUTH_FORBIDDEN` | 403 | Thiếu permission |
| `CHEAT_INTERNAL` | 500 | Log trace_id, không leak stack |
| `CHEAT_UPSTREAM_UNAVAILABLE` | 503 | Triton/IPQS circuit breaker open, Kafka down — `Retry-After` header |

---

## XVI. TESTING STRATEGY

### 16.1 Pyramid + coverage gate (JaCoCo)

```
          E2E (10%)       ← full flow: event → alert → review → decide
       Integration (30%)  ← Testcontainers: PG + Redis + Kafka + Apicurio + WireMock Triton
   Unit tests (60%)       ← domain logic, risk scoring, threshold, decay, cooldown
```

| Layer | JaCoCo gate (line coverage) |
| ----- | --------------------------- |
| `domain/*` | ≥ **80%** |
| `application/*` | ≥ **70%** |
| `scoring/*` | ≥ **85%** (critical path) |
| `infrastructure/*` | best-effort (integration test phủ) |
| Global | ≥ **75%** |

CI fail nếu coverage regress > 2% so với baseline branch `main`.

### 16.2 Test tools

| Layer | Tool |
| ----- | ---- |
| Unit | JUnit 5, AssertJ, Mockito |
| Integration | **Testcontainers** (PG 16, Redis 7, Confluent Kafka, Apicurio), **WireMock** (Triton, IPQualityScore, MaxMind URL stub) |
| Contract | Spring Cloud Contract (producer-side) + Avro compat check Apicurio |
| Security | OWASP ZAP baseline CI; `@security-engineer` review trước merge |
| Load | k6 — target 10k events/s ingest, 1k concurrent review queue query |
| ML eval | Python pytest với dataset honest/cheat |

### 16.3 Integration test bắt buộc — outbox + Kafka (ADR-001 §impl.5)

```java
@SpringBootTest
@Testcontainers
class AlertGeneratedOutboxIntegrationTest {
    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
    @Container static KafkaContainer kafka = new KafkaContainer(...);
    @Container static GenericContainer<?> apicurio = ...;

    @Test
    void high_risk_event_deduplicates_alert_through_outbox_even_if_relayer_crashes() {
        // given: attempt in progress với risk score = 58 (medium)
        seedAttemptContext(attemptId, 58);

        // when: inject devtools_open event → score +15 = 73 → crosses HIGH threshold
        ingestEvent(attemptId, EventType.DEVTOOLS_OPEN);

        // crash relayer giữa chừng
        relayerTestHook.pauseBefore(PublishStage.KAFKA_SEND);
        Thread.sleep(500);
        relayerTestHook.resume();

        // then: event xuất hiện đúng 1 lần trên Kafka
        await().atMost(10, SECONDS).untilAsserted(() -> {
            var records = kafkaConsumer.poll("cheat.alert.generated.v1");
            assertThat(records).hasSize(1);
            var payload = (AlertGeneratedPayload) records.get(0).value();
            assertThat(payload.attemptId()).isEqualTo(attemptId);
            assertThat(payload.severity()).isEqualTo("high");
            assertThat(payload.recommendedAction()).isEqualTo("suspend");
        });
        assertThat(outboxRepo.pendingCount()).isZero();
    }
}
```

### 16.4 False positive regression gate

Dataset `test/resources/fp-regression/`:
- `1000-honest-attempts.jsonl` — replay events của 1000 attempt đã verified honest (post-graduation audit)
- `100-known-cheat-attempts.jsonl` — 100 attempt đã confirmed cheat

CI gate:
- **Honest → flagged ratio** < 0.5% (vi phạm = fail build)
- **Cheat → caught ratio** ≥ 90%

Chạy mỗi PR touches `scoring/`, `pipeline/`, `config/weights.yaml`.

### 16.5 Security test cases bắt buộc

- [ ] HMAC signature forgery (wrong key → 401)
- [ ] Clock skew attack (`client_ts` far future → discard)
- [ ] Event replay (cùng event_id 2 lần → dedup qua `processed_events`)
- [ ] Rate limit bypass via distributed IP
- [ ] Review decide race (2 proctor cùng pickup → chỉ 1 thắng)
- [ ] Appeal after window (31 ngày → 410)
- [ ] Permission bypass (student POST decide → 403)
- [ ] Outbox poisoning: payload malformed → relayer mark last_error, không block queue
- [ ] Consumer idempotency: re-deliver cùng event 3 lần → `processed_events` dedup OK
- [ ] L5 Triton timeout → circuit breaker open, fallback không L5 events, không crash pipeline
- [ ] Weight PATCH với số âm → 422
- [ ] Weight PATCH bypass sudo tier B → 400 `AUTH_STEPUP_REQUIRED`

### 16.6 Load test (k6)

- Scenario A: 10k events/s ingest sustained 5 phút, 1000 attempt concurrent. Gate: p99 < 500ms, 0 event loss.
- Scenario B: 1000 concurrent proctor GET review queue. Gate: p99 < 300ms, < 0.1% 5xx.
- Scenario C: Exam end burst — 5000 attempt `completed` trong 1 phút → L6 Flink không lag > 30s.

---

## XVII. DEPLOYMENT & INFRASTRUCTURE

### 17.1 Kubernetes manifest (tóm tắt)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: cheat-detection, namespace: smartquiz }
spec:
  replicas: 3
  strategy: { type: RollingUpdate, rollingUpdate: { maxSurge: 1, maxUnavailable: 0 } }
  template:
    spec:
      containers:
        - name: cheat
          image: registry.smartquiz.vn/cheat-detection:2.0.0
          ports:
            - { name: http, containerPort: 3005 }
            - { name: grpc, containerPort: 4005 }
            - { name: mgmt, containerPort: 9005 }
          env:
            - { name: SPRING_PROFILES_ACTIVE, value: prod }
            - { name: VAULT_ADDR, value: "https://vault:8200" }
            - { name: TRITON_URL, value: "grpc://triton:8001" }
            - { name: MAXMIND_DB_PATH, value: /var/geoip/GeoLite2-City.mmdb }
          envFrom:
            - configMapRef: { name: cheat-config }
            - secretRef:    { name: cheat-secrets }
          resources:
            requests: { cpu: 1,  memory: 1Gi }
            limits:   { cpu: 4,  memory: 4Gi }
          volumeMounts:
            - { mountPath: /var/geoip, name: geoip-data, readOnly: true }
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: mgmt }
            initialDelaySeconds: 30
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: mgmt }
            periodSeconds: 5
          startupProbe:
            httpGet: { path: /actuator/health/readiness, port: mgmt }
            failureThreshold: 30
      volumes:
        - name: geoip-data
          persistentVolumeClaim: { claimName: geoip-pvc }

---
# L5 Vision worker — GPU node
apiVersion: apps/v1
kind: Deployment
metadata: { name: cheat-vision-gpu, namespace: smartquiz }
spec:
  replicas: 1  # scale on demand qua HPA queue-depth
  template:
    spec:
      nodeSelector: { "nvidia.com/gpu.present": "true" }
      tolerations: [{ key: "nvidia.com/gpu", operator: "Exists" }]
      containers:
        - name: vision-worker
          image: registry.smartquiz.vn/cheat-vision:2.0.0
          resources:
            limits: { "nvidia.com/gpu": 1, memory: 8Gi, cpu: 4 }

---
# L6 Flink job cluster
apiVersion: flink.apache.org/v1beta1
kind: FlinkDeployment
metadata: { name: cheat-statistical-flink, namespace: smartquiz }
spec:
  image: flink:1.18
  flinkConfiguration:
    taskmanager.numberOfTaskSlots: "4"
    state.backend: rocksdb
    state.checkpoints.dir: s3://flink-checkpoints/cheat-l6/
  taskManager: { replicas: 2 }
  job:
    jarURI: s3://artifacts/cheat-l6-flink-job-2.0.0.jar
    entryClass: vn.smartquiz.cheat.l6.StatisticalCorrelationJob
```

+ `HorizontalPodAutoscaler` (main service) theo `cheat_kafka_consumer_lag` + CPU
+ `PodDisruptionBudget` minAvailable=2 (main), minAvailable=1 (vision-gpu)
+ `NetworkPolicy` — ingress từ API Gateway + Exam Service gRPC; egress đến PG, Redis, Kafka, Triton, Vault, MaxMind (scheduled update), IPQualityScore, ClickHouse (ADR-002 read-only Analytics DB)

### 17.2 HPA main

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: { name: cheat-detection-hpa }
spec:
  minReplicas: 3
  maxReplicas: 20
  metrics:
    - type: Pods
      pods:
        metric: { name: cheat_kafka_consumer_lag }
        target: { type: AverageValue, averageValue: "1000" }
    - type: Resource
      resource: { name: cpu, target: { type: Utilization, averageUtilization: 70 } }
```

### 17.3 HPA vision — theo queue depth

```yaml
metrics:
  - type: Pods
    pods:
      metric: { name: cheat_vision_preproc_queue_depth }
      target: { type: AverageValue, averageValue: "50" }
```

### 17.4 Cấu hình môi trường

| Key | Prod | Dev |
| --- | ---- | --- |
| `DB_URL` | Vault secret | `jdbc:postgresql://localhost:5432/smartquiz` |
| `REDIS_URL` | `redis://redis:6379/0` | `redis://localhost:6379/0` |
| `KAFKA_BROKERS` | `kafka-0:9092,kafka-1:9092,kafka-2:9092` | `localhost:9092` |
| `TRITON_URL` | `grpc://triton:8001` | `grpc://localhost:8001` (hoặc disabled → L5 off) |
| `CLICKHOUSE_URL` | `jdbc:clickhouse://ch:8123/cheat_analytics` | local |
| `HMAC_MASTER_VAULT_PATH` | `transit/cheat_hmac_master` | env `HMAC_MASTER` |
| `IPQS_API_KEY_VAULT_PATH` | `kv/cheat/ipqs` | env |
| `MAXMIND_DB_PATH` | `/var/geoip/GeoLite2-City.mmdb` | same (mount local) |

### 17.5 Scaling & thread model

- Stateless (hot state Redis + durable PG) → scale horizontal.
- **I/O path** (Kafka poll, Redis, JDBC, gRPC): Virtual Threads.
- **Vision pre-processing** (CPU-bound): bounded `ThreadPoolTaskExecutor` (§VII.5).
- Peak load: 10k events/s → ~3-5 pod main (10k/pod comfortable). Exam rush: scale 20.
- L5 GPU: 1 warm pod, scale theo queue depth; peak enterprise exam 10 pod.

### 17.6 GeoIP DB update

CronJob mỗi tuần:
```yaml
apiVersion: batch/v1
kind: CronJob
metadata: { name: geoip-update }
spec:
  schedule: "0 3 * * 0"  # Sunday 3am UTC
  jobTemplate:
    spec:
      template:
        spec:
          containers:
            - name: updater
              image: ghcr.io/maxmind/geoipupdate:latest
              env: [ { name: GEOIPUPDATE_ACCOUNT_ID, valueFrom: {...} } ]
              volumeMounts: [ { mountPath: /usr/share/GeoIP, name: geoip-data } ]
          volumes:
            - name: geoip-data
              persistentVolumeClaim: { claimName: geoip-pvc }
          restartPolicy: OnFailure
```

Sau update → rolling restart main deployment để reload mmdb.

### 17.7 Disaster recovery

| Scenario | Impact | RPO | RTO | Mitigation |
| -------- | ------ | --- | --- | ---------- |
| CDS hoàn toàn down | Exam chạy, không phát hiện | — | < 15 phút | Events buffer Kafka 30 ngày → replay khi up; Exam Service fail-open |
| Triton GPU down (L5) | L5 off, L1-L4 ok | — | < 30 phút | Circuit breaker; fallback: proctor manual review video sau exam |
| Flink L6 cluster down | Post-exam analysis trễ | — | < 2h | Retry batch; không critical trong kỳ thi |
| False positive bão (weight PATCH bug) | Student suspend oan | — | < 5 phút | Emergency: PATCH weights về baseline; rollback deployment; `FalsePositiveRateSpike` alert |
| Redis down | Mất hot state | < 1 phút | < 5 phút | Rebuild từ Kafka replay + `cheat_events` PG |
| Postgres down | Mất audit write temp | < 1 phút | < 15 phút | Patroni + PITR; buffered writer retry; Kafka replay cover window |
| HMAC key rotate fail | Mọi event 401 | — | < 30 phút | Dual-key grace 30 ngày; rollback Vault |

---

## XVIII. ROADMAP & OPEN QUESTIONS

### 18.1 Gate trước khi scaffold (CLAUDE.md §9)

**Phải xong trước khi bắt tay code UseCase đầu tiên:**

- [x] Schema PostgreSQL (`database/postgresql/schema.sql` §8, §12, §13)
- [x] `cheat_event_type` ENUM base 26 event (schema.sql:50-58) — **cần extend 9 event mới** ở delta §18.1 item 5
- [x] ADR-001 (SLA + RPO + outbox)
- [x] ADR-002 (Analytics vs Cheating split)
- [x] **`exam_attempts.state_version BIGINT`** — đã có ở `schema.sql:343` (fencing token, COMMENT line 366); KHÔNG phải prereq riêng cho CDS
- [ ] **OpenAPI 3.1 spec** (`services/cheating-detection/app/src/main/resources/static/openapi.yaml`) — đủ endpoint MVP §18.2, reviewed trước khi Proctor UI code
- [ ] Avro schema MVP trong `shared-contracts/avro/cheat/`: `cheat.event.detected.v1`, `cheat.alert.generated.v1`, `cheat.review.decided.v1`, `cheat.appeal.submitted.v1`, `cheat.appeal.resolved.v1`
- [ ] Register Avro schema lên Apicurio dev instance (BACKWARD compat)
- [ ] `shared-outbox-starter` Gradle plugin stub (ADR-001 §consequences) — dùng chung với Auth/Exam
- [ ] `ops/gen-hmac-master.sh` — sinh HMAC master dev local + Vault bootstrap cho prod
- [ ] **Schema delta v2.1** — apply block bên dưới vào `schema.sql` master

#### Schema delta v2.1 — CẦN merge vào `database/postgresql/schema.sql`

> Đây là **single source** cho mọi schema change thuộc CDS v2.0/v2.1. Khi có thêm thay đổi,
> APPEND vào block này, đừng rải rác ở các section khác của doc.

1. **`cheat_events`** — partition theo tháng (§8 schema.sql:405-429):
   - Convert từ regular table → `PARTITION BY RANGE (server_timestamp)`.
   - Tạo partition `y2026m04 → y2027m04` upfront.
   - Cron monthly tạo partition mới; quá 24 tháng detach + S3 Glacier dump.
   - Flyway delta: `V{epoch}__cheat_events_partition.sql`.

   > **⚠️ PK + FK cascade impact (coordinate downtime):** PostgreSQL yêu cầu PK của
   > partitioned table **phải chứa partition key** — tức PK hiện tại `(id)` phải đổi thành
   > `(id, server_timestamp)`. Thay đổi này cascade:
   > - FK `cheat_review_queue.triggered_by_event REFERENCES cheat_events(id)` (schema.sql:640)
   >   không còn valid → phải `DROP CONSTRAINT ... ADD CONSTRAINT` với composite reference
   >   hoặc đổi sang NO-FK (soft reference) + check application-level.
   > - Migration steps:
   >   1. Lock `cheat_events` ngắn → tạo partitioned table mới `cheat_events_new`.
   >   2. `INSERT INTO cheat_events_new SELECT ...` (batch).
   >   3. `DROP TABLE cheat_events CASCADE` → drop FK `cheat_review_queue.triggered_by_event`.
   >   4. `ALTER TABLE cheat_events_new RENAME TO cheat_events`.
   >   5. Recreate FK với composite: `ALTER TABLE cheat_review_queue ADD COLUMN triggered_by_event_ts TIMESTAMPTZ` + composite FK; HOẶC chọn soft reference.
   > - **Downtime estimate**: ~5-15 phút cho 10M row.
   > - Pattern tương tự đã được document ở `schema.sql:321-330` cho `exam_attempts` — tham khảo plan ở đó.

2. **`cheat_review_queue`** — partition theo tháng khi > 10M row (Phase 2):
   - MVP: giữ regular table.
   - Phase 2: migrate partition theo `created_at`.

3. **`proctoring_sessions`** — thêm `finalized_video_s3_key VARCHAR(500)` (§7.5 — video concat từ frame):
   - Flyway delta: `V{epoch}__cheat_proctoring_finalized_video.sql`.

4. **`cheat_weight_config_history`** — new (audit trail cho `/admin/cheat/weights` PATCH, §11.4):
   ```sql
   CREATE TABLE cheat_weight_config_history (
       id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
       changed_by      UUID NOT NULL REFERENCES users(id),
       changed_at      TIMESTAMPTZ DEFAULT NOW(),
       previous_yaml   TEXT NOT NULL,
       new_yaml        TEXT NOT NULL,
       reason          TEXT,
       rollback_of_id  UUID REFERENCES cheat_weight_config_history(id)
   );
   CREATE INDEX idx_weight_history_time ON cheat_weight_config_history(changed_at DESC);
   ```

5. **Extend ENUM `cheat_event_type`** — hoà giải doc ↔ DDL (§3.4 weights.yaml đã reference):
   ```sql
   -- PostgreSQL cho phép ADD VALUE vào ENUM, không xoá được (yêu cầu rebuild type)
   ALTER TYPE cheat_event_type ADD VALUE IF NOT EXISTS 'extension_detected';
   ALTER TYPE cheat_event_type ADD VALUE IF NOT EXISTS 'emulator_detected';
   ALTER TYPE cheat_event_type ADD VALUE IF NOT EXISTS 'headless_browser';
   ALTER TYPE cheat_event_type ADD VALUE IF NOT EXISTS 'proxy_detected';
   ALTER TYPE cheat_event_type ADD VALUE IF NOT EXISTS 'high_latency_jump';
   ALTER TYPE cheat_event_type ADD VALUE IF NOT EXISTS 'wrong_answer_match';
   ALTER TYPE cheat_event_type ADD VALUE IF NOT EXISTS 'submission_time_cluster';
   ALTER TYPE cheat_event_type ADD VALUE IF NOT EXISTS 'answer_change_pattern';
   ALTER TYPE cheat_event_type ADD VALUE IF NOT EXISTS 'manual_proctor_note';
   ALTER TYPE cheat_event_type ADD VALUE IF NOT EXISTS 'suspicious_clock_skew';
   ```
   - Flyway delta: `V{epoch}__cheat_event_type_extend.sql`.
   - **Constraint:** `ALTER TYPE ... ADD VALUE` không chạy được trong transaction block → migration phải đánh dấu `executeInTransaction=false` (Flyway `-- transactional: false` directive).
   - CI gate `weights-vs-enum-check`: parse `weights.yaml` key + query `SELECT unnest(enum_range(NULL::cheat_event_type))` từ PG container, assert subset. Fail nếu YAML có event không nằm trong ENUM.

6. **Không cần đổi** (đã đủ v2.1): `cheat_appeals`, `outbox`, `processed_events`, `exam_attempts.state_version` (đã có `schema.sql:343`).

Schema master version tương ứng: `database/postgresql/schema.sql` (cần merge các delta trên trước khi scaffold CDS app).
Khi prod migrate lần đầu, các item delta trên phải biến thành Flyway file _immutable_.

### 18.2 MVP (Q2/2026)

- [ ] Flyway migration V1 cho delta schema (partition cheat_events, weight_config_history)
- [ ] L1 client behavior + L2 browser integrity pipeline + weight YAML config
- [ ] Event ingestion (Kafka + HTTP fallback) + HMAC verify
- [ ] RiskScoreCalculator + Redis Lua atomic
- [ ] ThresholdEvaluator + dispatch alert qua outbox
- [ ] Basic review queue REST + pickup/decide (no SLA cron yet)
- [ ] Outbox relayer (shared-outbox-starter)
- [ ] gRPC `GetRiskScore` + `RecordManualEvent` cho Exam Service
- [ ] MaxMind GeoIP mount + L3 ip_change/geolocation_change
- [ ] Permission enforce `cheat.review`, `cheat.decide`, `cheat.appeal.submit`

### 18.3 Phase 2 (Q3/2026)

- [ ] L3 full (VPN IPQualityScore + circuit breaker)
- [ ] L4 behavior analytics (Kafka Streams + typing baseline từ Analytics)
- [ ] Appeal workflow đầy đủ + notification integration
- [ ] SLA cron auto-escalation
- [ ] Review queue SLA dashboard
- [ ] False positive regression gate CI + dataset initial
- [ ] ClickHouse `cheat_analytics` DB (cross-service read)
- [ ] Admin config hot-reload (`/admin/cheat/weights` PATCH)

### 18.4 Phase 3 (Q4/2026)

- [ ] L5 Vision proctoring (Triton GPU, bounded pre-proc pool)
- [ ] L6 Flink statistical cross-attempt job
- [ ] A/B test weights framework (shadow mode)
- [ ] False positive reduction ML model (2nd layer filter)
- [ ] Mobile SDK for on-device monitoring
- [ ] Proctoring Service spin-off (ADR mới — tách vision khi GPU > 4 node)
- [ ] Risk-based difficulty scaling (gửi signal cho Question Service)
- [ ] Liveness check (blink detection, random head turn) chống deepfake

### 18.5 Open questions

1. **Student tự xem risk_score trong thi?** → **Không** (chống reverse-engineer threshold); chỉ thấy warning level (low/medium/high) qua WS, không số điểm.
2. **Giáo viên override weight cho exam cụ thể?** → Có, nhưng limited (±30% base weight); không được set event xuống 0. Implement Phase 2 qua `/admin/cheat/whitelist` + per-exam override table (chưa schema, quyết định trước Phase 2).
3. **Browser fingerprint scope?** → Balance fingerprint strength vs privacy; dùng GDPR-compliant fingerprint (không persist FingerprintJS Pro-level identification). Document data category trong privacy policy.
4. **Deepfake trong video proctoring?** → Phase 3: liveness check trước khi thi + periodic random head turn challenge.
5. **Share IP blocklist cross-org?** → Privacy concern. Phase 3: opt-in sharing với hashed IP qua platform-level feed.
6. **L6 threshold tuning** — cosine similarity > 0.92 có phù hợp mọi exam? → Phase 2: per-subject threshold tuning, tracked qua ClickHouse fit_rate.
7. **Video retention policy khi overturn appeal?** → Hiện 12 tháng. Nếu overturned, có nên early-delete? → Giữ full 12 tháng cho consistency (legal hold); document trong privacy policy.
8. **Proctoring Service tách riêng khi nào?** → ADR-002 §Implementation: khi GPU > 4 node hoặc cần edge inference đa vùng. Track trigger qua `cheat_vision_gpu_utilization` metric.
9. **CDS multi-region?** → ADR-001 §1 hiện single-region. Re-evaluate khi platform scale multi-region; CDS cần regional instance để reduce cross-region latency (event hot path).

---

_Tài liệu thuộc Hệ Thống Thi Trực Tuyến Thông Minh — Cheating Detection Service Design v2.0 — Tháng 4/2026._
