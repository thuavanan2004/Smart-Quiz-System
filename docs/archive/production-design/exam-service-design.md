# EXAM SERVICE - THIẾT KẾ CHI TIẾT

**Hệ Thống Thi Trực Tuyến Thông Minh** | Version 2.1 | Tháng 4/2026

Tài liệu này mở rộng mục "Exam Service" trong `design.md`, mô tả chi tiết ở mức đủ để triển khai code production. Exam Service là **trái tim của platform** — mất 1 đáp án ≡ hỏng bài thi của học sinh.

**Tài liệu nguồn ràng buộc (phải đọc trước khi thay đổi):**
- `CLAUDE.md` §2 (stack lock), §3 (NFR lock — outbox, state_version fencing, idempotent consumer), §9 (prereqs scaffold)
- `docs/adr/ADR-001-sla-rpo-outbox.md` (SLA 99.9% platform / 99.95% trong window thi, RPO đáp án ≤ 5s, transactional outbox)
- `docs/adr/ADR-002-analytics-vs-cheating-split.md` (ranh giới Cheating ↔ Analytics consumer)
- `database/postgresql/schema.sql` §6 (exams, sections, questions, enrollments), §7 (attempts, answers + `state_version`), §13 (outbox / processed_events)
- `database/redis/schema.md` Nhóm 1 (session thi ở `redis-hot`), Nhóm 4 (pub/sub WS)
- `shared-contracts/avro/exam/*.avsc` + `shared-contracts/avro/TOPICS.md` (event schema — BACKWARD compat)
- `docs/auth-service-design.md` §5.1 (JWT claim shape), §10 (RBAC permission-based)

**Changelog v2.1 (2026-04-22) — self-review fixes:**
- **§5.1 + §11.1**: thêm `exam.attempt.completed.v1` (per-attempt terminal aggregator — submitted/expired/terminated) để khớp contract CDS consume (`cheating-detection-service-design.md` §1175). Giữ `exam.exam.completed.v1` cho exam-level completion (all attempts).
- **§11.1**: thêm `cheat.event.raw.v1` vào fire-and-forget producer (WS forward từ client keystroke/tab-switch; §10.2 đã reference).
- **§9.3 code snippet**: fix Redis API — `adaptive:{attempt_id}` là Hash (theo `database/redis/schema.md` Nhóm 1), không phải Set/List. Dùng `HGETALL` + parse CSV thay cho `sMembers`/`lRange`.
- **§7.5**: đổi tên field consumer `alert.expectedStateVersion()` → `alert.stateVersionAtDetection()` khớp Avro contract CDS publish (`cheating-detection-service-design.md` §1308).
- **§1.3, §7.1, §10.1, §12.2**: unify WS URL prefix `/api/v1/ws/attempts/{id}` ở literal form (response body, OpenAPI spec, WS handshake); shorthand trong bảng vẫn `/ws/attempts/{id}`.
- **§13.7**: fix `Permissions-Policy` syntax — `camera=(self)` thay vì `camera=(self) "self"` (syntax lỗi).
- **§16.2**: fix Vault path — lockdown shared secret dùng KV engine (`secret/data/exam/lockdown`), không phải Transit (Transit chỉ dùng cho crypto ops không lưu secret).

**Changelog v2.0 (2026-04-22) — align với auth-service-design v1.5 + NFR lock:**
- Rewrite tổng thể theo template auth-service-design.md v1.5 (header format, §12.0 API conventions, RFC 7807, Idempotency-Key, rate limit headers, cursor pagination, no envelope).
- **§III + §IV**: tách Domain Model (aggregate logic) khỏi Data Model (DDL invariants). Không copy DDL — nguồn truth duy nhất là `schema.sql` §6-§7.
- **§IV.1**: thêm nguyên tắc **fencing token `state_version`** (đã có cột trong schema) chống race giữa Exam submit vs Cheating auto-suspend (CLAUDE.md §3 + `cheating-detection-service-design.md` §793).
- **§V.3**: state transition pattern bắt buộc `UPDATE ... WHERE id=? AND state_version=?` + `state_version = state_version + 1`; nếu `affected_rows=0` → `409 STALE_STATE_VERSION` + publish `exam.attempt.suspend_skipped.v1` (cho CDS consume).
- **§VI.3**: dual-write Redis → PG reframe theo ADR-001 — đáp án **durable = PG**, Redis chỉ cache write-through. `POST /answers` sync ghi PG `attempt_answers` + `outbox` row trong 1 TX; Redis cập nhật sau để serve hot read (timer, reconnect). Loại bỏ mô tả cũ "Redis = durable via AOF".
- **§XI rewrite — outbox-first events**: tất cả event critical đi qua outbox. `answer.submitted` có volume cao nên chọn encoding hiệu quả + partition outbox theo day. Fire-and-forget chỉ cho `exam.ws.heartbeat.v1`, `cheat.event.raw.v1`, `exam.exam.activated.v1`, `exam.exam.archived.v1` (nội bộ, không drive state).
- **§XII.0**: full API conventions theo auth v1.5 — `/api/v1/` prefix bắt buộc, path kebab + json snake_case, status 200/201/202/204/400/401/403/404/409/410/422/423/429/503, Idempotency-Key cho `/answers` + `/submit`, cursor pagination cho list.
- **§XII.6 template**: `POST /attempts/{id}/answers` + `POST /attempts/{id}/submit` với JSON Schema đầy đủ làm reference cho OpenAPI codegen.
- **§XV**: RFC 7807 Problem Details + bảng mã lỗi đầy đủ (`EXAM_*`, `ATTEMPT_*`, `ANSWER_*`, `GRADING_*`, `STALE_STATE_VERSION`, `IDEMPOTENCY_KEY_REUSE_DIFFERENT_BODY`).
- **§XIV**: SLO align ADR-001 (99.95% trong window thi); outbox metrics `exam_outbox_pending_size`, `exam_outbox_publish_lag_seconds`; alert rule `ExamAnswerOutboxBacklog`.
- **§XVII**: quality gate Spotless + Checkstyle + JaCoCo (domain ≥ 80%, application ≥ 70%); test bắt buộc fencing token race + outbox replay.
- **§XVIII**: gate prereqs theo CLAUDE.md §9 + open questions đã có quyết định từ cross-service review.

**Changelog v1.0 (2026-04) — initial:** state machine exam/attempt, Redis session, hot path flows, grading pipeline, IRT adaptive, WebSocket scale.

---

## I. TỔNG QUAN

### 1.1 Vai trò

Exam Service là **owner** của life-cycle bài thi và lượt thi. Nó **tạo ra điểm** (auto-grade + aggregate AI), **nắm đồng hồ authoritative** và **xuất mọi event state change** cho các consumer downstream (Analytics, Cheating, Grading AI, Notification, Certificate).

| Trách nhiệm | Không thuộc phạm vi |
| ----------- | ------------------- |
| Vòng đời bài thi (`draft → active → archived`) | Tạo nội dung câu hỏi (Question Service) |
| Vòng đời lượt thi (`in_progress → submitted → graded`) | Chấm AI essay / code execution (AI Service) |
| Timer authoritative + WebSocket sync | Phát hiện gian lận (Cheating Service — Exam chỉ consume `cheat.alert.generated.v1`) |
| Xáo câu hỏi deterministic per-attempt | Phân tích OLAP (Analytics Service) |
| Submit đáp án idempotent (dedupe `submission_id`) | Gửi email kết quả (Notification Service) |
| Auto-grade các type tự chấm (mc, tf, ordering, matching, drag_drop, hotspot, fill_blank với confidence ≥ 0.75) | Xuất PDF / chứng chỉ (Certificate Service, future) |
| Fan-out `grading.request.v1` cho type async (essay, code, short_answer AI-assist) | Giám thị video (Proctoring Service) |
| Publish event critical qua **transactional outbox** (ADR-001) | Lưu video (Media Service) |
| Adaptive IRT theta/SE update + next-question selection | Calibrate IRT params (Analytics batch job) |
| Fencing token `state_version` chống race với Cheating auto-suspend | Review queue cheat alerts (Cheating Service) |

### 1.2 Stack công nghệ

> Bản này đã lock theo `CLAUDE.md §2`. Đổi công nghệ phải viết ADR mới.

| Thành phần | Chọn | Lý do |
| ---------- | ---- | ----- |
| Runtime | **Java 21 LTS + Spring Boot 3.3+** | Thống nhất với Auth/Question (CLAUDE.md §2). `spring.threads.virtual.enabled=true` cho **I/O path** (JDBC, Redis, Kafka, gRPC, WebClient). CPU-bound công việc của Exam rất ít (auto-grade chủ yếu là so sánh) — không cần bounded pool riêng. |
| WebSocket | Spring WebSocket (native) + STOMP + Redis pub/sub | Scale 5k WS/pod × 20 pod = 100k đồng thời. Sticky session ở ingress + Redis pub/sub cho cross-pod message. |
| ORM | Spring Data JPA + Hibernate 6 | `exams`, `exam_sections`, `exam_questions`, `exam_enrollments`, `exam_attempts`, `attempt_answers`. `@Version` mapping cho `state_version`. |
| Migration | **Flyway** (`src/main/resources/db/migration`) | Versioned SQL; naming `V{epoch}__exam_*.sql`. |
| Redis client | Lettuce + Redisson | Lettuce cho Spring Data Redis; Redisson cho distributed lock (`lock:start:*`, `lock:submit:*`) + sorted-set leaderboard. |
| Mongo client | Spring Data MongoDB (read-only) | Load câu hỏi khi start attempt qua cache (miss → gRPC Question Service). |
| gRPC | Proto shared (`api-grpc/`) + grpc-java | Internal: gọi Auth.ValidateToken, Question.BatchGet; expose Exam.GetAttempt, Exam.StreamAttemptEvents cho Analytics/Cheating. |
| Event bus | Spring Kafka + **Transactional Outbox** (ADR-001) | Publish event critical qua outbox, không gọi `kafkaTemplate.send()` trực tiếp từ UseCase. |
| Schema contracts | **Apicurio Schema Registry + Avro**, BACKWARD compat | Event versioning; schema ở `shared-contracts/avro/exam/*.avsc`. |
| Scheduler | Spring `@Scheduled` + **ShedLock** (PG advisory lock) | Cron job: expire in-progress attempts, auto-transition exam `scheduled → active`, cleanup outbox/processed_events. |
| Observability | **Micrometer → Prometheus**, **OpenTelemetry OTLP**, **Loki** | Stack chuẩn repo (CLAUDE.md §2). |
| Logging | SLF4J + Logback JSON encoder (logstash-logback-encoder) + **MDC** (`trace_id`, `user_id`, `org_id`, `attempt_id`, `exam_id`) | Format AI-friendly. |
| Circuit breaker | Resilience4j | Question Service gRPC, AI Service, Media service signed URL. |
| Build | **Gradle** (wrapper pinned) + **Spotless** (google-java-format) + **Checkstyle** + **JaCoCo** | Quality gate CI bắt buộc (§17.1). |
| Test | JUnit 5 + AssertJ + **Testcontainers** (PG 16, Redis 7, Kafka, Apicurio, MongoDB) + WireMock (Question/AI stub) | CLAUDE.md §2 mandate. |
| Load | k6 | Peak scenario 10k concurrent / 60 min. |

### 1.3 Cổng & giao thức

| Giao thức | Port | Mục đích |
| --------- | ---- | -------- |
| HTTP/1.1 + HTTP/2 (REST) | `3002` | Client-facing qua API Gateway |
| WebSocket (STOMP over WS) | `3002/api/v1/ws` | Timer sync, cheat warning, force-submit |
| gRPC | `4002` | Internal: Analytics.StreamAttemptEvents, Cheating.UpdateRiskScore, ProctoringService |
| OpenAPI spec | `3002/v3/api-docs` (JSON) · `3002/swagger-ui.html` | Contract FE + consumer (CLAUDE.md §9 prereq) |
| Actuator (health, metrics) | `9002/actuator/*` | Prometheus scraping + K8s probes |

### 1.4 Yêu cầu phi chức năng (NFR) — lock theo ADR-001

| Chỉ số | Mục tiêu | Ghi chú |
| ------ | -------- | ------- |
| `POST /attempts/{id}/answers` p99 | **< 100ms** | Hot path; sync write PG (`attempt_answers` + outbox) trong 1 TX + async Redis update |
| `POST /exams/{id}/start` p99 | < 300ms | Cache `exam:config:*` hit + gRPC Question BatchGet |
| `POST /attempts/{id}/submit` p99 | < 500ms | Batch upsert answers (đã có PG nhờ §VI.3) + trigger grading fan-out |
| Throughput peak | 10k RPS cluster-wide | Peak giờ thi cao điểm |
| Đồng thời WS | 100.000 kết nối | 20 pod × 5k WS/pod, sticky session |
| **RPO đáp án** | **≤ 5s** (ADR-001 §2) | PG commit sync (không phụ thuộc Redis); outbox relayer ≤ 5s publish Kafka |
| RTO pod crash | < 10s | K8s reschedule + client auto-reconnect |
| **Availability trong window thi** (`starts_at - 15m` đến `ends_at + 30m`) | **99.95%** (ADR-001 §1) | ≤ 22 phút downtime/tháng trong window |
| Availability ngoài window | 99.9% | Platform SLA chung |
| Data loss đáp án | **0** (tuyệt đối) | PG = source of truth; Redis chết ≠ mất answer |

---

## II. KIẾN TRÚC BÊN TRONG

### 2.1 Sơ đồ lớp (layered architecture)

```
┌────────────────────────────────────────────────────────────┐
│  API Layer                                                 │
│  ─ ExamController, AttemptController, AdminExamController  │
│  ─ ExamWebSocketHandler (STOMP)                            │
│  ─ ExamGrpcService                                         │
├────────────────────────────────────────────────────────────┤
│  Application Services (Use Cases)                          │
│  ─ CreateExamUseCase, PublishExamUseCase, ArchiveExamUseCase│
│  ─ StartAttemptUseCase, SubmitAnswerUseCase                │
│  ─ SubmitAttemptUseCase, ResumeAttemptUseCase              │
│  ─ SuspendAttemptUseCase, ExpireAttemptsJob                │
│  ─ NextAdaptiveQuestionUseCase                             │
│  ─ GradingAggregatorUseCase (consume grading.result.v1)    │
├────────────────────────────────────────────────────────────┤
│  Domain Layer                                              │
│  ─ Exam (aggregate root) ─ ExamAttempt (aggregate root)    │
│  ─ ExamStateMachine ─ AttemptStateMachine                  │
│  ─ GradingPolicy ─ ShufflePolicy ─ AccessPolicy            │
│  ─ AdaptiveSelector (IRT 3PL)                              │
│  ─ AutoGraderRegistry (per-type graders)                   │
├────────────────────────────────────────────────────────────┤
│  Infrastructure                                            │
│  ─ JPA repositories (PG, @Version fencing)                 │
│  ─ RedisSessionStore (hot cluster), RedisLockManager       │
│  ─ ExamOutboxPublisher (TX-bound, MANDATORY propagation)   │
│  ─ OutboxRelayer (leader-elect, pg_try_advisory_lock)      │
│  ─ KafkaCheatConsumer (idempotent via processed_events)    │
│  ─ GradingResultConsumer                                   │
│  ─ QuestionGrpcClient (Question Service, Resilience4j)     │
│  ─ AuthGrpcClient (ValidateToken cache 60s)                │
└────────────────────────────────────────────────────────────┘
```

### 2.2 Module Gradle multi-project

Nằm trong `services/exam/` (CLAUDE.md §4). Gradle wrapper pin, version catalog dùng chung ở `/gradle/libs.versions.toml`.

```
services/exam/
├── settings.gradle.kts        # include: app, api-grpc, domain-test-fixtures
├── build.gradle.kts           # root — convention plugins (spotless, checkstyle, jacoco)
├── gradle/                    # wrapper (pinned)
├── api-grpc/                  # .proto + generated stubs (publish → mavenLocal)
│   ├── src/main/proto/
│   └── build.gradle.kts
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/vn/smartquiz/exam/
│       │   ├── ExamServiceApplication.java
│       │   ├── config/        # SecurityConfig, WebSocketConfig, RedisConfig, KafkaConfig, OutboxConfig, ResilienceConfig
│       │   ├── controller/    # @RestController + @ControllerAdvice (RFC 7807)
│       │   ├── websocket/     # Handler + session registry (local + Redis pub/sub)
│       │   ├── grpc/          # gRPC server
│       │   ├── application/
│       │   │   ├── exam/      # CreateExam, PublishExam, ArchiveExam UseCases
│       │   │   └── attempt/   # Start, SubmitAnswer, SubmitFinal, Suspend, Expire
│       │   ├── domain/
│       │   │   ├── exam/      # Exam, ExamStatus, ExamConfig, ExamPolicy
│       │   │   ├── attempt/   # ExamAttempt, AttemptStatus, Answer, StateVersion
│       │   │   ├── grading/   # AutoGrader, GradingResult, GradingPolicy
│       │   │   ├── adaptive/  # IrtModel (3PL), FisherInformation, ThetaEstimator
│       │   │   └── policy/    # AccessPolicy (IP whitelist, password, enrollment)
│       │   ├── infrastructure/
│       │   │   ├── persistence/   # JPA entities + repo (@Version)
│       │   │   ├── redis/         # SessionStore, AnswerBuffer, LockManager
│       │   │   ├── kafka/         # OutboxRelayer + Avro serde (Apicurio) + consumers
│       │   │   ├── question/      # gRPC client + circuit breaker
│       │   │   └── auth/          # gRPC client + JWKS cache
│       │   └── common/        # Exception, ErrorCode, MdcFilter, StateVersionConflictAdvice
│       ├── main/resources/
│       │   ├── application.yml
│       │   ├── logback-spring.xml       # JSON encoder + mask filter (answer payload, JWT)
│       │   ├── db/migration/            # Flyway V{epoch}__exam_*.sql — delta riêng
│       │   └── static/openapi.yaml      # OpenAPI 3.1 spec
│       └── test/java/...
└── README.md
```

**Rule Flyway:** schema master ở `database/postgresql/schema.sql`. Exam Service chỉ commit migration delta riêng vào `app/src/main/resources/db/migration` với prefix `V{yyyymmddhhmm}__exam_*.sql`. Không sửa migration đã release (immutable).

### 2.3 Build quality gate

| Tool | Cấu hình | Gate fail khi |
| ---- | -------- | ------------- |
| Spotless | `googleJavaFormat('1.19.2')` + `removeUnusedImports()` + `trimTrailingWhitespace()` | Format lệch → CI fail, gợi ý `./gradlew spotlessApply` |
| Checkstyle | `config/checkstyle/checkstyle.xml` (Google style + project override) | Bất kỳ error → CI fail |
| JaCoCo | Report HTML + XML → Codecov | `domain/` line coverage < **80%**, `application/` < **70%** |
| OWASP dependency-check | `./gradlew dependencyCheckAggregate` nightly | CVSS ≥ 7.0 trên compile deps |
| Error Prone | Google static analysis | Any new warning trên `main` → review block |
| Apicurio compat | `./gradlew :shared-contracts:avroCompatCheck` | BACKWARD violation → CI fail |

---

## III. DOMAIN MODEL

### 3.1 Aggregate: `Exam`

Root aggregate quản lý cấu hình bài thi. Chỉ instructor/admin thao tác trực tiếp; student chỉ đọc qua view đã strip (§13.5).

```java
public class Exam {
    private ExamId id;
    private OrgId orgId;
    private UserId createdBy;
    private SubjectId subjectId;
    private String title;
    private ExamStatus status;               // draft|published|scheduled|active|completed|archived
    private ExamType type;                   // STANDARD | ADAPTIVE | PRACTICE | SURVEY
    private Duration duration;
    private int maxAttempts;
    private Score passingScore;
    private Score totalPoints;
    private ExamPolicy policy;               // shuffle, IP whitelist, password, proctoring level, lockdown
    private TimeWindow window;               // starts_at, ends_at, grace_period
    private List<ExamSection> sections;
    private List<ExamQuestion> questions;    // Section = null nếu flat
    private Instant publishedAt;

    // Domain operations
    public void publish(Clock clock)                { stateMachine.transition(PUBLISH, this, clock); }
    public void archive(Clock clock)                { stateMachine.transition(ARCHIVE, this, clock); }
    public boolean isCurrentlyOpen(Clock clock)     { return status == ACTIVE && window.contains(clock.instant()); }
    public boolean allowsUser(User u, Set<ExamEnrollmentId> enrolled) { ... }
    public int computeTotalPoints()                 { ... }
    public List<ExamQuestion> shuffledFor(UUID attemptSeed) { ... }  // Fisher-Yates deterministic
}
```

### 3.2 Aggregate: `ExamAttempt`

Root aggregate cho 1 lượt thi — **đơn vị concurrency + đơn vị outbox event**.

```java
public class ExamAttempt {
    private AttemptId id;
    private ExamId examId;
    private UserId userId;
    private OrgId orgId;                 // denormalized cho event payload + multi-tenancy
    private short attemptNumber;
    private AttemptStatus status;        // in_progress|submitted|graded|expired|cancelled|suspended
    @Version
    private long stateVersion;           // ← FENCING TOKEN — xem §IV.1
    private Instant startedAt;
    private Instant submittedAt;
    private Instant gradedAt;
    private Instant expiresAt;
    private Duration timeSpent;
    private Score rawScore;
    private Score maxScore;
    private BigDecimal percentageScore;
    private Boolean passed;
    private short riskScore;             // do Cheating Service update qua gRPC (§XII.4)
    private boolean flaggedForReview;
    private InetAddress ipAddress;
    private String userAgent;
    private GeoInfo geo;
    private List<QuestionRefId> questionOrder;  // thứ tự đã xáo — FROZEN khi started
    private short currentQuestionIndex;
    private Double adaptiveTheta;
    private Double adaptiveSe;

    // Domain operations — mọi mutation bump state_version qua JPA @Version
    public Answer submitAnswer(QuestionRefId qid, AnswerPayload p, UUID submissionId, Clock clock) { ... }
    public void submitFinal(Clock clock) { ... }
    public void suspendForCheating(String reason, long expectedStateVersion) { ... }  // check fencing
    public void expire(Clock clock) { ... }
    public Duration remainingTime(Clock clock) { ... }
    public boolean canAnswerQuestion(QuestionRefId qid) { ... }
}
```

### 3.3 Value objects

```java
public record Score(BigDecimal value) { ... }
public record QuestionRefId(String value) { ... }
public record AnswerPayload(String rawJson) { /* validated theo question.type */ }
public record GradingResult(Score earned, boolean correct, boolean needsManual, String method) { }
public record AttemptSnapshot(short currentIdx, Duration remaining, short riskScore, long stateVersion) { }
public record StateVersionConflict(long expected, long actual) extends RuntimeException { }
```

### 3.4 Permission model (reference Auth Service §3.3-§3.4)

Exam Service **không hardcode role name** — check permission code từ JWT claim `authorities`:

| Permission code | Dùng khi |
| --------------- | -------- |
| `exam.read` | GET exam (instructor + student enrolled) |
| `exam.create` | POST exam (instructor) |
| `exam.update.own` / `exam.update.any` | PATCH exam (owner vs admin override) |
| `exam.publish` | POST /publish |
| `exam.enroll.students` | POST enrollment |
| `attempt.start` / `attempt.submit` / `attempt.read.own` | Student flow |
| `attempt.read.org` | Instructor/admin xem attempt trong org |
| `attempt.grade` | Manual grade essay/code |
| `attempt.suspend` / `attempt.resume` / `attempt.terminate` | Proctor/admin flow |

Enforcement: `@PreAuthorize("hasAuthority('...')")` + `ExamPolicy` bean check scope (own / same-org / enrolled). Cache resolve user→perms Redis 60s; invalidate khi consume `auth.role.changed.v1`.

---

## IV. DATA MODEL — invariants & business rules

> **⚠️ DDL là nguồn truth duy nhất ở `database/postgresql/schema.sql` §6 (exams/sections/questions/enrollments) + §7 (attempts/answers).**
> Section này **KHÔNG copy cột** — chỉ mô tả invariant, retention, fencing, idempotency pattern. Sửa cột: sửa `schema.sql` trước, rồi Flyway migration delta (§18.1).

### 4.1 Fencing token — `exam_attempts.state_version`

**Vấn đề:** 2 race kinh điển giữa Exam Service ↔ Cheating Detection Service (CDS):

- **Race A**: student bấm submit tại `t=10.000s`, đúng lúc CDS phát hiện rủi ro và publish `cheat.alert.generated.v1` với `auto_action=SUSPEND` tại `t=10.002s`. Exam đã set `status=submitted` xong rồi, CDS consumer lại UPDATE `status=suspended` → mất trạng thái submitted, score bị drop, cheating log confusing.
- **Race B**: 2 CDS consumer thread xử lý 2 alert khác nhau cùng attempt, cả 2 suspend → không hại về ý nghĩa nhưng double-publish `exam.attempt.suspended.v1` → notification spam.

**Giải pháp — optimistic lock qua `state_version`**:
- Mọi UPDATE `exam_attempts` phải kèm `AND state_version = :expected`; bump `state_version = state_version + 1` trong cùng SQL.
- JPA mapping: `@Version` trên field `stateVersion` → Hibernate tự thêm `AND state_version=?` và throw `OptimisticLockException` khi 0 row affected.
- CDS gRPC `SuspendAttempt(attempt_id, expected_state_version, reason)` — nếu `expected != actual` → server trả `FAILED_PRECONDITION` + publish `exam.attempt.suspend_skipped.v1 {reason: STALE_STATE_VERSION, attempt_id, expected, actual}` cho CDS log warning, không retry (state đã thay đổi — quyết định cũ không còn áp dụng).

```java
// Inside SubmitAttemptUseCase
@Transactional
public SubmitResult execute(AttemptId id) {
    ExamAttempt attempt = attemptRepo.findById(id).orElseThrow(AttemptNotFound::new);
    long beforeVersion = attempt.stateVersion();
    attempt.submitFinal(clock);
    try {
        attemptRepo.saveAndFlush(attempt);          // Hibernate WHERE state_version=beforeVersion
    } catch (OptimisticLockException e) {
        // CDS đã suspend giữa chừng — respect CDS decision, return 409
        throw new StateVersionConflict(beforeVersion, /* unknown */ -1);
    }
    outbox.publish("exam.attempt.submitted.v1", ...);  // cùng TX với save
    return ...;
}
```

### 4.2 `exams` + `exam_sections` + `exam_questions` + `exam_enrollments` (schema §6)

DDL: `schema.sql:234` (exams), `:275` (sections), `:289` (questions), `:305` (enrollments).

**Invariants business-level (không thấy từ DDL):**
- `exams.status` transition tuân state machine §V.1 — không update trực tiếp từ UseCase, phải qua `ExamStateMachine`.
- `exams.question_order` **không lưu trong `exams`** — lưu per-attempt ở `exam_attempts.question_order` (deterministic Fisher-Yates seed = `hash(attempt_id + exam.id)`).
- `exam_questions.question_ref_id` trỏ sang MongoDB; `question_version` pin tại thời điểm publish exam → cho phép Question Service đổi câu hỏi mà không ảnh hưởng exam đã publish.
- `exam_enrollments` UNIQUE `(exam_id, user_id)` — re-enroll là no-op (idempotent).
- `exams.access_password_hash` = Argon2id (reuse hasher của Auth Service — chung lib).
- **Retention**: `exams.status=archived` + `deleted_at IS NULL` giữ vĩnh viễn (bằng chứng học tập). `deleted_at` = soft delete, batch cleanup sau 5 năm.

### 4.3 `exam_attempts` (schema §7)

DDL: `schema.sql:337`. Đã có `state_version BIGINT NOT NULL DEFAULT 0` cho fencing (§4.1).

**Invariants:**
- UNIQUE INDEX `idx_one_active_attempt ON (exam_id, user_id) WHERE status='in_progress'` — 1 user không có 2 attempt đang chạy cùng 1 exam. Start attempt race: PG error 23505 → trả `409 ATTEMPT_IN_PROGRESS_EXISTS` + gợi ý resume.
- `question_order UUID[]` FROZEN khi `started_at` — không re-shuffle khi resume. Adaptive exam: thứ tự lớn dần theo câu chọn động, vẫn append-only.
- `expires_at = started_at + exam.duration + grace_period_minutes`. Server-side check: `now > expires_at + NETWORK_GRACE_5S` → reject.
- `risk_score` update **chỉ từ Cheating Service** qua gRPC `UpdateRiskScore(attempt_id, expected_state_version, new_risk)`. Exam Service không tự tính.
- **Retention**: khi migrate partitioned (schema §7 comment, tại ~10M rows), `PARTITION BY RANGE(started_at)` theo tháng. FK từ `attempt_answers`, `cheat_events`, `proctoring_sessions`, `certificates` phải cập nhật thành `(id, started_at)` composite. Migration task Phase 2.

### 4.4 `attempt_answers` (schema §7)

DDL: `schema.sql:377`. `submission_id UUID UNIQUE NOT NULL DEFAULT gen_random_uuid()` = idempotency key.

**Invariants:**
- `submission_id` **client-generated** (UUID v4) — sent trong POST body, UNIQUE index làm dedupe. Replay cùng `submission_id` trong cùng attempt → no-op (ON CONFLICT DO NOTHING). Lần thứ 2 server đọc row cũ và trả lại response.
- `answer_data JSONB` payload theo shape `question.type` (validate app-level trước INSERT):
  - `multiple_choice_single`: `{"selected_options": ["opt-uuid"]}` (size = 1)
  - `multiple_choice_multi`: `{"selected_options": ["o1","o2"]}`
  - `true_false`: `{"value": true|false}`
  - `ordering`: `{"order": ["o1","o2","o3"]}`
  - `matching`: `{"pairs": {"left-1":"right-3", ...}}`
  - `drag_drop`: `{"zones": {"zone-A":["item-1","item-2"]}}`
  - `hotspot`: `{"points": [{"x":0.45,"y":0.72}]}`
  - `fill_blank`: `{"blanks": ["answer1", "answer2"]}`
  - `short_answer`: `{"text": "..."}`
  - `essay`: `{"text": "..."}`
  - `code_execution`: `{"language":"python","code":"...","test_case_ids":["tc1"]}`
- `grading_method` enum app-level (DDL VARCHAR(20)): `auto | ai | manual`.
- `is_correct` / `points_earned` NULL khi pending AI — consumer `grading.result.v1` UPDATE.
- **Retention**: partition theo tháng khi đạt 10M rows (schema note). Dữ liệu giữ theo retention của `exam_attempts` cùng partition.

### 4.5 `proctoring_sessions` — read-only cho Exam

Exam Service **không ghi** `proctoring_sessions`. Chỉ Proctoring Service write. Exam đọc `proctoring_sessions.status` để hiển thị "có giám thị" trên UI.

### 4.6 `outbox` + `processed_events` (schema §13)

Dùng chung với Auth/Cheating/Analytics. Exam Service:
- INSERT `outbox` trong cùng TX với domain change (§11.2).
- INSERT `processed_events` khi consume `cheat.alert.generated.v1`, `grading.result.v1`, `auth.role.changed.v1`, `auth.user.deleted.v1`, `question.updated.v1`.
- Consumer group name: `exam-service-{purpose}` (vd `exam-service-cheat`, `exam-service-grading`).
- Cleanup: outbox row `published_at IS NOT NULL AND published_at < NOW() - interval '7 days'` → batch delete mỗi đêm 03:00. `processed_events` giữ 7 ngày (match Kafka retention).

---

## V. STATE MACHINES

### 5.1 Exam state machine

```
       ┌─────┐   publish    ┌──────────┐
       │draft├─────────────►│published │
       └──┬──┘              └────┬─────┘
          │                      │
          │      auto schedule   │
          │ ◄────────────────────┤ (nếu starts_at > now)
          ▼                      ▼
     ┌─────────┐            ┌─────────┐
     │scheduled│ ──auto──►  │ active  │
     └─────────┘            └────┬────┘
                                 │ ends_at + grace reached
                                 ▼
                            ┌─────────┐ archive ┌──────────┐
                            │completed├────────►│archived  │
                            └─────────┘         └──────────┘
```

**Guards & actions** (runtime check trong `ExamStateMachine`; không tin trạng thái trong body request):

| Transition | Guard | Action | Event outbox |
| ---------- | ----- | ------ | ------------ |
| draft → published | ≥ 1 câu hỏi, `total_points > 0`, `starts_at IS NOT NULL`, `ends_at > starts_at` | `published_at = now()`; warm cache `exam:config:*`, `exam:q_ids:*` | `exam.exam.published.v1` |
| published → scheduled | `starts_at > now()` | auto cron mỗi 1 phút | — |
| published/scheduled → active | `now() >= starts_at AND now() < ends_at` | auto cron | `exam.exam.activated.v1` (fire-and-forget) |
| active → completed | `now() > ends_at + grace_period` OR tất cả enrollee đã submit | auto cron mỗi 1 phút | `exam.exam.completed.v1` (trigger Analytics batch aggregation) |
| completed → archived | thủ công hoặc cron sau 90 ngày | freeze data | `exam.exam.archived.v1` (fire-and-forget) |

**Lưu ý phân biệt exam-level vs attempt-level completion**:
- `exam.exam.completed.v1` — exam-level, phát 1 lần khi exam kết thúc (trigger batch analytics aggregation).
- `exam.attempt.completed.v1` — attempt-level, phát **mỗi** lần 1 attempt reach terminal state (submitted / expired / terminated / cancelled). Đây là topic CDS subscribe để trigger L6 Flink job per-attempt (xem `cheating-detection-service-design.md` §1175). **Publish trong cùng TX với transition** (co-publish với `submitted.v1`/`graded.v1`/etc., payload khác nhau).

### 5.2 Attempt state machine

```
                     ┌──────────────┐
                     │ in_progress  │
                     └──┬──┬──┬──┬──┘
            submit      │  │  │  │ timeout (now >= expires_at + 5s)
                 ┌──────┘  │  │  └──────┐
                 │ suspend │  │ cancel  │
                 │ (CDS)   │  │(student)│
                 ▼         ▼  ▼         ▼
          ┌──────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐
          │submitted │ │suspended│ │cancelled│ │ expired │
          └────┬─────┘ └────┬────┘ └─────────┘ └────┬────┘
               │ grade      │resume                  │ auto-submit
               │ aggregate  ▼                        │ (force=expired)
               │       in_progress                   │
               │                                     ▼
               │                               ┌──────────┐
               │                               │submitted │
               │                               └────┬─────┘
               ▼                                    │
          ┌──────────┐                              │
          │  graded  │◄─────────────────────────────┘
          └──────────┘

  suspended ──terminate(confirm cheat)──► submitted (raw_score=0, flagged=true)
```

**Guards (đều check qua `state_version`):**

| Transition | Guard | Expected `state_version` |
| ---------- | ----- | -------------------------- |
| → in_progress (khởi tạo) | exam đang `active`, user enrolled, no other in_progress (UNIQUE index) | N/A (INSERT) |
| in_progress → submitted (user) | `now() < expires_at + 5s` | hiện tại của attempt |
| in_progress → expired | cron phát hiện `now() >= expires_at + 5s` | hiện tại |
| in_progress → suspended | consumer `cheat.alert.generated.v1` với `auto_action=SUSPEND` | hiện tại (từ event payload); miss → skip + publish `exam.attempt.suspend_skipped.v1` |
| suspended → in_progress (resume) | proctor action, `now() < expires_at` | hiện tại |
| suspended → submitted (terminate) | proctor action confirm cheat | hiện tại |
| submitted → graded | tất cả answer có `is_correct IS NOT NULL` hoặc `grading_method='manual' AND graded_at IS NOT NULL` | hiện tại |

### 5.3 Triển khai state transition với fencing

```java
// Pattern chuẩn cho mọi transition
@Transactional
public Attempt transition(AttemptId id, long expectedVersion, Action action) {
    ExamAttempt attempt = attemptRepo.findByIdAndStateVersion(id, expectedVersion)
        .orElseThrow(() -> new StateVersionConflict(expectedVersion, -1));
    // Domain check
    stateMachine.assertCanTransition(attempt.status(), action);
    // Mutate (Hibernate @Version tự bump state_version khi flush)
    action.apply(attempt);
    // Save — Hibernate generate: UPDATE ... WHERE id=? AND state_version=?
    attemptRepo.save(attempt);
    // Outbox trong cùng TX
    outbox.publish(topicFor(action), attempt.id().toString(), payload(attempt, action), attempt.id().toString());
    return attempt;
}
```

Dùng thư viện `spring-statemachine-core` chỉ để **validate transition rule** (bảng `guard` ở §5.2); **không** dùng `StateMachinePersister` (persist state trong entity field + JPA @Version là đủ).

---

## VI. SESSION MANAGEMENT (Redis)

### 6.1 Topology — 2 cụm Redis

Theo `database/redis/schema.md` Nhóm 1 + Nhóm 4:

| Mục đích | Cụm | Persistence | Eviction | Key prefix |
| -------- | --- | ----------- | -------- | ---------- |
| Session thi, answer buffer | `redis-hot` | AOF appendfsync=everysec + RDB 5m | `noeviction` | `session:*`, `answers:*`, `q_order:*`, `adaptive:*`, `exam:concurrent:*` |
| Exam config cache | `redis-cache` | RDB 5m | `allkeys-lru` | `exam:config:*`, `exam:q_ids:*`, `question:*` |
| Rate limit, lock | `redis-cache` | RDB 5m | `allkeys-lru` | `rate:*`, `lock:*`, `idempotency:*` |
| Pub/Sub WS | `redis-hot` | — | — | `ws:exam:{attempt_id}` |

### 6.2 Cấu trúc key cho 1 attempt đang thi

| Key | Kiểu | Trường / Nội dung | TTL | Cập nhật bởi |
| --- | ---- | ----------------- | --- | ------------ |
| `session:{attempt_id}` | Hash | `remaining_seconds`, `current_q_idx`, `risk_score`, `status`, `last_heartbeat`, `exam_id`, `user_id`, `state_version` | `duration + 30m` | Mọi request ghi attempt |
| `answers:{attempt_id}` | Hash | `{question_ref_id}` → `{submission_id, payload_hash, saved_at}` | `duration + 2h` | POST /answers (hot read for reconnect) |
| `q_order:{attempt_id}` | List | UUID câu hỏi đã xáo | `duration + 2h` | Set khi start |
| `adaptive:{attempt_id}` | Hash | `theta`, `se`, `answered_ids` (CSV), `next_q_pool` (CSV) | `duration + 1h` | Adaptive selector |
| `lock:start:{exam_id}:{user_id}` | String NX | distributed lock khi start | 10s | StartAttemptUseCase |
| `lock:submit:{attempt_id}` | String NX | lock khi submit | 30s | SubmitAttemptUseCase |
| `idempotency:{submission_id}` | String | cached response JSON (status + body) | 1h | SubmitAnswerUseCase (§12.0.6) |
| `exam:concurrent:{exam_id}` | Set | tập `attempt_id` đang thi | `duration + 30m` | Start/Submit hook |

### 6.3 Dual-write strategy — PG = source of truth, Redis = hot cache

> **QUAN TRỌNG (ADR-001 §2 + CLAUDE.md §3):** Redis không phải nguồn truth. Mất Redis = user reconnect chậm 1 nhịp (rebuild từ PG). **Mất PG = lose answer — unacceptable.**

Flow `POST /attempts/{id}/answers`:

```
HTTP → Controller
  ├─ 1. Auth (JWT verify offline qua JWKS cache 1h)
  ├─ 2. Idempotency check: GET idempotency:{submission_id}
  │        Hit → return cached response (status 200, body reused) — exit here
  ├─ 3. Load session from Redis (HGETALL session:{attempt_id})
  │        Miss → fallback load from PG (rebuild session)
  │        Check status=in_progress, now < expires_at + 5s, q_ref_id ∈ q_order
  ├─ 4. Validate payload shape theo question.type (§4.4)
  ├─ 5. @Transactional:
  │        a. INSERT attempt_answers (..., submission_id, answer_data) ON CONFLICT (submission_id) DO NOTHING
  │        b. IF affected_rows = 0 → đã xử lý trước đó, load existing → return same response
  │        c. INSERT outbox (topic=exam.answer.submitted.v1, payload={...}, partition_key=attempt_id)
  │        d. UPDATE exam_attempts SET current_question_index=?, time_spent_seconds=?, state_version=state_version+1
  │              WHERE id=? AND state_version=?   -- optimistic lock
  │        e. COMMIT
  ├─ 6. Cache response in idempotency:{submission_id} EX 1h
  ├─ 7. Async (fire-and-forget): Redis HSET answers:{attempt_id} / session:{attempt_id}
  │        Nếu Redis down: log warning, KHÔNG fail request — PG đã có
  └─ 8. Return 200 {saved: true, submission_id, server_time}
```

**Tại sao KHÔNG ghi Redis sync trước PG?** Nếu Redis hit trước, user thấy "saved" nhưng PG crash → user tưởng đã lưu mà thực tế mất. Đảo thứ tự: PG commit là điểm xác nhận durable; Redis chỉ là cache để `GET /snapshot` nhanh.

**Latency budget** cho p99 < 100ms:
- JWT verify (cache): 2ms
- Redis GET idempotency + HGETALL session: 5ms
- Validate payload: 2ms
- PG `INSERT attempt_answers` + `INSERT outbox` + `UPDATE exam_attempts` (1 TX): 30-50ms
- Redis update async (không block): 0 (fire-and-forget)
- **Total ~60-70ms** — ổn với budget 100ms.

Outbox relayer (§11.2) publish `exam.answer.submitted.v1` lên Kafka trong vòng ≤ 5s (ADR-001 RPO).

### 6.4 Timer authoritative

**Nguyên tắc:** server là single source of truth cho thời gian. Client chỉ để hiển thị.

```
remaining_seconds = max(0, (expires_at - now_utc).total_seconds)
```

- `expires_at` lưu `exam_attempts.expires_at` + mirror trong `session:{attempt_id}`.
- Client heartbeat WebSocket mỗi 10s → server trả frame `timer.sync {remaining_ms, server_time_ms, expires_at}`.
- Client điều chỉnh đồng hồ hiển thị theo response; không tin `Date.now()`.
- Nếu client mất kết nối > 30s → server **không** pause; user vẫn bị tính giờ.
- Pause chỉ xảy ra khi proctor suspend (`status=suspended`) — cron auto-submit skip attempt suspended cho tới khi resume.

### 6.5 Resume attempt sau disconnect

```
GET /api/v1/attempts/{id}/snapshot
  │
  ├─ Check session:{attempt_id} tồn tại (Redis hot)
  │    Hit → trả về {remaining_sec, current_q_idx, answered_refs, state_version}
  │
  └─ Miss (Redis restart / evicted):
        Load from PG: SELECT * FROM exam_attempts WHERE id=?
        IF status='in_progress' AND now < expires_at:
            Rebuild session:{attempt_id} từ PG (SELECT question_ref_id FROM attempt_answers WHERE attempt_id=?)
            Return state
        ELSE:
            Return 410 Gone - attempt expired/submitted/cancelled
```

Rebuild overhead: ~20ms (1 SELECT exam_attempts + 1 SELECT attempt_answers). Acceptable cho resume flow (rare).

---

## VII. HOT PATH FLOWS

### 7.1 Start Attempt — p99 < 300ms

```
POST /api/v1/exams/{examId}/start
Headers: Authorization: Bearer <JWT>
         X-Device-Fingerprint: <client-sig>
         Idempotency-Key: <UUID v4>   (per-user best practice, không bắt buộc)
Body:    { "access_password": "...?" }
```

Pseudocode:

```
1. Verify JWT offline (JWKS cache 1h)
2. Extract user_id, org_id, authorities[]

3. ACQUIRE Redis lock: lock:start:{exam_id}:{user_id} SET NX EX 10
   Fail → 409 AUTH_LOCK_HELD (retry sau 1s)

4. Load exam:
   - Cache exam:config:{exam_id} (redis-cache, 30m TTL)
   - Miss → SELECT FROM exams + exam_sections + exam_questions → cache

5. Access policy checks (all fail → 403 specific error):
   - exam.status ∈ {published, active}           → EXAM_NOT_OPEN
   - now ∈ [starts_at, ends_at]                   → EXAM_NOT_OPEN
   - user.org_id == exam.org_id OR enrolled       → EXAM_ACCESS_DENIED
   - exam.ip_whitelist empty OR ip ∈ whitelist    → EXAM_IP_NOT_ALLOWED
   - access_password valid (Argon2 verify)        → EXAM_PASSWORD_INVALID
   - attempt_count(user, exam) < max_attempts     → EXAM_MAX_ATTEMPTS_REACHED
   - NO existing in_progress (UNIQUE index)       → ATTEMPT_IN_PROGRESS_EXISTS (gợi ý resume)

6. Load question_ids:
   - Cache exam:q_ids:{exam_id} (List Redis 30m)
   - Miss → gRPC Question.BatchGet(question_ref_ids) → cache
   - Circuit breaker Resilience4j: nếu open → 503 QUESTION_SERVICE_DOWN

7. Shuffle (nếu exam.shuffle_questions):
   - seed = SHA256(attempt_id_about_to_create + exam.id)[:8] as long
   - Fisher-Yates với seed → deterministic

8. @Transactional:
   a. INSERT exam_attempts (status='in_progress', started_at=NOW(),
                            expires_at=NOW() + duration + grace_period,
                            question_order=shuffled[],
                            state_version=0, ...)
      (catch UNIQUE VIOLATION idx_one_active_attempt → 409 ATTEMPT_IN_PROGRESS_EXISTS)
   b. INSERT outbox (topic=exam.attempt.started.v1, ..., partition_key=attempt_id)
   c. COMMIT

9. Redis hot write (async fire-and-forget; failure → log):
   HSET session:{attempt_id} remaining_seconds=... current_q_idx=0 status=in_progress
                            exam_id=... user_id=... state_version=0 last_heartbeat=...
   RPUSH q_order:{attempt_id} <uuid>...
   EXPIRE session:* duration+30m
   SADD exam:concurrent:{exam_id} {attempt_id}
   (Nếu adaptive) HSET adaptive:{attempt_id} theta=0 se=1.0

10. Release lock (DEL lock:start:...)

11. Return 201 Created
    Headers: Location: /api/v1/attempts/{attempt_id}
             X-Request-Id, X-Trace-Id
    Body:
    {
      "attempt_id": "...",
      "state_version": 0,
      "expires_at": "2026-04-22T11:05:00Z",
      "server_time": "2026-04-22T10:05:00Z",
      "current_question_index": 0,
      "total_questions": 15,
      "question": { /* stripped — không có is_correct/explanation */ },
      "ws_url": "wss://api.smartquiz.vn/api/v1/ws/attempts/{attempt_id}"
    }
```

**Optimization:** Bước 4-6 phải hit cache — warm cache ngay sau `exam.publish` (consume `exam.exam.published.v1` trong Exam Service tự consume).

### 7.2 Submit Answer — p99 < 100ms, **idempotent**

Xem §6.3 cho flow đầy đủ. Request body:

```json
{
  "question_ref_id": "q-...",
  "answer_data": { /* theo question.type, §4.4 */ },
  "submission_id": "550e8400-e29b-41d4-a716-446655440000",
  "client_timestamp": "2026-04-22T10:15:03.123Z"
}
```

Response:

```json
{
  "saved": true,
  "submission_id": "550e8400-e29b-41d4-a716-446655440000",
  "server_time": "2026-04-22T10:15:03.251Z",
  "state_version": 12
}
```

**Lý do trả `state_version`:** client (nhất là WS proctor) có thể cần để gRPC-call CheatingService với fencing token chính xác.

### 7.3 Submit Final — p99 < 500ms, critical

```
POST /api/v1/attempts/{attemptId}/submit
Headers: Authorization, Idempotency-Key
```

```
1. ACQUIRE Redis lock:submit:{attempt_id} TTL 30s
   Fail → 409 AUTH_LOCK_HELD

2. Load attempt từ PG với FOR UPDATE (row-level lock):
   SELECT * FROM exam_attempts WHERE id=? FOR UPDATE
   Check status='in_progress' hoặc 'expired' (cho force-submit sau expire)

3. Flush answers Redis → PG (best effort catch-up):
   - HGETALL answers:{attempt_id}
   - So với attempt_answers đã có → insert missing với ON CONFLICT (submission_id) DO NOTHING
   (Trong design v2: bước này hầu như no-op vì §6.3 đã ghi PG sync — chỉ edge case Redis có mà PG miss)

4. Auto-grade tất cả câu auto-gradable (sync, total budget 200ms):
   Fan-out theo question.type:
   ├─ multiple_choice_*, true_false, ordering, matching, drag_drop, hotspot
   │   → AutoGrader (sync, < 10ms/question)
   ├─ fill_blank
   │   → FuzzyMatchGrader: confidence >= 0.75 → auto; < 0.75 → needs_manual=true
   └─ essay, short_answer, code_execution
       → KHÔNG grade sync; skip (async AI fan-out bước 6)

5. @Transactional (state_version fencing):
   a. UPDATE attempt_answers SET is_correct=?, points_earned=?, grading_method='auto'
        WHERE attempt_id=? AND exam_question_id=? AND is_correct IS NULL
   b. UPDATE exam_attempts SET
        raw_score = (SELECT SUM(points_earned) FROM attempt_answers WHERE attempt_id=? AND is_correct IS NOT NULL),
        submitted_at = NOW(),
        status = 'submitted',
        time_spent_seconds = NOW() - started_at,
        state_version = state_version + 1
      WHERE id=? AND state_version=?   -- optimistic lock
      IF affected_rows = 0:
        ROLLBACK → 409 STALE_STATE_VERSION (CDS đã suspend giữa chừng)
   c. INSERT outbox (exam.attempt.submitted.v1, payload=..., partition_key=attempt_id)
   d. Nếu tất cả answer đã graded (không còn pending AI):
        UPDATE ... SET status='graded', graded_at=NOW(), passed=..., percentage_score=...
        INSERT outbox (exam.attempt.graded.v1, ...)
      Else:
        -- Status vẫn 'submitted'; GradingAggregator consumer sẽ transition → 'graded'
   e. COMMIT

6. Fan-out async (ngoài TX, không block):
   Foreach pending AI answer:
     INSERT outbox (grading.request.v1, partition_key=attempt_answer_id)
     (AI Service consume, publish grading.result.v1 sau 30-60s / 2-30s code)

7. Redis cleanup (fire-and-forget):
   DEL session:{attempt_id}, answers:{attempt_id}, q_order:{attempt_id}, adaptive:{attempt_id}
   SREM exam:concurrent:{exam_id} {attempt_id}

8. Release lock

9. Return 200 OK
{
  "status": "submitted",  (hoặc "graded" nếu không còn pending)
  "state_version": 13,
  "grading": {
    "raw_score": 85.0,
    "max_score": 100.0,
    "percentage_score": 85.0,
    "passed": true,
    "pending_ai": 2   // còn 2 câu essay chờ AI
  },
  "result_url": "/api/v1/attempts/{id}/result"
}
```

### 7.4 Expire Attempt — background job

```java
@Scheduled(fixedDelay = 30_000)
@SchedulerLock(name = "expire-attempts", lockAtMostFor = "25s", lockAtLeastFor = "10s")
void expireInProgressAttempts() {
    List<AttemptId> expired = repo.findExpiredInProgress(Instant.now().minusSeconds(5)); // 5s network grace
    for (AttemptId id : expired) {
        try {
            submitUseCase.execute(id, SubmitSource.AUTO_EXPIRED);
        } catch (StateVersionConflict e) {
            log.info("attempt {} state changed during expire sweep — skip", id);
        }
    }
}
```

ShedLock qua PG advisory lock đảm bảo chỉ 1 pod chạy job này tại 1 thời điểm.

### 7.5 Suspend — consume `cheat.alert.generated.v1`

```java
@KafkaListener(topics = "cheat.alert.generated.v1", groupId = "exam-service-cheat")
@Transactional
public void onCheatAlert(ConsumerRecord<String, GenericRecord> rec, Acknowledgment ack) {
    CheatAlert alert = avroMapper.fromRecord(rec.value());
    // Dedupe
    if (!processedEvents.markProcessed(alert.eventId(), "exam-service-cheat", rec.topic())) {
        ack.acknowledge();  // already handled
        return;
    }
    if (alert.autoAction() != SUSPEND || alert.riskScore() < 60) {
        ack.acknowledge();
        return;
    }

    // State transition with fencing.
    // alert.stateVersionAtDetection() = Avro field `state_version_at_detection` (CDS payload contract).
    // Local param renamed `expectedStateVersion` để align với Exam Service API semantic.
    try {
        suspendAttemptUseCase.execute(alert.attemptId(), alert.stateVersionAtDetection(), alert.reason());
    } catch (StateVersionConflict e) {
        log.warn("suspend skipped — state_version drift attempt={} expected={} actual={}",
                 alert.attemptId(), e.expected(), e.actual());
        outbox.publish("exam.attempt.suspend_skipped.v1", alert.attemptId().toString(), Map.of(
            "attempt_id", alert.attemptId(),
            "reason", "STALE_STATE_VERSION",
            "expected_version", e.expected(),
            "actual_version", e.actual()
        ), alert.attemptId().toString());
    }
    ack.acknowledge();
}
```

Proctor có thể resume (`POST /attempts/{id}/resume`) hoặc terminate (`POST /attempts/{id}/terminate` — set score=0).

---

## VIII. GRADING PIPELINE

### 8.1 Phân loại theo khả năng auto-grade

| Type | Auto? | Engine | Độ trễ budget |
| ---- | ----- | ------ | ------------- |
| multiple_choice_single | ✅ sync | Set equal | < 5ms |
| multiple_choice_multi | ✅ sync | Set equal | < 5ms |
| true_false | ✅ sync | Boolean | < 1ms |
| matching | ✅ sync | Pair map | < 10ms |
| ordering | ✅ sync | Array compare | < 10ms |
| drag_drop | ✅ sync | Map compare | < 10ms |
| hotspot | ✅ sync | Point-in-polygon | < 10ms |
| fill_blank | ✅ sync (confidence ≥ 0.75) | Regex/fuzzy (Levenshtein) | < 20ms |
| short_answer | ⚠️ AI-assist | Embedding + keyword | async 200-500ms |
| essay | ❌ async | LLM + rubric (AI Service) | 30-60s |
| code_execution | ❌ async | gVisor sandbox (AI Service) | 2-30s |

### 8.2 Fan-out

```
          POST /submit
              │
              ▼
       ┌──────────────┐
       │ Grader router│ (AutoGraderRegistry)
       └──┬─────┬─────┘
    sync  │     │  async
          ▼     ▼
  ┌────────────┐  ┌──────────────────────────┐
  │AutoGrader  │  │outbox INSERT              │
  │(in-process)│  │topic=grading.request.v1   │
  └────────────┘  └────────┬─────────────────┘
                           │
                  Relayer publish Kafka
                           │
                           ▼
                      AI Service (Python)
                           │
                           ▼
                   Kafka grading.result.v1
                           │
                           ▼
              GradingResultConsumer (idempotent via processed_events)
                           │
                           ▼
                 UPDATE attempt_answers
                 IF tất cả graded:
                   UPDATE exam_attempts.status='graded' + outbox exam.attempt.graded.v1
```

### 8.3 AutoGrader implementations

```java
public interface AutoGrader {
    boolean supports(QuestionType type);
    GradingResult grade(Question question, AnswerPayload payload);
}

@Component
public class MultipleChoiceSingleGrader implements AutoGrader {
    public boolean supports(QuestionType t) { return t == MULTIPLE_CHOICE_SINGLE; }

    public GradingResult grade(Question q, AnswerPayload p) {
        List<String> selected = p.get("selected_options").asList();
        if (selected.size() != 1) return GradingResult.incorrect(q.points());
        Optional<Option> correct = q.options().stream().filter(Option::isCorrect).findFirst();
        boolean isRight = correct.isPresent() && correct.get().id().equals(selected.get(0));
        return new GradingResult(
            isRight ? q.points() : Score.ZERO,
            isRight, /*needsManual*/ false, /*method*/ "auto"
        );
    }
}
```

Graders register vào `AutoGraderRegistry`; router chọn theo `question.type`. Test unit-level per grader + integration `SubmitFinalGradingIT`.

### 8.4 GradingResultConsumer — aggregate final score

```java
@KafkaListener(topics = "grading.result.v1", groupId = "exam-service-grading")
@Transactional
public void onGradingResult(ConsumerRecord<String, GenericRecord> rec, Acknowledgment ack) {
    GradingResult r = avroMapper.fromRecord(rec.value());
    if (!processedEvents.markProcessed(r.eventId(), "exam-service-grading", rec.topic())) {
        ack.acknowledge(); return;
    }
    // UPDATE attempt_answers
    answerRepo.updateGrading(r.attemptAnswerId(), r.isCorrect(), r.pointsEarned(),
                             r.gradingMethod(), r.graderComment());
    // Check all graded
    ExamAttempt attempt = attemptRepo.findById(r.attemptId()).orElseThrow();
    if (answerRepo.allGraded(r.attemptId())) {
        attempt.finalizeGrade(clock);   // status=graded, compute percentage + passed
        attemptRepo.save(attempt);       // @Version bump
        outbox.publish("exam.attempt.graded.v1", attempt.id().toString(), payload(attempt), attempt.id().toString());
    }
    ack.acknowledge();
}
```

---

## IX. ADAPTIVE EXAM (IRT 3PL)

### 9.1 Model

```
P(correct | θ, a, b, c) = c + (1 - c) / (1 + exp(-a * (θ - b)))
```

- `θ` (theta): năng lực học sinh
- `a`: độ phân biệt (discrimination)
- `b`: độ khó (difficulty)
- `c`: xác suất đoán đúng ngẫu nhiên (guessing)

Params `a, b, c` pin per question.version (Question Service own). Calibration do Analytics batch job (xem `analytics-service-design.md`).

### 9.2 Flow

```
Start: θ₀ = 0, SE = 1.0, answered = {}

Mỗi câu:
  1. Chọn q* trong pool chưa answer, không vi phạm topic constraints:
       q* = argmax_q  I(θ, q) = a² * P * (1-P)     (Fisher information)
  2. Trả câu cho client (stripped)
  3. Client answer → server grade (AutoGrader sync)
  4. Update θ via Newton-Raphson MLE:
       L(θ) = Π P(θ,a,b,c)^y * (1-P)^(1-y)
       θ_new = θ_old + L'(θ)/L''(θ)   (1 iter đủ với priors)
  5. Update SE = 1/sqrt(Σ I(θ, q_i))
  6. Dừng khi:
       - SE < 0.30 (ước tính đủ tin cậy)
       - Đủ max_questions cấu hình
       - Hết giờ
  7. Điểm cuối: transform θ → T-score (mean=50, sd=10) hoặc percentile
```

### 9.3 Triển khai

```java
@Component
public class AdaptiveSelector {

    // `adaptive:{attempt_id}` là Hash theo database/redis/schema.md Nhóm 1.
    // Fields: theta, se, answered_ids (CSV), next_q_pool (CSV).
    public QuestionRefId nextQuestion(AttemptId attemptId, double theta) {
        Map<String, String> st = redis.opsForHash().entries("adaptive:" + attemptId);
        Set<String> answered   = parseCsv(st.getOrDefault("answered_ids", ""));
        List<String> pool      = parseCsvOrdered(st.getOrDefault("next_q_pool", ""));

        return pool.stream()
            .filter(q -> !answered.contains(q))
            .filter(q -> topicConstraintsOk(attemptId, q))
            .map(q -> questionCache.get(q))
            .max(Comparator.comparingDouble(q -> fisherInfo(theta, q.irt())))
            .map(Question::refId)
            .orElseThrow(PoolExhausted::new);
    }

    double fisherInfo(double theta, IrtParams p) {
        double pTheta = prob3PL(theta, p.a(), p.b(), p.c());
        return Math.pow(p.a(), 2) * pTheta * (1 - pTheta);
    }

    public ThetaUpdate updateTheta(double prior, List<Response> responses) {
        // Newton-Raphson 1-iter
        double num = 0, den = 0;
        for (Response r : responses) {
            double p = prob3PL(prior, r.a(), r.b(), r.c());
            num += r.a() * ((r.correct() ? 1 : 0) - p);
            den -= Math.pow(r.a(), 2) * p * (1 - p);
        }
        double thetaNew = prior + num / den;
        double se = 1.0 / Math.sqrt(-den);
        return new ThetaUpdate(thetaNew, se);
    }
}
```

### 9.4 Fallback khi Redis `adaptive:*` mất

Rebuild từ `attempt_answers` (đã graded) trong PG: load responses + recompute θ từ θ₀=0. Overhead ~50ms cho 20 response. UX có thể giật 1 nhịp — acceptable (rare).

---

## X. WEBSOCKET PROTOCOL

### 10.1 Connection

```
WSS /api/v1/ws/attempts/{attempt_id}
Headers: Authorization: Bearer <JWT>
```

> **⚠️** Literal path luôn có prefix `/api/v1/`. Bảng shorthand §12.2 viết `/ws/attempts/{id}` cho gọn, nhưng **client + OpenAPI + CORS whitelist phải dùng full `/api/v1/ws/...`** (§12.0.1).

Handshake: verify JWT → check user owns attempt_id AND status='in_progress'/'suspended' → accept. STOMP subprotocol với heartbeat 30s.

### 10.2 Message types (STOMP frames, JSON body)

**Server → Client:**

| Destination | Payload | Khi nào gửi |
| ----------- | ------- | ----------- |
| `/attempt/{id}/timer` | `{remaining_ms, server_time, expires_at}` | Mỗi 10s + khi client subscribe |
| `/attempt/{id}/timer-warning` | `{remaining_ms, level: "5min"\|"1min"\|"30s"}` | Khi còn 5p/1p/30s |
| `/attempt/{id}/cheat-warning` | `{type, message, severity}` | Khi CDS publish `cheat.alert.generated.v1` với severity=low/medium |
| `/attempt/{id}/suspended` | `{reason, state_version}` | Khi state transition → suspended |
| `/attempt/{id}/auto-submitted` | `{reason: "timeout"\|"terminated"}` | Khi server force submit |
| `/attempt/{id}/force-navigate` | `{question_index}` | Adaptive: server yêu cầu nhảy câu tiếp theo |
| `/ping` | `{server_time}` | Keepalive mỗi 30s |

**Client → Server:**

| Destination | Payload | Tác dụng |
| ----------- | ------- | -------- |
| `/attempt/heartbeat` | `{attempt_id, client_time, current_q_idx}` | Update `session.last_heartbeat` (không phải check answer — dùng POST /answers cho answer) |
| `/attempt/cheat-event` | `{event_type, event_data}` | Forward sang Kafka `cheat.event.raw.v1` (CDS L1-L3 xử lý) |
| `/pong` | `{}` | Response ping |

### 10.3 Scale pattern — 100k WS

**Vấn đề:** 20 pod × 5k WS/pod. Khi CDS cần push `cheat.warning` đến 1 attempt, pod không biết attempt đó ở pod nào.

**Giải pháp:**
- **Sticky session** ở ingress (client hash qua cookie `sq-exam-route`)
- Mỗi pod giữ local registry `Map<AttemptId, WebSocketSession>`
- Pod subscribe Redis pub/sub channel `ws:exam:{attempt_id}` khi WS accept
- Unsubscribe khi WS close

```
Pod A:
   client1 (attempt X) ─┐
                        ├─► WS connections
   client2 (attempt Y) ─┘

   Redis SUBSCRIBE ws:exam:X, ws:exam:Y

CheatConsumer (any pod):
   On cheat.alert.generated.v1 → Redis PUBLISH ws:exam:X {"type":"cheat.warning", ...}

Pod A receives via Redis sub → forward to local session
```

**Pod crash handling:** client auto-reconnect (exp backoff 1s/2s/4s/8s/16s); ingress route sang pod mới; pod mới load attempt state từ PG/Redis + subscribe Redis channel. Timer vẫn đúng (authoritative server).

### 10.4 Disconnect handling

- Client lose → server **không pause**; attempt vẫn chạy giờ.
- Reconnect: client gọi `GET /api/v1/attempts/{id}/snapshot` trước WS → sync state (current_q_idx, answered, remaining).
- Pod crash: lose local WS; clients reconnect qua ingress → pod khác; state rebuild từ PG+Redis.

---

## XI. EVENTS — TRANSACTIONAL OUTBOX + AVRO

> **⚠️ Nguồn truth topic name + schema: `shared-contracts/avro/TOPICS.md`** (catalog) + `shared-contracts/avro/exam/*.avsc` (schema). Bảng §11.1 là **view tóm tắt**. Khi lệch, file shared-contracts thắng. PR đổi topic phải update **cả 2 nơi + CLAUDE.md §8** trong cùng commit.

Tuân thủ **ADR-001** + CLAUDE.md §3:

- **Không** gọi `kafkaTemplate.send()` trực tiếp từ UseCase. State change → INSERT `outbox` trong **cùng transaction** với domain mutation (bảng ở `schema.sql` §13).
- Relayer (leader-elected qua `pg_try_advisory_lock`) poll `SELECT ... FOR UPDATE SKIP LOCKED`, publish Kafka, `UPDATE published_at`.
- Payload encode **Avro**, schema publish Apicurio với compat mode `BACKWARD`. Schema file `shared-contracts/avro/exam/*.avsc`.

### 11.1 Phân loại event theo độ quan trọng (ADR-001 §3)

**Critical — BẮT BUỘC qua outbox** (drive state change ở service khác):

| Topic (v1) | Partition key | Payload (Avro record) | Consumer |
| ---------- | ------------- | --------------------- | -------- |
| `exam.exam.published.v1` | `exam_id` | `{exam_id, org_id, starts_at, ends_at, type, total_points, version, published_at}` | Analytics (dim cache), Cheating (warm baseline), Notification (enrollee email) |
| `exam.exam.completed.v1` | `exam_id` | `{exam_id, org_id, ended_at, total_attempts, completed_at}` | Analytics (batch aggregate), Cheating (trigger L6 Flink job) |
| `exam.attempt.started.v1` | `attempt_id` | `{attempt_id, exam_id, user_id, org_id, started_at, expires_at, ip, geo, ua, device_fingerprint}` | Analytics (stream), Cheating (baseline load), Proctoring |
| `exam.answer.submitted.v1` | `attempt_id` | `{attempt_id, exam_question_id, question_ref_id, submission_id, answer_data (JSON bytes), ts, state_version}` | Cheating (L4 answer pattern), Analytics (`answer_analytics`), Grading router |
| `exam.attempt.submitted.v1` | `attempt_id` | `{attempt_id, exam_id, user_id, submitted_at, raw_score?, time_spent_sec, state_version}` | Analytics (`exam_facts` INSERT preliminary), Notification, Cheating (trigger L6) |
| `exam.attempt.graded.v1` | `attempt_id` | `{attempt_id, raw_score, max_score, percentage_score, passed, graded_at, state_version}` | Analytics (`exam_facts` UPSERT final), Certificate, Notification |
| `exam.attempt.suspended.v1` | `attempt_id` | `{attempt_id, reason, risk_score, suspended_at, state_version}` | Notification, Audit, Cheating (log) |
| `exam.attempt.suspend_skipped.v1` | `attempt_id` | `{attempt_id, reason: STALE_STATE_VERSION\|ALREADY_TERMINAL, expected_version, actual_version, ts}` | Cheating Service (log warning, không retry) |
| `exam.attempt.completed.v1` | `attempt_id` | `{attempt_id, exam_id, user_id, terminal_status: enum{submitted,graded,expired,cancelled,terminated}, completed_at, state_version}` | **Cheating Service** (trigger L6 Flink per-attempt — `cheating-detection-service-design.md` §1175), Proctoring (finalize session) |
| `grading.request.v1` | `attempt_answer_id` | `{attempt_answer_id, attempt_id, question_ref_id, question_type, answer_data, rubric?, model_hint?}` | AI Service (Python), Code Runner |

**Lưu ý co-publish `exam.attempt.completed.v1`**: trong cùng TX với transition → terminal state (§5.2), ngoài event riêng (`submitted.v1`/`graded.v1`/etc.) phải **INSERT thêm outbox row** cho `exam.attempt.completed.v1`. Consumer CDS chỉ subscribe 1 topic aggregator → đơn giản hơn subscribe 4 topic riêng.

**Fire-and-forget — KHÔNG qua outbox** (miss chấp nhận, volume cao):

| Topic (v1) | Key | Payload | Consumer |
| ---------- | --- | ------- | -------- |
| `exam.ws.heartbeat.v1` | `attempt_id` | `{attempt_id, current_q_idx, last_heartbeat, ts}` | Analytics (user activity — sample 1/10) |
| `cheat.event.raw.v1` | `attempt_id` | `{attempt_id, user_id, event_type, event_data, client_timestamp, server_timestamp}` | Cheating Service L1-L3 layer (`cheating-detection-service-design.md`). WS handler forward client cheat signals (tab switch, keystroke anomaly, paste, etc.). Miss acceptable vì CDS compensate bằng L4-L5. |
| `exam.exam.activated.v1` / `exam.exam.archived.v1` | `exam_id` | `{exam_id, ts}` | Analytics (state trace) |

### 11.2 Code pattern — outbox publisher

**Encoding strategy:** outbox `payload JSONB` ở PG (dễ debug); relayer encode Avro khi publish (Apicurio `SerdeConfig` resolve schema theo `topic + eventType + schema_version`).

**Propagation rule (critical — align auth-service §11.2):**

- **UseCase** caller: `@Transactional(propagation = REQUIRED)` mở TX
- **ExamOutboxPublisher**: `@Transactional(propagation = MANDATORY)` — fail-fast nếu không trong TX

```java
@Service
@Transactional(propagation = Propagation.MANDATORY)
class ExamOutboxPublisher {
    private final OutboxRepository repo;
    private final ObjectMapper jsonMapper;

    public void publish(String topic, String eventType,
                        String aggregateId, Object payload, String partitionKey) {
        OutboxRow row = new OutboxRow(
            UUID.randomUUID(),
            "exam_attempt",
            aggregateId,
            topic,
            eventType,
            jsonMapper.valueToTree(payload),
            Map.of("trace_id", MDC.get("trace_id"), "schema_version", "1"),
            partitionKey
        );
        repo.save(row);
    }
}

// UseCase — BẮT BUỘC @Transactional
@Service
class SubmitAnswerUseCase {
    @Transactional
    public SubmitAnswerResult execute(AttemptId id, QuestionRefId qid, AnswerPayload p, UUID sid) {
        // Idempotency via UNIQUE(submission_id)
        int affected = answerRepo.insertIfAbsent(id, qid, p, sid);
        if (affected == 0) return cachedResponseFor(sid);
        attemptRepo.touch(id);                     // @Version bump
        outbox.publish(
            "exam.answer.submitted.v1", "AnswerSubmitted",
            id.toString(),
            new AnswerSubmittedEvent(...),
            id.toString()                           // partition by attempt_id
        );
        return SubmitAnswerResult.saved(sid);
    }
}

// Relayer — leader-elect, async publish, per-row error isolation, batch budget 3s
@Component
class ExamOutboxRelayer implements SmartLifecycle {
    private static final long BATCH_BUDGET_MS = 3_000;
    private static final int  BATCH_SIZE      = 500;
    private final KafkaTemplate<String, GenericRecord> kafka;
    private volatile boolean running;

    @Scheduled(fixedDelay = 100)
    void pollAndPublish() {
        if (!running || !leaderLock.tryAcquire()) return;
        try {
            long deadline = System.currentTimeMillis() + BATCH_BUDGET_MS;
            List<OutboxRow> batch = repo.claimPending(BATCH_SIZE);
            List<CompletableFuture<Void>> inflight = new ArrayList<>(batch.size());

            for (OutboxRow row : batch) {
                if (System.currentTimeMillis() > deadline) break;
                inflight.add(publishOne(row));
            }
            long waitMs = Math.max(0, deadline - System.currentTimeMillis());
            CompletableFuture.allOf(inflight.toArray(new CompletableFuture[0]))
                .orTimeout(waitMs, MILLISECONDS)
                .exceptionally(ex -> { log.warn("batch partial timeout", ex); return null; })
                .join();
        } finally {
            leaderLock.release();
        }
    }

    private CompletableFuture<Void> publishOne(OutboxRow row) {
        try {
            GenericRecord avro = avroMapper.toAvro(row);
            ProducerRecord<String, GenericRecord> rec = new ProducerRecord<>(
                row.topic(), row.partitionKey(), avro);
            rec.headers().add("trace_id", row.headers().get("trace_id").getBytes(UTF_8));
            rec.headers().add("event_id", row.eventId().toString().getBytes(UTF_8));
            return kafka.send(rec).completable()
                .thenAccept(meta -> repo.markPublished(row.eventId()))
                .exceptionally(ex -> {
                    repo.markFailure(row.eventId(), trim(ex.getMessage(), 500));
                    metrics.publishFailed.increment(row.topic(), classify(ex));
                    return null;
                });
        } catch (Exception serde) {
            repo.markFailure(row.eventId(), "serde: " + trim(serde.getMessage(), 400));
            metrics.publishFailed.increment(row.topic(), "serde");
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override public void stop()  { running = false; kafka.flush(); }  // graceful SIGTERM
    @Override public void start() { running = true; }
    @Override public boolean isRunning() { return running; }
}
```

### 11.3 Consumed topics

| Topic | Group | Hành động |
| ----- | ----- | --------- |
| `cheat.alert.generated.v1` | `exam-service-cheat` | Suspend attempt nếu `auto_action=SUSPEND` + state_version khớp (§7.5) |
| `grading.result.v1` | `exam-service-grading` | UPDATE attempt_answers, aggregate điểm, transition → graded (§8.4) |
| `question.updated.v1` | `exam-service-questions` | Invalidate cache `exam:q_ids:*`, `question:*` |
| `auth.role.changed.v1` | `exam-service-auth` | Invalidate Redis user permission cache (60s) |
| `auth.user.deleted.v1` | `exam-service-user-deletion` | Anonymize attempt (GDPR) — set user_id = placeholder anonymous, giữ scores cho analytics |

Consumer dedupe qua `processed_events(event_id, consumer_group)` (schema §13). Pattern:

```java
@Transactional
public void handle(...) {
    if (!processedEventsRepo.markProcessed(eventId, groupId, topic)) return;  // idempotent no-op
    /* business logic */
}
```

### 11.4 Avro schema convention

- File: `shared-contracts/avro/exam/exam.answer.submitted.v1.avsc`
- Namespace: `vn.smartquiz.exam.v1`
- Rule BACKWARD: chỉ được add field với default; không remove/rename; không đổi type.
- Breaking change → topic `.v2`, service tự migrate dần.
- CI gate: `./gradlew :shared-contracts:avroCompatCheck`.

### 11.5 Producer/Relayer config

| Setting | Value | Lý do |
| ------- | ----- | ----- |
| `acks` | `all` | Đợi ISR replicate — không mất event |
| `enable.idempotence` | `true` | Chống duplicate khi retry |
| `max.in.flight.requests.per.connection` | `5` | + idempotence giữ order per partition |
| `compression.type` | `zstd` | Giảm network cost |
| `delivery.timeout.ms` | `30000` | Broker slow → fail fast, mark last_error |
| `request.timeout.ms` | `5000` | Mỗi request ≤ 5s |
| Poll interval relayer | `100ms` fixedDelay | RPO ≤ 5s |
| Batch budget relayer | `3000ms` wall-clock | Leave rest cho poll tiếp |
| Batch size relayer | `500 rows / poll` max | `claimPending` cap |
| `spring.lifecycle.timeout-per-shutdown-phase` | `30s` | SIGTERM → drain inflight + `kafka.flush()` |

### 11.6 Metrics outbox bắt buộc (ADR-001 §impl)

| Metric | Alert |
| ------ | ----- |
| `exam_outbox_pending_size` (gauge) | warning > 1k, critical > 10k |
| `exam_outbox_publish_lag_seconds` (histogram) | p99 > 5s = **critical (RPO violation)** |
| `exam_outbox_publish_failed_total{reason}` (counter) | spike > 10/min → page |

### 11.7 Outbox table partition plan

Volume ước tính peak: 10k concurrent × 30 answer/60m = 5k/min = 300k/h rows `exam.answer.submitted.v1`. Nếu chạy 10 giờ peak → 3M rows/day. Kế hoạch:

- MVP: không partition (đủ với 1M rows < 10GB).
- Phase 2 (khi > 10M rows): `PARTITION BY RANGE (created_at)` day-level + `pg_partman` auto create + drop partition > 7 days. Relayer phải query theo partition mới nhất.

---

## XII. API ENDPOINTS CHI TIẾT

### 12.0 API conventions

Bảng §12.1-12.5 chỉ là tóm tắt. **Contract đầy đủ ở OpenAPI spec** `app/src/main/resources/static/openapi.yaml` (§18.1 prereq). Mọi endpoint **phải** tuân convention dưới. §12.6 có template 2 endpoint điển hình.

#### 12.0.1 Versioning

- Tất cả REST có prefix `/api/v1/`. Trong **bảng** tóm tắt dùng shorthand `/exams/{id}` = `/api/v1/exams/{id}`.
- **Rule shorthand vs literal**: mọi chỗ **literal path vào config / header / cookie / redirect / CORS / OpenAPI** phải dùng full `/api/v1/...`. WS path cũng có prefix: `/api/v1/ws/attempts/{id}`.
- **Endpoint chuẩn không versioned**: `/actuator/*`, `/.well-known/*` (nếu có).
- **Breaking change**: bump → `/api/v2/`, song song ≥ 6 tháng, header `Sunset: <date>` (RFC 8594).
- **Content negotiation**: `Accept: application/json` default hoặc `Accept: application/vnd.smartquiz.v1+json`.
- **gRPC**: package `vn.smartquiz.exam.v1`.

#### 12.0.2 Content-Type & encoding

- Request: `Content-Type: application/json; charset=utf-8`. Khác → `415 Unsupported Media Type`.
- Response: luôn `application/json; charset=utf-8` kể cả error (RFC 7807).
- Không trailing slash — `308 Permanent Redirect` về canonical.
- Request body cap **1 MB** (trừ `grading.request.v1` đi Kafka, không qua REST). Vượt → `413`.

#### 12.0.3 Naming + formats

| Thành phần | Rule | Ví dụ |
| ---------- | ---- | ----- |
| Path segment | lowercase, hyphen | `/exams/{id}/questions`, `/attempts/{id}/submit` |
| Query param | lowercase, hyphen | `?org-id=&status=&page-size=50` |
| JSON field | **snake_case** | `attempt_id`, `question_ref_id`, `state_version` |
| JSON enum | lowercase snake | `"status": "in_progress"` |
| HTTP header custom | `X-` prefix, kebab | `X-Request-Id`, `X-Device-Fingerprint` |
| UUID | canonical lowercase | `550e8400-e29b-41d4-a716-446655440000` |
| Timestamp | **ISO 8601 UTC with Z** | `"2026-04-22T10:05:22.123Z"` |
| Duration body | seconds int hoặc ISO 8601 | `"remaining_seconds": 900` hoặc `"time_limit": "PT60M"` |
| Missing vs null | Missing = "không nói"; `null` = "field tồn tại, unset" | — |

#### 12.0.4 Status code

| Code | Dùng khi | Body |
| ---- | -------- | ---- |
| `200 OK` | GET OK, POST mutation trả resource | JSON |
| `201 Created` | POST tạo (start attempt, create exam) | JSON + `Location` header |
| `202 Accepted` | Async (submit → grading pending) | JSON `{status, result_url}` |
| `204 No Content` | DELETE, cancel | — |
| `400 Bad Request` | JSON parse fail, required header thiếu | RFC 7807 |
| `401 Unauthorized` | JWT thiếu/sai | RFC 7807 + `WWW-Authenticate: Bearer` |
| `403 Forbidden` | Thiếu permission / password sai / IP not allowed | RFC 7807 |
| `404 Not Found` | Exam/attempt không tồn tại | RFC 7807 |
| `409 Conflict` | State conflict (in-progress tồn tại, stale state_version, already submitted, idempotency-key conflict) | RFC 7807 |
| `410 Gone` | Attempt đã expired / submitted không cho thao tác nữa | RFC 7807 |
| `415` | Content-Type không hỗ trợ | RFC 7807 |
| `422 Unprocessable Entity` | Semantic validation fail (payload shape sai với question.type) | RFC 7807 + `errors[]` field-level |
| `423 Locked` | Attempt đã bị suspend | RFC 7807 |
| `429 Too Many Requests` | Rate limit hit | RFC 7807 + `Retry-After` + `X-RateLimit-*` |
| `500` | Unhandled | RFC 7807 chỉ `trace_id`, không leak stack |
| `503 Service Unavailable` | Upstream dep down (Question gRPC, Kafka, circuit breaker open) | RFC 7807 + `Retry-After` |

**400 vs 422**: 400 cho syntax (JSON broken); 422 cho semantic (payload shape sai với type).

#### 12.0.5 Error response — RFC 7807

Tất cả ≥ 400 trả:

```json
{
  "type": "https://smartquiz.vn/errors/stale-state-version",
  "title": "Trạng thái đã thay đổi",
  "status": 409,
  "code": "STALE_STATE_VERSION",
  "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
  "timestamp": "2026-04-22T10:05:22Z",
  "detail": "Expected state_version=12 but current=13. Attempt was suspended by proctor at 10:05:21."
}
```

422 có thêm `errors[]`:

```json
{
  "type": "https://smartquiz.vn/errors/validation-failed",
  "title": "Dữ liệu không hợp lệ",
  "status": 422,
  "code": "ANSWER_INVALID_PAYLOAD",
  "trace_id": "...",
  "timestamp": "...",
  "errors": [
    { "field": "answer_data.selected_options",
      "code": "INVALID_CARDINALITY",
      "message": "multiple_choice_single requires exactly 1 selected option, got 2" }
  ]
}
```

#### 12.0.6 Idempotency

POST mutation **phải** hỗ trợ `Idempotency-Key` header (RFC draft-ietf-httpapi-idempotency-key):

- Format: UUID v4 client-sinh.
- Server cache response (status + body) Redis `idempotency:{key}:{user_id}` TTL **24h**.
- Replay cùng key trong 24h → trả cached response, **không** re-execute.
- Conflict (cùng key, body khác) → `409 IDEMPOTENCY_KEY_REUSE_DIFFERENT_BODY`.

**Bắt buộc idempotency:** `/exams/{id}/start`, `/attempts/{id}/submit`, `/attempts/{id}/suspend`, `/attempts/{id}/resume`, `/attempts/{id}/terminate`, `/attempts/{id}/regrade`, `/exams/{id}/publish`, `/exams/{id}/archive`.

**Không cần (natively idempotent):**
- `POST /attempts/{id}/answers` — `submission_id` trong body đã làm dedupe key (UNIQUE index); client không cần `Idempotency-Key`.
- `DELETE /exams/{id}` — soft delete, re-apply no-op.

**Lý do tách:** `submission_id` là nghiệp vụ (1 answer = 1 submission), `Idempotency-Key` là kỹ thuật (HTTP retry). `/answers` có volume cao → client đỡ thêm 1 header; check `submission_id` rẻ hơn (UNIQUE index sẵn).

#### 12.0.7 Rate limit headers

Endpoint có rate limit (§13.2) trả header:

```
X-RateLimit-Limit: 200
X-RateLimit-Remaining: 187
X-RateLimit-Reset: 2026-04-22T10:06:00Z
Retry-After: 60                           # chỉ khi 429, unit = seconds
```

Body 429:

```json
{
  "type": "https://smartquiz.vn/errors/rate-limit",
  "title": "Quá nhiều request",
  "status": 429,
  "code": "EXAM_RATE_LIMIT",
  "retry_after": 60,
  "limit": 200,
  "window": "1m",
  "trace_id": "...",
  "timestamp": "..."
}
```

#### 12.0.8 Pagination (cursor-based)

Convention: `?cursor=<opaque>&limit=50`. `limit` max **100**, default **20**.

Response:

```json
{
  "items": [ ... ],
  "page_info": {
    "next_cursor": "eyJpZCI6IjEyMzQifQ==",
    "has_next": true
  }
}
```

Không dùng offset-based (trùng/thiếu khi concurrent insert). Exam list endpoints: `/exams`, `/exams/{id}/attempts`, `/admin/audit/exam`.

#### 12.0.9 Standard request headers

Client **nên** gửi:

| Header | Purpose | Required |
| ------ | ------- | -------- |
| `Authorization: Bearer <jwt>` | Authenticated endpoint | Có |
| `X-Request-Id: <uuid>` | Client tracking; echo response | Không — server sinh nếu thiếu |
| `Idempotency-Key: <uuid>` | POST mutation (§12.0.6) | Có — endpoint liệt kê |
| `X-Device-Fingerprint: <sig>` | Anti-cheat + reconnect match | Browser client tự sinh |
| `Accept-Language: vi,en;q=0.8` | i18n | Không — default `vi` |

Server **luôn** trả:

| Header | Nội dung |
| ------ | -------- |
| `X-Request-Id` | Echo hoặc mới sinh |
| `X-Trace-Id` | OTel trace id (§14.2) |
| `Content-Type: application/json; charset=utf-8` | — |
| `Cache-Control: no-store` | Default mọi endpoint (trừ `/.well-known/*`) |
| `Strict-Transport-Security`, `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, … | §13.7 |

#### 12.0.10 No response envelope

Response **không wrap** `{data: ...}`. GET resource trả resource trực tiếp; error RFC 7807. Ngoại lệ list endpoint: `items + page_info` là Page<T> resource, không phải envelope.

### 12.1 Exam management (instructor/admin)

🔒 = sudo mode required (recent-auth ≤ 5 phút, reference `auth-service-design.md` §5.1.1). 🆔 = Idempotency-Key bắt buộc.

| Method | Path | Body | Response | Quyền | Flags |
| ------ | ---- | ---- | -------- | ----- | ----- |
| POST | `/exams` | ExamCreateDto | `201 {exam_id}` | `exam.create` | 🆔 |
| GET | `/exams?org-id=&status=&q=&cursor=&limit=` | — | `200 {items, page_info}` | `exam.read` | — |
| GET | `/exams/{id}` | — | `200` | `exam.read` | — |
| PATCH | `/exams/{id}` | ExamUpdateDto | `200` | `exam.update.own` + owner policy | — |
| POST | `/exams/{id}/publish` | — | `200 {published_at}` | `exam.publish` + owner | 🆔 |
| POST | `/exams/{id}/archive` | — | `204` | `exam.update.any` | 🆔 |
| DELETE | `/exams/{id}` | — | `204` (soft) | `exam.update.own` + owner | — |
| POST | `/exams/{id}/sections` | SectionDto | `201` | `exam.update.own` | — |
| POST | `/exams/{id}/questions` | `{question_ref_id, section_id?, order_index, points}` | `201` | `exam.update.own` | — |
| DELETE | `/exams/{id}/questions/{eqId}` | — | `204` | `exam.update.own` | — |
| POST | `/exams/{id}/enrollments` | `{user_ids: []}` (bulk) | `200 {enrolled, skipped}` | `exam.enroll.students` | 🆔 |
| DELETE | `/exams/{id}/enrollments/{userId}` | — | `204` | `exam.enroll.students` | — |
| GET | `/exams/{id}/analytics` | — | `200` | `exam.analytics` | — |
| GET | `/exams/{id}/attempts?status=&cursor=&limit=` | — | `200 {items, page_info}` | `attempt.read.org` | — |

### 12.2 Attempt (student)

| Method | Path | Body | Response | Quyền | Flags |
| ------ | ---- | ---- | -------- | ----- | ----- |
| POST | `/exams/{id}/start` | `{access_password?}` | `201 {attempt_id, question, expires_at, state_version, ws_url}` | `attempt.start` + enrollment | 🆔 |
| GET | `/attempts/{id}/snapshot` | — | `200 {state_version, current_q_idx, remaining_sec, answered_refs}` | `attempt.read.own` | — |
| POST | `/attempts/{id}/answers` | `{question_ref_id, answer_data, submission_id, client_timestamp}` | `200 {saved, submission_id, state_version}` | `attempt.submit` + owner | — (submission_id đã idempotent) |
| PATCH | `/attempts/{id}/navigate` | `{current_question_index}` | `204` | `attempt.submit` + owner | — |
| POST | `/attempts/{id}/submit` | — | `200 {status, state_version, grading}` | `attempt.submit` + owner | 🆔 |
| GET | `/attempts/{id}/result` | — | `200` | `attempt.read.own` (sau submit) OR `attempt.read.org` | — |
| WS | `/ws/attempts/{id}` | STOMP | Upgrade 101 | `attempt.submit` + owner | — |

### 12.3 Proctor / admin

🔒 = sudo mode. 🆔 = idempotency.

| Method | Path | Body | Quyền | Flags |
| ------ | ---- | ---- | ----- | ----- |
| POST | `/attempts/{id}/suspend` | `{reason, expected_state_version}` | `attempt.suspend` | 🆔 |
| POST | `/attempts/{id}/resume` | `{expected_state_version}` | `attempt.resume` | 🆔 |
| POST | `/attempts/{id}/terminate` | `{reason, expected_state_version}` | `attempt.terminate` | 🔒 🆔 |
| POST | `/attempts/{id}/regrade` | `{reason}` | `attempt.grade` | 🔒 🆔 |
| PATCH | `/attempts/{id}/answers/{answerId}/grade` | `{points_earned, grader_comment}` | `attempt.grade` | — |

### 12.4 gRPC (internal)

```proto
service ExamService {
    rpc GetAttempt(GetAttemptRequest) returns (Attempt);
    rpc GetAttemptBatch(GetAttemptBatchRequest) returns (GetAttemptBatchResponse);
    rpc UpdateRiskScore(UpdateRiskScoreRequest) returns (UpdateRiskScoreResponse);
    rpc SuspendAttempt(SuspendAttemptRequest) returns (SuspendAttemptResponse);
    rpc StreamAttemptEvents(AttemptFilter) returns (stream AttemptEvent);
}

message UpdateRiskScoreRequest {
    string attempt_id = 1;
    int64 expected_state_version = 2;  // fencing token
    int32 new_risk_score = 3;
    string source = 4;                 // "cheating-service"
}

message UpdateRiskScoreResponse {
    enum Result { APPLIED = 0; STATE_CONFLICT = 1; ATTEMPT_TERMINAL = 2; }
    Result result = 1;
    int64 current_state_version = 2;
}
```

Dùng cho:
- Cheating Service update `risk_score` kèm fencing token (§4.1).
- Analytics Service stream event cho OLAP ingestion.
- Proctoring Service suspend/resume.

### 12.5 Rate limit config (Redis sliding window, Lua atomic)

| Endpoint | Key | Giới hạn | Hành động khi vượt |
| -------- | --- | -------- | ------------------ |
| POST /exams/{id}/start | `rate:exam_start:{user_id}` | 3/phút | 429 |
| POST /attempts/{id}/answers | `rate:answers:{attempt_id}` | 200/phút | 429 (50/s peak an toàn) |
| POST /attempts/{id}/submit | `rate:submit:{attempt_id}` | 5/phút | 429 |
| GET /exams?* | `rate:exam_list:{user_id}` | 60/phút | 429 |
| POST /exams | `rate:exam_create:{user_id}` | 10/giờ | 429 |
| POST /exams/{id}/enrollments | `rate:enroll:{user_id}` | 20/giờ | 429 (anti abuse bulk) |
| WS connect | `rate:ws_connect:{user_id}` | 10/phút | 429 (trước khi upgrade) |

Lua script chia sẻ với Auth Service (reference `auth-service-design.md` §9.1).

### 12.6 Endpoint contract template — reference cho OpenAPI generation

2 endpoint điển hình làm **template**. OpenAPI spec đầy đủ ở `app/src/main/resources/static/openapi.yaml` (§18.1 prereq) — **nguồn truth** cho BE/FE codegen.

#### 12.6.1 POST /api/v1/attempts/{attemptId}/answers

**Request:**

```http
POST /api/v1/attempts/a1b2c3d4-.../answers HTTP/2
Host: api.smartquiz.vn
Content-Type: application/json; charset=utf-8
Authorization: Bearer eyJhbGc...
X-Request-Id: 7c9e6f8b-2a3d-4e5f-b1a0-1234567890ab
X-Device-Fingerprint: fp_abc123

{
  "question_ref_id": "q-550e8400-...",
  "answer_data": { "selected_options": ["opt-b"] },
  "submission_id": "550e8400-e29b-41d4-a716-446655440000",
  "client_timestamp": "2026-04-22T10:15:03.123Z"
}
```

**Request JSON Schema:**

```yaml
SubmitAnswerRequest:
  type: object
  required: [question_ref_id, answer_data, submission_id]
  additionalProperties: false
  properties:
    question_ref_id:
      type: string
      maxLength: 36
      pattern: "^q-[0-9a-f-]{36}$"
    answer_data:
      type: object
      description: "Shape theo question.type — xem §4.4. Server validate run-time."
    submission_id:
      type: string
      format: uuid
      description: "UUID v4 client-sinh; UNIQUE dedupe key"
    client_timestamp:
      type: string
      format: date-time
      description: "ISO 8601 UTC; dùng cho cheat detection L4"
```

**Response 200 OK:**

```http
HTTP/2 200
Content-Type: application/json; charset=utf-8
X-Request-Id: 7c9e6f8b-...
X-Trace-Id: 4bf92f3577b34da6a3ce929d0e0e4736
X-RateLimit-Limit: 200
X-RateLimit-Remaining: 187
X-RateLimit-Reset: 2026-04-22T10:16:00Z
Cache-Control: no-store

{
  "saved": true,
  "submission_id": "550e8400-e29b-41d4-a716-446655440000",
  "server_time": "2026-04-22T10:15:03.251Z",
  "state_version": 12
}
```

**Response JSON Schema:**

```yaml
SubmitAnswerResponse:
  type: object
  required: [saved, submission_id, server_time, state_version]
  additionalProperties: false
  properties:
    saved:          { type: boolean, const: true }
    submission_id:  { type: string, format: uuid }
    server_time:    { type: string, format: date-time }
    state_version:  { type: integer, format: int64, minimum: 0 }
```

**Error responses:**

| Status | Code | Khi nào |
| ------ | ---- | ------- |
| 400 | `ANSWER_MALFORMED_REQUEST` | JSON broken, content-type sai |
| 401 | `AUTH_TOKEN_INVALID` / `AUTH_TOKEN_EXPIRED` | JWT |
| 403 | `AUTH_FORBIDDEN` | Không phải owner của attempt |
| 404 | `ATTEMPT_NOT_FOUND` | attempt_id không tồn tại |
| 404 | `ANSWER_UNKNOWN_QUESTION` | question_ref_id không ∈ question_order |
| 409 | `ATTEMPT_ALREADY_SUBMITTED` | status != in_progress |
| 409 | `STALE_STATE_VERSION` | concurrent mutation (rare — thường auto-retry client) |
| 410 | `ATTEMPT_EXPIRED` | now > expires_at + 5s |
| 422 | `ANSWER_INVALID_PAYLOAD` | Shape không khớp question.type — body `errors[]` field-level |
| 423 | `ATTEMPT_SUSPENDED` | CDS đã suspend |
| 429 | `EXAM_RATE_LIMIT` | Vượt 200/phút/attempt |
| 500 | `EXAM_INTERNAL` | — |
| 503 | `EXAM_DEGRADED` | Redis down + PG timeout |

#### 12.6.2 POST /api/v1/attempts/{attemptId}/submit

**Request:**

```http
POST /api/v1/attempts/a1b2c3d4-.../submit HTTP/2
Content-Type: application/json; charset=utf-8
Authorization: Bearer eyJ...
Idempotency-Key: 7c9e6f8b-2a3d-4e5f-b1a0-1234567890ab

{}
```

**Request JSON Schema:**

```yaml
SubmitAttemptRequest:
  type: object
  additionalProperties: false
  description: "Empty body — attempt_id ở path là đủ"
```

**Response 200 OK (graded đầy đủ):**

```json
{
  "status": "graded",
  "state_version": 13,
  "grading": {
    "raw_score": 85.0,
    "max_score": 100.0,
    "percentage_score": 85.0,
    "passed": true,
    "pending_ai": 0
  },
  "result_url": "/api/v1/attempts/a1b2c3d4-.../result"
}
```

**Response 202 Accepted (còn pending AI):**

```http
HTTP/2 202
...

{
  "status": "submitted",
  "state_version": 13,
  "grading": {
    "raw_score": 60.0,
    "max_score": 100.0,
    "percentage_score": null,
    "passed": null,
    "pending_ai": 2
  },
  "result_url": "/api/v1/attempts/a1b2c3d4-.../result"
}
```

**Response JSON Schema:**

```yaml
SubmitAttemptResponse:
  type: object
  required: [status, state_version, grading, result_url]
  additionalProperties: false
  properties:
    status:
      type: string
      enum: [submitted, graded]
    state_version:
      type: integer
      format: int64
    grading:
      type: object
      required: [raw_score, max_score, pending_ai]
      properties:
        raw_score:        { type: number, format: double }
        max_score:        { type: number, format: double }
        percentage_score: { type: number, nullable: true }
        passed:           { type: boolean, nullable: true }
        pending_ai:       { type: integer, minimum: 0 }
    result_url:
      type: string
      format: uri-reference
```

**Error responses:**

| Status | Code | Khi nào |
| ------ | ---- | ------- |
| 401 | `AUTH_TOKEN_INVALID` | — |
| 403 | `AUTH_FORBIDDEN` | Not owner |
| 404 | `ATTEMPT_NOT_FOUND` | — |
| 409 | `ATTEMPT_ALREADY_SUBMITTED` | double-submit (hoặc retry — Idempotency-Key giải quyết) |
| 409 | `STALE_STATE_VERSION` | CDS đã suspend giữa chừng → client fetch snapshot lại |
| 409 | `IDEMPOTENCY_KEY_REUSE_DIFFERENT_BODY` | — |
| 410 | `ATTEMPT_EXPIRED` | — |
| 423 | `ATTEMPT_SUSPENDED` | cần proctor resume/terminate |

#### 12.6.3 Pattern tổng hợp (cho các endpoint còn lại)

Mỗi endpoint OpenAPI spec phải có:

1. **Operation ID** `{verb}{Resource}` — vd `startAttempt`, `submitAnswer`, `suspendAttempt`
2. **Summary** 1 dòng + **Description** link tới design doc section
3. **Request body schema** `additionalProperties: false`, `required`, constraints (min/max, pattern, format)
4. **Response schema** per status code
5. **Examples** happy path + error
6. **Security requirements** — `security: [{ bearerAuth: [] }]`
7. **`x-codeSamples`** cho curl/TS/Python (optional)
8. **Rate limit note** trong description

Tooling:
- BE: `springdoc-openapi` auto-gen từ `@RestController` + merge với `openapi.yaml` static
- FE: `openapi-typescript-codegen` → TS client tự động
- Contract test: Pact (consumer-driven) hoặc Spring Cloud Contract

---

## XIII. SECURITY & ACCESS CONTROL

### 13.1 Permission-based authorization

Exam Service **không check role name** mà check **permission code** từ JWT claim `authorities`. Xem `auth-service-design.md` §3.3 + §5.1.

```java
// Xem exam — permission + policy
@PreAuthorize("hasAuthority('exam.read') and @examPolicy.canView(authentication, #examId)")
@GetMapping("/exams/{examId}")
public ExamDto getExam(@PathVariable UUID examId) { ... }

// Sửa exam mình tạo
@PreAuthorize("hasAuthority('exam.update.own') and @examPolicy.isOwner(authentication, #examId)")
@PatchMapping("/exams/{examId}")
public void updateExam(...) { ... }

// Sửa bất kỳ exam trong org (admin)
@PreAuthorize("hasAuthority('exam.update.any') and @examPolicy.isSameOrg(authentication, #examId)")
public void updateExamAny(...) { ... }

// Publish
@PreAuthorize("hasAuthority('exam.publish') and @examPolicy.canPublish(authentication, #examId)")
public void publishExam(UUID examId) { ... }

// Suspend attempt (proctor/admin)
@PreAuthorize("hasAuthority('attempt.suspend')")
public void suspendAttempt(UUID attemptId, String reason, long expectedStateVersion) { ... }
```

> **⚠️ Scaffolding gotcha (reference auth §3.3):** config `JwtAuthenticationConverter` với `setAuthoritiesClaimName("authorities")` + `setAuthorityPrefix("")`. Không làm → `hasAuthority('exam.read')` silent fail.

### 13.2 Rate limiting & anti-abuse

Xem §12.5 cho bảng giới hạn. Implementation: Lua script atomic Redis sliding window (share với Auth).

**Account lockout layer** không áp dụng ở Exam Service (do Auth). Exam chỉ enforce rate limit per-attempt + per-user.

### 13.3 Password-protected exam

```java
if (exam.passwordProtected()) {
    if (!passwordHasher.verify(dto.accessPassword(), exam.accessPasswordHash())) {
        throw new ForbiddenException(EXAM_PASSWORD_INVALID);
    }
}
```

Dùng chung `PasswordHasher` (Argon2id) với Auth Service — lib share.

### 13.4 IP whitelist

```java
if (!exam.ipWhitelist().isEmpty()) {
    InetAddress clientIp = extractClientIp(request);   // X-Forwarded-For lấy hop đầu tiên
    if (!exam.ipWhitelist().contains(clientIp)) {
        log.warn("Rejected start from non-whitelisted IP {}", clientIp);
        throw new ForbiddenException(EXAM_IP_NOT_ALLOWED);
    }
}
```

### 13.5 Prevent answer tampering — DTO view strip

Client (student) **không bao giờ** nhận `is_correct`, `explanation`, `correct_order`, `grading_config` khi đang làm bài:

```java
@JsonView(StudentView.class)
public record StudentQuestionDto(
    String questionRefId,
    QuestionType type,
    String questionText,
    List<StudentOptionDto> options,      // {id, text} — bỏ is_correct, explanation
    Integer points,
    MediaDto media
) { }

@JsonView(InstructorView.class)
public record InstructorQuestionDto(
    String questionRefId,
    ...
    List<Option> options,                // full, có is_correct
    String explanation,
    ...
) { }
```

Controller chọn view theo permission — `hasAuthority('question.read.answer')` → InstructorView; else StudentView.

### 13.6 Lockdown browser

- Exam Service **không tự ép browser** — chỉ set flag `exam.lockdown_browser`.
- Client lockdown app (Safe Exam Browser, custom) gửi `X-Lockdown-Browser: true` + shared secret Vault path.
- Server verify header + secret; nếu `exam.lockdown_browser && !valid` → 403 `EXAM_LOCKDOWN_REQUIRED`.
- Chống spoof: shared secret rotate 90 ngày (Vault), client ship với OTA update.

### 13.7 Headers an toàn (mặc định)

```
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Referrer-Policy: strict-origin-when-cross-origin
Permissions-Policy: geolocation=(), camera=(self), microphone=()    # camera=self cho proctoring
Content-Security-Policy: default-src 'self'; connect-src 'self' wss://api.smartquiz.vn
Strict-Transport-Security: max-age=31536000; includeSubDomains; preload
```

### 13.8 Data isolation (multi-tenancy)

PostgreSQL Row-Level Security trên `exams`, `exam_attempts`:

```sql
ALTER TABLE exams ENABLE ROW LEVEL SECURITY;
CREATE POLICY org_isolation_exams ON exams
  USING (org_id = current_setting('app.current_org_id')::UUID);

ALTER TABLE exam_attempts ENABLE ROW LEVEL SECURITY;
CREATE POLICY org_isolation_attempts ON exam_attempts
  USING (exam_id IN (SELECT id FROM exams));  -- gián tiếp qua exam.org_id
```

Spring: `DataSource` wrapper set session var `app.current_org_id` từ JWT claim qua interceptor trước mỗi request. Connection pool phải **reset** var ở `onReturn` (HikariCP `connectionInitSql`). Platform admin bypass: set `app.bypass_rls=true` khi claim `platform_role=super_admin`.

### 13.9 TLS / mTLS

- TLS 1.3 only ở API Gateway (Kong).
- Service-to-service: mTLS qua Istio SPIFFE identity.
- WS upgrade cũng qua TLS 1.3.

### 13.10 Encryption at rest

| Dữ liệu | Cách | Key |
| ------- | ---- | --- |
| `exams.access_password_hash` | Argon2id | — (hash, không cần key) |
| `attempt_answers.answer_data` essay text nhạy cảm | Column-level AES-256-GCM app-side (optional Phase 3) | Vault `transit/exam_answer` |
| PostgreSQL disk | LUKS / RDS storage encryption | AWS KMS |
| Redis RDB/AOF | Disk encryption + TLS in-transit | — |

### 13.11 Secrets rotation

| Secret | Tần suất | Cơ chế |
| ------ | -------- | ------ |
| Lockdown browser shared secret | 90 ngày | Vault + OTA client update |
| DB password | 30 ngày | Vault Dynamic Secrets (Postgres plugin) |
| Redis password | 90 ngày | Vault static role |
| Kafka SASL | 90 ngày | Vault PKI |

---

## XIV. OBSERVABILITY

Tuân CLAUDE.md §2: **Micrometer → Prometheus**, **OpenTelemetry OTLP** (collector cluster), **Loki** log.

### 14.1 Metrics (Prometheus, `/actuator/prometheus`)

| Metric | Type | Label |
| ------ | ---- | ----- |
| `exam_attempt_started_total` | counter | `exam_id`, `org_id` |
| `exam_attempt_submitted_total` | counter | `result=passed\|failed\|pending` |
| `exam_attempt_expired_total` | counter | — |
| `exam_attempt_suspended_total` | counter | `reason` |
| `exam_attempt_suspend_skipped_total` | counter | `reason=stale_version\|terminal` |
| `exam_answer_submitted_total` | counter | `question_type` |
| `exam_answer_grading_total` | counter | `method=auto\|ai\|manual`, `result` |
| `exam_state_version_conflict_total` | counter | `endpoint` |
| `exam_active_attempts` | gauge | (update 10s) |
| `exam_active_ws_connections` | gauge | `pod` |
| `exam_start_duration_seconds` | histogram | — |
| `exam_answer_duration_seconds` | histogram | — |
| `exam_submit_duration_seconds` | histogram | — |
| `exam_grading_duration_seconds` | histogram | `method` |
| `exam_redis_hit_ratio` | gauge | `key_prefix` |
| `exam_outbox_pending_size` | gauge | — (§11.6) |
| `exam_outbox_publish_lag_seconds` | histogram | — (§11.6) |
| `exam_outbox_publish_failed_total` | counter | `topic`, `reason` |
| `exam_processed_events_total` | counter | `consumer_group`, `result=new\|duplicate` |
| `exam_circuit_breaker_state` | gauge | `target=question\|auth`, `state` |

### 14.2 Tracing (OpenTelemetry)

- Instrument qua `io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter`.
- Export OTLP gRPC → collector `otel-collector:4317` → Tempo/Jaeger.
- Span quan trọng set attribute: `exam.id`, `attempt.id`, `user.id`, `org.id`, `exam.state_version`, `exam.question_type`.
- **Cấm** set raw answer_data, JWT, password.
- Trace propagation qua gateway: `traceparent` W3C. MDC filter set `trace_id` = current span.

### 14.3 SLO (reference ADR-001 §1)

| SLI | Target trong window thi | Ngoài window |
| --- | ---------------------- | ------------ |
| `POST /answers` p99 | < 100ms | < 200ms |
| `POST /start` p99 | < 300ms | < 500ms |
| `POST /submit` p99 | < 500ms | < 800ms |
| Submit success rate | > 99.99% | > 99.9% |
| WS uptime per attempt (duration ≥ 80% connected) | > 99.9% | > 99% |
| Data loss answer | **0** tuyệt đối | 0 |
| Availability | **99.95%** (ADR-001) | 99.9% |

Error budget trong window 22 phút/tháng → PagerDuty nếu tiêu > 50% trong 24h (fast burn).

### 14.4 Logs — structured JSON + MDC

Logback encoder `net.logstash.logback:logstash-logback-encoder`. Filter `ExamMdcFilter` set: `trace_id`, `span_id`, `user_id`, `org_id`, `attempt_id` (nếu path có), `exam_id`, `request_id`, `client_ip`, `state_version` (if known).

```json
{
  "ts": "2026-04-22T10:05:22.123Z",
  "level": "INFO",
  "service": "exam-service",
  "trace_id": "7c9e6f8b2a3d4e5f...",
  "span_id": "1a2b3c4d...",
  "request_id": "req-abc123",
  "user_id": "a0000000-0000-0000-0000-000000000004",
  "org_id": "11111111-1111-1111-1111-111111111111",
  "attempt_id": "f0000000-...",
  "exam_id": "e1111111-...",
  "state_version": 12,
  "event": "answer.submitted",
  "question_type": "multiple_choice_single",
  "duration_ms": 67
}
```

**Masking filter bắt buộc** (Logback `MaskingPatternLayout`):
- Regex mask: `answer_data`, `access_password`, `access_token`, `refresh_token`, `client_secret`, `mfa_secret` → `***REDACTED***`
- Email: `h***@hust.edu.vn`
- **KHÔNG log toàn bộ answer payload** — chỉ log `question_type` + `submission_id` + `state_version`. Essay raw có PII.

Log ship qua promtail → Loki (retention 14 ngày). Index: `service`, `level`, `event`, `attempt_id`, `user_id`.

### 14.5 Alerts

| Alert | Điều kiện | Severity |
| ----- | --------- | -------- |
| `ExamServiceDown` | up == 0 trong 2 phút | critical |
| `ExamAnswerLatencyHigh` | p99 `/answers` > 300ms trong 5 phút | warning |
| `ExamSubmitLatencyHigh` | p99 `/submit` > 1s trong 5 phút | warning |
| `ExamSubmitFailSpike` | fail rate > 0.1% trong 5 phút | critical |
| `RedisHotDown` | hot cluster ping fail | warning (PG vẫn durable; UX bị chậm reconnect) |
| `RedisHotMemoryHigh` | `used_memory_pct` > 85% | critical (noeviction → OOM reject write) |
| `ActiveAttemptsDrop` | active giảm 50% trong 1 phút | critical (WS mass disconnect) |
| `ExamOutboxBacklog` | `exam_outbox_pending_size` > 10k | critical (RPO violation) |
| `ExamOutboxPublishLag` | `exam_outbox_publish_lag_seconds` p99 > 5s | critical |
| `GradingBacklog` | `grading.request.v1` consumer lag > 1000 | warning |
| `StateVersionConflictSpike` | `exam_state_version_conflict_total` > 100/min | warning (có thể CDS spam suspend) |
| `SLOBurnRate` | Error budget tiêu > 2%/hour (fast burn) | critical |

---

## XV. ERROR HANDLING

### 15.1 Format lỗi chuẩn (RFC 7807)

Xem §12.0.5 cho template.

### 15.2 Bảng mã lỗi

| Code | HTTP | Ý nghĩa | Khi nào dùng |
| ---- | ---- | ------- | ------------ |
| `EXAM_MALFORMED_REQUEST` | 400 | JSON parse fail, header missing | — |
| `EXAM_VALIDATION_FAILED` | 422 | Semantic validation fail body | Body có `errors[]` |
| `EXAM_NOT_FOUND` | 404 | Exam không tồn tại / đã xoá | — |
| `EXAM_NOT_OPEN` | 409 | Exam chưa tới `starts_at` hoặc đã qua `ends_at` | — |
| `EXAM_ACCESS_DENIED` | 403 | User không enrolled / org mismatch | — |
| `EXAM_PASSWORD_INVALID` | 403 | Sai mật khẩu truy cập | — |
| `EXAM_IP_NOT_ALLOWED` | 403 | IP không trong whitelist | — |
| `EXAM_LOCKDOWN_REQUIRED` | 403 | Exam yêu cầu lockdown browser | — |
| `EXAM_MAX_ATTEMPTS_REACHED` | 409 | Dùng hết attempt | — |
| `EXAM_RATE_LIMIT` | 429 | Vượt quota | `Retry-After` + `X-RateLimit-*` (§12.0.7) |
| `ATTEMPT_IN_PROGRESS_EXISTS` | 409 | Đã có attempt đang chạy | Gợi ý resume |
| `ATTEMPT_NOT_FOUND` | 404 | attempt_id không tồn tại | — |
| `ATTEMPT_EXPIRED` | 410 | `now > expires_at + 5s` | — |
| `ATTEMPT_ALREADY_SUBMITTED` | 409 | Đã nộp, không thao tác nữa | — |
| `ATTEMPT_SUSPENDED` | 423 | CDS / proctor suspend | — |
| `STALE_STATE_VERSION` | 409 | Concurrent mutation — state_version mismatch | Body kèm `expected` + `actual` + `detail`; client refetch snapshot |
| `IDEMPOTENCY_KEY_REUSE_DIFFERENT_BODY` | 409 | Cùng key, body khác | §12.0.6 |
| `ANSWER_MALFORMED_REQUEST` | 400 | JSON broken | — |
| `ANSWER_INVALID_PAYLOAD` | 422 | Shape không khớp question.type | Body `errors[]` |
| `ANSWER_UNKNOWN_QUESTION` | 404 | question_ref_id không ∈ attempt's question_order | — |
| `GRADING_IN_PROGRESS` | 202 | AI chưa chấm xong | Response của `/result` trong lúc pending |
| `QUESTION_SERVICE_DOWN` | 503 | Circuit breaker open | `Retry-After` |
| `EXAM_DEGRADED` | 503 | Redis hot + PG timeout đồng thời | — |
| `AUTH_TOKEN_INVALID` / `AUTH_TOKEN_EXPIRED` / `AUTH_TOKEN_REVOKED` | 401 | JWT (delegated từ Auth) | — |
| `AUTH_FORBIDDEN` | 403 | Thiếu permission | — |
| `EXAM_INTERNAL` | 500 | Lỗi hệ thống | Log trace_id, không leak stack |

### 15.3 Retry guidance (cho client)

| Error | Client nên làm |
| ----- | -------------- |
| 5xx | Exponential backoff 1/2/4s, max 3 lần |
| 503 `QUESTION_SERVICE_DOWN` | Honor `Retry-After`; nếu là `/start` thì retry; `/answers` thì đệm local queue và retry khi online |
| 409 `ATTEMPT_IN_PROGRESS_EXISTS` | Navigate resume, không tạo mới |
| 409 `STALE_STATE_VERSION` | GET `/snapshot` → refresh state → retry action với new state_version |
| 410 `ATTEMPT_EXPIRED` | Show "Bài thi đã kết thúc", không retry |
| 423 `ATTEMPT_SUSPENDED` | Show suspend banner, wait proctor; không retry |
| 429 | Honor `Retry-After` |
| 422 | Show field-level error theo `errors[]`, không retry |

---

## XVI. DEPLOYMENT & INFRASTRUCTURE

### 16.1 Kubernetes manifest (tóm tắt)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: exam-service, namespace: smartquiz }
spec:
  replicas: 3
  strategy: { type: RollingUpdate, rollingUpdate: { maxSurge: 2, maxUnavailable: 0 } }
  template:
    spec:
      affinity:
        podAntiAffinity:
          preferredDuringSchedulingIgnoredDuringExecution:
            - weight: 100
              podAffinityTerm:
                topologyKey: topology.kubernetes.io/zone
                labelSelector: { matchLabels: { app: exam-service } }
      containers:
        - name: exam
          image: registry.smartquiz.vn/exam-service:2.0.0
          ports:
            - { name: http, containerPort: 3002 }
            - { name: grpc, containerPort: 4002 }
            - { name: mgmt, containerPort: 9002 }
          env:
            - { name: SPRING_PROFILES_ACTIVE, value: prod }
            - { name: VAULT_ADDR, value: "https://vault:8200" }
          envFrom:
            - configMapRef: { name: exam-config }
            - secretRef:    { name: exam-secrets }
          resources:
            requests: { cpu: 1,   memory: 1Gi }
            limits:   { cpu: 4,   memory: 4Gi }
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: mgmt }
            initialDelaySeconds: 30
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: mgmt }
            periodSeconds: 3
          startupProbe:
            httpGet: { path: /actuator/health/readiness, port: mgmt }
            failureThreshold: 30
          lifecycle:
            preStop:
              exec:
                command: ["sh","-c","sleep 30"]   # WS drain
```

+ `HorizontalPodAutoscaler` theo CPU 70% + custom metric `exam_active_ws_connections` target 4000/pod (min 3, max 30)
+ `PodDisruptionBudget` minAvailable=2
+ `NetworkPolicy` ingress từ API Gateway + egress PG / Redis hot & cache / Kafka / Apicurio / Auth Service / Question Service
+ Ingress sticky session cookie `sq-exam-route` (WS affinity)

### 16.2 Cấu hình môi trường

| Key | Prod | Dev |
| --- | ---- | --- |
| `DB_URL` | Vault secret | `jdbc:postgresql://localhost:5432/smartquiz` |
| `REDIS_HOT_URL` | `redis://redis-hot:6379/0` | `redis://localhost:6379/0` |
| `REDIS_CACHE_URL` | `redis://redis-cache:6379/0` | `redis://localhost:6380/0` |
| `KAFKA_BROKERS` | `kafka-0:9092,kafka-1:9092,kafka-2:9092` | `localhost:9092` |
| `APICURIO_URL` | `http://apicurio:8080/apis/registry/v2` | `http://localhost:8080/apis/registry/v2` |
| `MONGO_URI` | Vault secret | `mongodb://localhost:27017/smartquiz` |
| `AUTH_GRPC` | `auth-service:4001` | `localhost:4001` |
| `QUESTION_GRPC` | `question-service:4003` | `localhost:4003` |
| `JWKS_URI` | `https://auth.smartquiz.vn/.well-known/jwks.json` | `http://localhost:3001/.well-known/jwks.json` |
| `LOCKDOWN_SHARED_SECRET` | Vault KV `secret/data/exam/lockdown` | env |

### 16.3 HPA

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: { name: exam-hpa }
spec:
  minReplicas: 3
  maxReplicas: 30
  metrics:
    - type: Resource
      resource: { name: cpu, target: { type: Utilization, averageUtilization: 70 } }
    - type: Pods
      pods:
        metric: { name: exam_active_ws_connections }
        target: { type: AverageValue, averageValue: 4000 }
  behavior:
    scaleUp:
      policies: [{ type: Percent, value: 50, periodSeconds: 30 }]
    scaleDown:
      stabilizationWindowSeconds: 300   # chậm → WS không mất đột ngột
```

### 16.4 Scaling & thread model

- Stateless service (state ở PG + Redis) → scale horizontal thoải mái.
- **I/O path** (HTTP, JDBC, Redis, Kafka, gRPC, WebClient): Virtual Threads `spring.threads.virtual.enabled=true`. Không cần tune pool.
- **Auto-grade**: CPU-bound nhẹ (compare set, array) — chạy inline trên VT, không cần executor riêng.
- **Sticky session WS**: ingress cookie `sq-exam-route` max-age 2h + scaleDown stabilization 5 phút.
- Peak 10k concurrent × 5k WS/pod → 2 pod tối thiểu; HPA scale khi WS tăng.

### 16.5 Disaster recovery

| Scenario | RPO | RTO | Biện pháp |
| -------- | --- | --- | --------- |
| Mất 1 pod | 0 | < 10s | K8s reschedule + client reconnect |
| Mất Redis hot | 0 (PG đủ durable) | < 2 phút | Redis Sentinel/Cluster failover; rebuild session từ PG khi resume |
| Mất Redis cache | 0 | < 30s | LRU fallback PG |
| Mất PG primary | < 1 phút | < 15 phút | Patroni streaming replication + PITR |
| Mất Kafka | ≤ 5s (RPO outbox) | < 5 phút | Outbox đệm; relayer retry khi Kafka up |
| Mất Apicurio | 0 ngắn hạn (cache schema local 10 phút) | < 5 phút | HA 3 node; schema cached khi publish |
| Mất zone | < 1 phút | < 15 phút | Multi-AZ; Istio traffic shift |
| Mất datacenter | < 1 phút | < 30 phút | Multi-region K8s, PG cross-region read replica |

---

## XVII. TESTING STRATEGY

### 17.1 Pyramid + coverage gate (JaCoCo)

```
          E2E (10%)       ← Playwright: start-answer-submit-grade happy path
       Integration (30%)  ← Testcontainers: PG + Redis (hot+cache) + Kafka + Apicurio + MongoDB
    Unit (60%)            ← Domain logic, state machine, graders, IRT, state_version fencing
```

| Layer | JaCoCo gate (line) |
| ----- | ------------------ |
| `domain/*` (pure logic) | ≥ **80%** |
| `application/*` (UseCase) | ≥ **70%** |
| `infrastructure/*` | best-effort (integration test cover) |
| Global | ≥ **75%** |

CI fail nếu coverage regress > 2% vs baseline `main`.

### 17.2 Test tools

| Layer | Tool |
| ----- | ---- |
| Unit | JUnit 5, AssertJ, Mockito |
| Integration | **Testcontainers** (PG 16, Redis 7 hot + cache, Confluent Kafka, Apicurio, MongoDB 7), **WireMock** (Question/AI stub) |
| Contract | **Spring Cloud Contract** hoặc Pact (consumer-driven) + Avro compat check Apicurio |
| Security | OWASP ZAP baseline CI; `@security-engineer` review trước merge |
| Load | k6 (target 10k concurrent, 50 RPS/attempt answer) |
| Chaos | Chaos Mesh (staging) — kill pod / delay Redis / partition Kafka |

### 17.3 Integration test bắt buộc

**1. Outbox + fencing token race (ADR-001 §impl.5):**

```java
@SpringBootTest
@Testcontainers
class AnswerOutboxAndFencingIT {
    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
    @Container static KafkaContainer kafka = new KafkaContainer(...);
    @Container static GenericContainer<?> apicurio = ...;
    @Container static GenericContainer<?> redis = ...;

    @Test
    void answer_submit_publishes_event_via_outbox_even_if_relayer_crashes() {
        var attemptId = startAttempt();
        submitAnswerUseCase.execute(attemptId, qid, payload, UUID.randomUUID());

        // Simulate relayer pause
        relayerTestHook.pauseBefore(PublishStage.KAFKA_SEND);
        Thread.sleep(500);
        relayerTestHook.resume();

        await().atMost(10, SECONDS).untilAsserted(() -> {
            var records = kafkaConsumer.poll("exam.answer.submitted.v1");
            assertThat(records).hasSize(1);
            assertThat(records.get(0).key()).isEqualTo(attemptId.toString());
        });
        assertThat(outboxRepo.pendingCount()).isZero();
    }

    @Test
    void stale_state_version_suspend_is_skipped_and_published() {
        var attemptId = startAttempt();
        long v = attemptRepo.findById(attemptId).get().stateVersion();
        // Concurrent: submit bumps version → v+1
        submitAttemptUseCase.execute(attemptId);
        // CDS tries to suspend with old v
        assertThatThrownBy(() -> suspendAttemptUseCase.execute(attemptId, v, "risk"))
            .isInstanceOf(StateVersionConflict.class);
        // suspend_skipped.v1 event published
        await().untilAsserted(() ->
            assertThat(kafkaConsumer.poll("exam.attempt.suspend_skipped.v1")).hasSize(1));
    }
}
```

**2. Idempotency submission_id:**

```java
@Test
void second_submit_with_same_submission_id_returns_same_result() {
    var attemptId = startAttempt();
    var sid = UUID.randomUUID();
    var dto = new AnswerDto("q-1", Map.of("selected_options", List.of("o1")), sid);

    mvc.perform(post("/api/v1/attempts/{id}/answers", attemptId).content(json(dto)))
       .andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(true));
    mvc.perform(post("/api/v1/attempts/{id}/answers", attemptId).content(json(dto)))
       .andExpect(status().isOk()).andExpect(jsonPath("$.saved").value(true));

    // 1 row trong PG
    assertThat(answerRepo.countBySubmissionId(sid)).isEqualTo(1);
    // 1 event outbox
    assertThat(outboxRepo.findByAggregateId(attemptId)).filteredOn(r -> r.topic().equals("exam.answer.submitted.v1")).hasSize(1);
}
```

**3. Consumer idempotency `processed_events`:**

```java
@Test
void redelivered_cheat_alert_is_deduped() {
    var attemptId = startAttempt();
    var alert = new CheatAlert(UUID.randomUUID(), attemptId, 60, SUSPEND, /* expected_state_version */ 0, "...");
    cheatConsumer.handle(alert);
    cheatConsumer.handle(alert);   // replay
    assertThat(attemptRepo.findById(attemptId).get().status()).isEqualTo(SUSPENDED);
    assertThat(outboxRepo.findByTopic("exam.attempt.suspended.v1")).hasSize(1);
}
```

### 17.4 Security test cases bắt buộc

- [ ] SQL injection qua `access_password`, `question_ref_id`
- [ ] Answer tampering — student không nhận `is_correct` / `explanation` qua `/exams/{id}`
- [ ] State_version tampering — client gửi `expected_state_version` fake không match → 409, không side-effect
- [ ] Timer manipulation — client-side change không reset server `expires_at`
- [ ] IDOR — user A không đọc được `/attempts/{id}` của user B (403 AUTH_FORBIDDEN)
- [ ] Cross-org — user org X không list được exam org Y (RLS)
- [ ] JWT alg:none, JWT public-key-signed (confused deputy)
- [ ] Refresh token stolen → CDS phải suspend (integrate Auth event)
- [ ] Race condition submit vs suspend (fencing test §17.3)
- [ ] Race condition 2 concurrent submit cùng attempt (lock `lock:submit:*`)
- [ ] Idempotency replay 10x cùng submission_id → 1 row PG, 1 event Kafka
- [ ] Rate limit: 201 request/phút `/answers` → 429
- [ ] Outbox poisoning — malformed payload không block queue, mark last_error
- [ ] RLS bypass attempt — SET `app.current_org_id = NULL` → query fail

### 17.5 Load test scenario (k6)

```js
export let options = {
  scenarios: {
    peak_exam: {
      executor: 'ramping-vus',
      stages: [
        { duration: '2m',  target: 2000 },
        { duration: '5m',  target: 10000 },
        { duration: '60m', target: 10000 },
        { duration: '2m',  target: 0 }
      ]
    }
  },
  thresholds: {
    'http_req_duration{endpoint:answer}':  ['p(99)<100'],
    'http_req_duration{endpoint:submit}':  ['p(99)<500'],
    'http_req_duration{endpoint:start}':   ['p(99)<300'],
    'checks': ['rate>0.9999']
  }
};
```

Chạy từ 2-3 node load generator (4 vCPU / 8 GB).

### 17.6 Chaos scenarios (staging)

- Kill 1 pod exam-service trong lúc 100 user thi → verify all answers saved (PG count match before)
- Delay Redis hot 500ms → verify latency spike nhưng không fail (PG vẫn ghi)
- Partition Kafka 30s → verify outbox `pending_size` tăng, sau recovery drain hết ≤ 60s
- Kill PG primary → verify Patroni failover < 15s, no data loss

---

## XVIII. ROADMAP & OPEN QUESTIONS

### 18.1 Gate trước khi scaffold (CLAUDE.md §9)

**Phải xong trước khi code UseCase đầu tiên:**

- [x] Schema PostgreSQL (`database/postgresql/schema.sql` §6, §7, §13)
- [x] `state_version` column trên `exam_attempts` (schema §7, CLAUDE.md §3 fencing)
- [x] ADR-001 (SLA + RPO + outbox)
- [x] ADR-002 (Analytics vs Cheating split)
- [ ] **OpenAPI 3.1 spec** (`app/src/main/resources/static/openapi.yaml`) — đủ endpoint MVP §18.2, reviewed trước khi FE code thi (CLAUDE.md §9.3)
- [ ] Avro schema MVP `shared-contracts/avro/exam/`: `exam.exam.published.v1`, `exam.attempt.started.v1`, `exam.answer.submitted.v1`, `exam.attempt.submitted.v1`, `exam.attempt.graded.v1`, `exam.attempt.suspended.v1`, `exam.attempt.suspend_skipped.v1`, `grading.request.v1`
- [ ] Register Avro schema lên Apicurio dev (BACKWARD compat)
- [ ] `shared-outbox-starter` Gradle plugin stub (dùng chung với Auth/Cheating/Analytics — ADR-001 §consequences)
- [ ] JWT keypair local dev (`ops/gen-jwt-keypair.sh`) — chia với Auth
- [ ] Topic declare trong `shared-contracts/avro/TOPICS.md` + CLAUDE.md §8

#### Schema delta v2.0 — đã merge vào `database/postgresql/schema.sql` (2026-04-22)

> Block single-source cho mọi schema change Exam v2.0. APPEND khi có thêm.

1. **`exam_attempts.state_version BIGINT NOT NULL DEFAULT 0`** (schema §7 line 343) — fencing token: ✅ merged
   - Index không cần thêm (PK đủ).
   - JPA mapping `@Version` trên field `stateVersion`.
   - Flyway delta prod: `V{epoch}__exam_state_version.sql` (đã có trong schema master; khi app scaffold chỉ tạo empty migration hoặc dùng baseline).

2. **Không cần đổi** (đã đủ v2.0): `exams`, `exam_sections`, `exam_questions`, `exam_enrollments`, `attempt_answers`, `cheat_events`, `proctoring_sessions`, `grading_rubrics`, `attempt_feedback`, `certificates`, `outbox`, `processed_events`.

3. **Phase 2 partition migration** (khi attempt_answers > 10M rows): schema §7 comment đã outline. Migration file `V{epoch}__partition_attempts.sql` plan ở ADR-003 (chưa viết).

Schema master version tương ứng: `database/postgresql/schema.sql` (2026-04-22). Khi prod đã apply, items biến thành Flyway _immutable_ — sửa thêm phải append block v2.1.

### 18.2 MVP (tháng 4-5/2026)

- [ ] Flyway migration baseline
- [ ] Create / publish / archive exam (instructor flow)
- [ ] Start attempt + submit answer + submit final (student flow)
- [ ] Auto-grade: multiple_choice_*, true_false, ordering, matching, drag_drop, hotspot
- [ ] WebSocket timer sync + heartbeat
- [ ] Redis session store + idempotency `submission_id`
- [ ] **Transactional outbox relayer** (leader-elect qua advisory lock) + publish `exam.attempt.started.v1`, `exam.answer.submitted.v1`, `exam.attempt.submitted.v1`
- [ ] State machine exam + attempt với `@Version` fencing
- [ ] Consume `cheat.alert.generated.v1` (suspend) + fencing check + publish `suspend_skipped`
- [ ] Consume `grading.result.v1` (aggregate final score)
- [ ] gRPC `GetAttempt`, `UpdateRiskScore`, `SuspendAttempt` (internal)
- [ ] Integration test outbox + fencing race (§17.3)

### 18.3 Phase 2 (Q3/2026)

- [ ] Adaptive exam (IRT 3PL) — theta update + next-question Fisher info
- [ ] AI grading integration (essay, code) qua `grading.request.v1`
- [ ] fill_blank fuzzy grader (Levenshtein confidence)
- [ ] Proctor admin endpoints (suspend / resume / terminate)
- [ ] Exam analytics dashboard endpoint (aggregate từ Analytics Service)
- [ ] Feature flags (Unleash self-host): `exam.adaptive.enabled`, `exam.grader_v2.enabled`

### 18.4 Phase 3 (Q4/2026)

- [ ] Human review queue cho essay (`attempt.grade` manual flow)
- [ ] Batch grading (upload đáp án scan OMR)
- [ ] Regrade endpoint + bulk update điểm + publish `exam.attempt.regraded.v1`
- [ ] Exam templates + versioning (duplicate exam as template)
- [ ] Column-level encryption cho `answer_data` essay (AES-256-GCM Vault Transit)
- [ ] Partition `exam_attempts` + `attempt_answers` theo tháng (ADR-003)

### 18.5 Open questions — đã quyết

1. **Adaptive + section cố định** — adaptive pool = câu từ tất cả section đã config; section order giữ ngoài adaptive selector. Quyết Phase 2.
2. **Student review bài đã nộp (`allow_review=true`)** — hiển thị theo `question_order` đã xáo của attempt (không phải thứ tự gốc). Quyết.
3. **Multi-device thi cùng attempt** — **cấm**. UNIQUE INDEX `idx_one_active_attempt` + match `X-Device-Fingerprint` khi reconnect; mismatch → 409 + gợi ý logout device kia.
4. **Offline mode** — KHÔNG MVP. Phase 3 có thể support download câu + sync sau (yêu cầu cryptographic integrity: signed manifest).
5. **Proctoring integration** — service riêng, communicate qua Kafka (`proctoring.frame.analyzed.v1`) + gRPC (`ProctoringService.StreamVideo`). Exam Service chỉ consume trigger suspend.
6. **Grace period khi browser crash sát submit** — `grace_period_minutes` đã có trong `exams`; cộng vào `expires_at` khi tạo attempt. Default 0; instructor set theo nature of exam.
7. **Outbox partitioning** — MVP 1 bảng chung; Phase 2 partition theo `created_at` day-level khi > 10M rows. Relayer query `created_at >= NOW() - interval '1 day'` để hit partition mới nhất.

---

_Tài liệu thuộc Hệ Thống Thi Trực Tuyến Thông Minh — Exam Service Design v2.1 — Tháng 4/2026._
