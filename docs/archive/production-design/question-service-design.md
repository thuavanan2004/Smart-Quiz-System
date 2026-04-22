# QUESTION SERVICE - THIẾT KẾ CHI TIẾT

**Hệ Thống Thi Trực Tuyến Thông Minh** | Version 2.0 | Tháng 4/2026

Tài liệu này mở rộng mục "Question Service" trong `design.md`, mô tả chi tiết ở mức đủ để triển khai code production. Cấu trúc + convention bám theo `docs/auth-service-design.md` v1.5 để đồng bộ phong cách thiết kế cả repo.

**Tài liệu nguồn ràng buộc (phải đọc trước khi thay đổi):**
- `CLAUDE.md` §2 (stack lock), §3 (NFR lock — outbox/state_version/JWT RS256/idempotent consumer), §6 (interaction rules), §7 (agents + skills)
- `docs/adr/ADR-001-sla-rpo-outbox.md` (SLA 99.9% platform / 99.95% Question for read path, RPO ≤ 5s cho event critical, outbox pattern)
- `docs/auth-service-design.md` §3.3 (JwtAuthenticationConverter trap), §5.1 (JWT claims — `authorities`, `org_id`, `platform_role`), §12.0 (API conventions), §11 (outbox publisher pattern) — Question Service là **consumer** JWT + **producer** outbox + consumer event pattern giống Auth.
- `database/mongodb/schema.js` (6 collections: `questions`, `question_versions`, `question_reports`, `question_tags`, `question_imports`, `ai_prompts`)
- `database/postgresql/schema.sql` §6 (subjects taxonomy), §13 (outbox/processed_events)
- `database/elasticsearch/schema.json` (`question_search` index — text + KNN dense_vector)
- `shared-contracts/avro/question/*.avsc` (khi tạo) — BACKWARD compat mode

**Changelog v2.0 (2026-04-22) — alignment với auth-service-design v1.5:**
- Stack table rewrite theo `CLAUDE.md §2`: Spring Boot **3.3+**, Gradle wrapper + **Spotless + Checkstyle + JaCoCo**, **Testcontainers** (Mongo 7, Redis 7, Kafka, Elasticsearch 8, Apicurio), **Apicurio Schema Registry + Avro** BACKWARD compat, **Loki / OTLP / Micrometer→Prometheus** (không còn chỉ "Micrometer + OpenTelemetry")
- §II.2 module layout Gradle multi-project (`app`, `api-grpc`); §II.3 build quality gate (JaCoCo domain ≥ 80%, application ≥ 70%)
- §IV data model **không copy DDL** — chỉ ghi invariant + business rule; pointer đến `schema.js` / `schema.sql` / `schema.json`
- §XI rewrite **Transactional Outbox** (ADR-001): tách critical (qua outbox) vs fire-and-forget; thêm `AuthOutboxPublisher`-pattern với `@Transactional(MANDATORY)` fail-fast; relayer dùng `pg_try_advisory_lock` + `SmartLifecycle` + per-row error isolation; Avro encode ở relayer, JSONB lưu outbox DB
- §XI.2 consumer pattern với `processed_events` dedup (bảng có sẵn `schema.sql §13`) — at-least-once + idempotent
- §XII.0 API conventions đầy đủ (versioning `/api/v1/`, content-type, naming, status codes 200/201/202/204/400/401/403/409/410/422/429/503, **Idempotency-Key** header, **rate-limit headers** X-RateLimit-*/Retry-After, **cursor-based pagination**, no envelope, RFC 7807 Problem Details với `errors[]` cho 422)
- §XII.10 endpoint contract template với **OpenAPI 3.1 JSON Schema** (create question + search) làm reference BE/FE/QA codegen
- §XIII security rewrite: JWT verify qua JWKS (reference Auth §5.4) + **JwtGrantedAuthoritiesConverter trap** (authoritiesClaimName="authorities", prefix="") cùng config snippet; sudo mode tier A cho `question.deprecate` (recent-auth 5 phút) + tier B cho bulk-delete (§XIII.1); CSP/HSTS/nosniff; Redis key namespacing để tránh collision cross-service
- §XV RFC 7807 Problem Details + bảng error code gồm `QUESTION_MALFORMED_REQUEST` (400), `QUESTION_VALIDATION_FAILED` (422 + `errors[]`), `QUESTION_IDEMPOTENCY_KEY_REUSE_DIFFERENT_BODY`, `QUESTION_SUDO_REQUIRED`
- §XVII test strategy — JaCoCo gate cụ thể, **Testcontainers integration test outbox** (crash relayer giữa publish → event eventually xuất hiện), contract test Spring Cloud Contract cho gRPC, security test matrix
- §XVIII roadmap bổ sung **gate prereq trước scaffold** theo CLAUDE.md §9
- §XIX note **agents + skills** nên invoke khi scaffold (từ CLAUDE.md §7)

**Changelog v1.0 (2026-04-18):** bản khởi tạo theo design.md.

---

## I. TỔNG QUAN

### 1.1 Vai trò

Question Service là **nguồn sự thật duy nhất** cho ngân hàng câu hỏi. Mọi thao tác CRUD câu hỏi, tìm kiếm, import/export, và quản lý vòng đời đều đi qua đây. Exam Service **không** truy cập MongoDB trực tiếp — chỉ qua gRPC hoặc consume event.

| Trách nhiệm | Không thuộc phạm vi |
| ----------- | ------------------- |
| CRUD ngân hàng câu hỏi (11 loại polymorphic) | Sinh câu hỏi bằng AI (AI Service publish `ai.question.generated.v1` → consume) |
| Quản lý phiên bản bất biến (version history) | Chấm điểm câu hỏi (Exam + AI Service) |
| Tìm kiếm: full-text, faceted, semantic (KNN) | Calibrate IRT params (Analytics publish `analytics.irt.calibrated.v1` → consume) |
| Import/Export: CSV, Excel, GIFT, QTI 2.1, JSON, MoodleXML | Lưu media (Media Service — chỉ lưu ref URL/S3 key) |
| Duplicate detection (embedding cosine similarity ≥ 0.92) | Sinh embedding (AI Service — chỉ consume `ai.embedding.ready.v1`) |
| Quality review workflow (draft → review → active → deprecated) | Quản lý tag taxonomy master (Phase 3 marketplace) |
| Thống kê sử dụng (`stats.times_used`, `correct_rate`, flush batch 30s) | Auth + RBAC (Auth Service cấp JWT + JWKS; Question **verify offline** + enforce permission-based) |
| Sync Mongo → Elasticsearch qua event-driven | Gửi email / notification (Notification Service consumer) |
| Subject/topic taxonomy đọc từ PostgreSQL shared `subjects` | Attempt/grading (Exam Service) |

### 1.2 Stack công nghệ

> Bản này đã lock theo `CLAUDE.md §2`. Đổi công nghệ phải viết ADR mới — đừng tự ý thay trong design doc.

| Thành phần | Chọn | Lý do |
| ---------- | ---- | ----- |
| Runtime | **Java 21 LTS + Spring Boot 3.3+** | Thống nhất với Auth/Exam Service. `spring.threads.virtual.enabled=true` cho I/O path (Mongo, ES, Redis, Kafka producer, gRPC, S3) — **question path full I/O-bound**, VT phù hợp 100%. |
| Framework | Spring Web MVC + Spring Security 6 (Resource Server) | JWT verify qua JWKS — Question KHÔNG phát token. |
| ORM Mongo | Spring Data MongoDB + reactive driver (non-blocking cho stream) | `questions`, `question_versions`, `question_reports`, `question_tags`, `question_imports`, `ai_prompts` |
| ORM PG | Spring Data JPA + Hibernate 6 | Chỉ cho `subjects` taxonomy (§IV.2) + `outbox`/`processed_events` (§XI) |
| Migration | **Flyway** (`src/main/resources/db/migration`) cho PG. Mongo schema ở `database/mongodb/schema.js` + migrate-mongo cho delta | CLAUDE.md §2 |
| Elasticsearch client | `co.elastic.clients:elasticsearch-java` 8.x (NOT RestHighLevelClient — deprecated) | Search text + KNN dense_vector |
| Redis client | Lettuce (async, non-blocking) | LRU cache + pub/sub invalidation |
| Kafka | Spring Kafka 3.x + **Apicurio** Avro serde | Consume AI events + publish outbox relayer events |
| Schema contracts | **Apicurio Schema Registry + Avro**, BACKWARD compat (CLAUDE.md §2) | `shared-contracts/avro/question/*.avsc` |
| gRPC | grpc-java + protoc-gen-validate | Serve Exam Service (GetQuestion, BatchGet, GetForStudent) |
| Import/export | Apache POI 5 (XLSX), OpenCSV, custom ANTLR GIFT parser, xerces QTI 2.1, Jackson (JSON/MoodleXML) | |
| Validation | Jakarta Bean Validation 3.0 + custom validators per type (§V) | |
| HTTP client | Spring `WebClient` + Resilience4j circuit breaker | AI Service, Media Service calls |
| JWT | `spring-boot-starter-oauth2-resource-server` + JWK Set URI | Verify offline, cache JWKS 1h (§XIII.2) |
| Rate limit | Redis Lua sliding window (copy pattern Auth §9.1) | — |
| Observability | **Micrometer → Prometheus**, **OpenTelemetry OTLP** (traces+metrics → otel-collector:4317), **Loki** (log push qua promtail) | Stack chuẩn repo (CLAUDE.md §2) |
| Logging | SLF4J + Logback JSON encoder (logstash-logback-encoder) + **MDC** (`trace_id`, `user_id`, `org_id`, `question_id`, `request_id`) | AI-friendly debug |
| Build | **Gradle** wrapper pinned + **Spotless** (google-java-format) + **Checkstyle** + **JaCoCo** | Quality gate CI |
| Test | JUnit 5 + AssertJ + **Testcontainers** (MongoDB 7, Redis 7, Kafka, Elasticsearch 8, Apicurio, PostgreSQL 16) + WireMock (AI/Media stub) | Integration hit container thật |

### 1.3 Cổng & giao thức

| Giao thức | Port | Mục đích |
| --------- | ---- | -------- |
| HTTP/1.1 + HTTP/2 (REST) | `3003` | Client-facing qua API Gateway |
| gRPC | `4003` | Internal service-to-service (Exam Service gọi khi start attempt) |
| OpenAPI spec | `3003/v3/api-docs` (JSON) · `3003/swagger-ui.html` (UI) | Contract FE + Exam (CLAUDE.md §9 — gate trước khi FE code question bank) |
| Actuator (health, metrics) | `9003/actuator/*` | Prometheus scraping + K8s probes |

### 1.4 Yêu cầu phi chức năng (NFR)

| Chỉ số | Mục tiêu | Rationale |
| ------ | -------- | --------- |
| `GetQuestion` (gRPC, cache hit) p99 | < 10ms | Exam Service start attempt phải nhanh — 30-50 câu × 10ms = 300-500ms chấp nhận được |
| `GetQuestion` (cache miss, Mongo) p99 | < 50ms | Mongo index `question_id` unique lookup |
| `BatchGetQuestions` (50 questions) p99 | < 100ms | Batch MGET Redis + Mongo `$in` fallback |
| Search full-text (ES) p99 | < 200ms | Query `question_search` với filters |
| KNN semantic p99 | < 300ms | KNN score query dense_vector |
| Cache hit ratio | ≥ 90% | Warm cache khi `exam.published.v1` consume |
| ES sync lag (Mongo→ES) p99 | < 5s | Outbox publish → consumer index ES (ADR-001 RPO ≤ 5s) |
| Read/Write ratio | ~1000:1 | Read-heavy service |
| Availability | 99.95% trong window kỳ thi | Exam Service blocking dep khi start attempt; ngoài window 99.9% (ADR-001 §1) |

---

## II. KIẾN TRÚC BÊN TRONG

### 2.1 Sơ đồ lớp (layered architecture)

```
┌──────────────────────────────────────────────────────────────┐
│  Controllers (REST + gRPC)                                   │
│  ─ QuestionController, SearchController, ImportExportCtrl    │
│  ─ TaxonomyController, QuestionReportController              │
│  ─ AdminQuestionController (deprecate, bulk-ops)             │
│  ─ QuestionGrpcService                                       │
├──────────────────────────────────────────────────────────────┤
│  Application Services (use cases)                            │
│  ─ CreateQuestionUseCase, UpdateQuestionUseCase              │
│  ─ PublishQuestionUseCase (draft→review→active)              │
│  ─ DeprecateQuestionUseCase, RestoreQuestionUseCase          │
│  ─ ImportQuestionsUseCase, ExportQuestionsUseCase            │
│  ─ SearchQuestionsUseCase (text / semantic / faceted)        │
│  ─ DetectDuplicateUseCase, ReviewReportUseCase               │
├──────────────────────────────────────────────────────────────┤
│  Domain Services                                             │
│  ─ QuestionStateMachine, ValidationPolicy (per type)         │
│  ─ GradingPreviewService, SimilarityScorer                   │
│  ─ QuestionAccessPolicy (org scope, ownership)               │
├──────────────────────────────────────────────────────────────┤
│  Repositories                                                │
│  ─ MongoQuestionRepo, MongoVersionRepo, MongoReportRepo      │
│  ─ MongoImportJobRepo, MongoTagRepo                          │
│  ─ PgSubjectRepo, PgOutboxRepo                               │
│  ─ EsQuestionIndexer (Elasticsearch write)                   │
│  ─ RedisQuestionCache, RedisSearchCache                      │
├──────────────────────────────────────────────────────────────┤
│  Integrations                                                │
│  ─ OutboxPublisher (TX-bound), KafkaRelayer (leader-elect)   │
│  ─ AiServiceClient (WebClient + CB)                          │
│  ─ MediaServiceClient (S3 presigned URL)                     │
│  ─ KafkaAiConsumer, KafkaAnalyticsIrtConsumer                │
│  ─ KafkaExamAnswerConsumer (stats batch flush 30s)           │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 Module Gradle multi-project

Nằm ở `services/question/` (CLAUDE.md §4). Wrapper pin (`gradle-wrapper.properties`), version catalog dùng chung `/gradle/libs.versions.toml`.

```
services/question/
├── settings.gradle.kts        # include: app, api-grpc, domain-test-fixtures
├── build.gradle.kts           # root — convention plugins (spotless, checkstyle, jacoco)
├── gradle/                    # wrapper (pinned)
├── api-grpc/                  # .proto + generated stubs (publish → mavenLocal để Exam consume)
│   ├── src/main/proto/question/v1/question_service.proto
│   └── build.gradle.kts
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/vn/smartquiz/question/
│       │   ├── QuestionServiceApplication.java
│       │   ├── config/          # MongoConfig, EsConfig, RedisConfig, KafkaConfig, SecurityConfig, OutboxConfig
│       │   ├── web/             # @RestController + @ControllerAdvice (RFC 7807)
│       │   ├── grpc/            # QuestionGrpcService
│       │   ├── application/     # UseCase (CreateQuestionUseCase, ...)
│       │   ├── domain/
│       │   │   ├── question/    # Question (abstract), QuestionType, QuestionContent
│       │   │   ├── type/        # 11 polymorphic subclasses (McSingleQuestion, EssayQuestion, …)
│       │   │   ├── version/     # QuestionVersion (immutable snapshot)
│       │   │   ├── report/      # QuestionReport
│       │   │   ├── tag/         # Tag, TagTaxonomy
│       │   │   └── policy/      # ValidationPolicy, QualityPolicy, AccessPolicy
│       │   ├── infrastructure/
│       │   │   ├── mongo/       # Repo + Spring Data entity mapping polymorphic (@TypeAlias)
│       │   │   ├── pg/          # Subject JPA entity + OutboxRepository
│       │   │   ├── elasticsearch/
│       │   │   ├── redis/
│       │   │   ├── kafka/       # Outbox relayer + Avro serde (Apicurio) + consumers
│       │   │   ├── parser/      # GiftParser (ANTLR), QtiParser (xerces), CsvParser, XlsxParser
│       │   │   └── client/      # WebClient wrapper AI/Media/Notification
│       │   └── common/          # Exception, ErrorCode, MdcFilter, RateLimitFilter
│       ├── main/resources/
│       │   ├── application.yml
│       │   ├── logback-spring.xml        # JSON encoder + mask filter (answers, explanation khi log raw)
│       │   ├── db/migration/             # Flyway V{epoch}__question_*.sql — patch delta PG
│       │   └── static/openapi.yaml       # OpenAPI 3.1 spec (§XII.10)
│       └── test/java/...
└── README.md
```

**Rule migration:**
- **PostgreSQL**: schema master `database/postgresql/schema.sql` (share với Auth/Exam). Question chỉ commit migration delta vào `app/src/main/resources/db/migration` với prefix `V{yyyymmddhhmm}__question_*.sql`. Không đổi migration đã release (immutable).
- **MongoDB**: schema validator master ở `database/mongodb/schema.js`. Thay đổi validator → migrate-mongo script trong `app/src/main/resources/mongo-migrations/`.
- **Elasticsearch**: mapping master `database/elasticsearch/schema.json`. Thay đổi mapping → reindex qua `_reindex` API (schema change KHÔNG immutable ở ES — rebuild index với alias rollover).

### 2.3 Build quality gate

| Tool | Cấu hình | Gate fail khi |
| ---- | -------- | ------------- |
| Spotless | `googleJavaFormat('1.19.2')` + `removeUnusedImports()` + `trimTrailingWhitespace()` | Format lệch → CI fail, gợi ý `./gradlew spotlessApply` |
| Checkstyle | `config/checkstyle/checkstyle.xml` (Google style + project override) | Bất kỳ error → CI fail |
| JaCoCo | Report HTML + XML → Codecov | `domain/` line coverage < **80%**, `application/` < **70%**, global < **75%** |
| OWASP dependency-check | `./gradlew dependencyCheckAggregate` nightly | CVSS ≥ 7.0 trên compile deps |
| Error Prone | Google static analysis — bật cho `main` | New warning → review block |
| Avro compat | `./gradlew :shared-contracts:avroCompatCheck` | BACKWARD compat fail trên schema đã live |

---

## III. DOMAIN MODEL

### 3.1 Aggregate root: `Question`

```java
public abstract class Question {
    protected QuestionId id;                    // UUID v4
    protected OrgId orgId;                      // null = shared across orgs (marketplace Phase 3)
    protected SubjectCode subjectCode;          // FK → PG subjects.code
    protected UserId createdBy;
    protected UserId reviewedBy;
    protected QuestionStatus status;            // DRAFT | REVIEW | ACTIVE | DEPRECATED
    protected QuestionType type;                // discriminator
    protected int version;                      // monotonic, bump mỗi lần content thay đổi
    protected long stateVersion;                // fencing token (tương tự Exam state_version) — chống race state transition
    protected boolean aiGenerated;
    protected Integer aiQualityScore;           // 0-100, nullable
    protected List<QualityFlag> aiQualityFlags; // enum, xem §XII.2
    protected QuestionContent content;          // text, rich, media, code, math
    protected String explanation;
    protected String hint;
    protected List<String> referenceLinks;
    protected QuestionMetadata metadata;        // topic, subtopic, tags, bloom_level, language, estTime
    protected IrtParams irt;
    protected QuestionStats stats;
    protected EmbeddingRef embedding;           // ref sang AI Service; vector lưu ở ES, KHÔNG ở Mongo
    protected Instant createdAt;
    protected Instant updatedAt;
    protected Instant reviewedAt;
    protected Instant deprecatedAt;             // NULL khi còn active

    // Domain operations (polymorphic)
    public abstract GradingResult autoGrade(AnswerPayload payload);
    public abstract void validate();
    public abstract AnswerPayloadSchema answerSchema();
    public abstract Question stripForStudent();  // trả bản sao không có đáp án/explanation

    // State transitions
    public void submitForReview() { stateMachine.transition(SUBMIT_REVIEW, this); }
    public void approve(UserId reviewer) { stateMachine.transition(APPROVE, this, reviewer); }
    public void reject(UserId reviewer, String reason) { stateMachine.transition(REJECT, this, reviewer, reason); }
    public void deprecate(UserId actor) { stateMachine.transition(DEPRECATE, this, actor); }
    public boolean isUsableInExam() { return status == ACTIVE; }
}
```

**Invariants (enforce ở domain, không phụ thuộc DB):**
- `version` ≥ 1, monotonic. Mỗi lần content thay đổi → bump version, snapshot sang `question_versions`.
- `stateVersion` bump mỗi state transition (draft→review, review→active, active→deprecated). Fencing token dùng cho optimistic concurrency (xem §IV.1).
- `status == ACTIVE` ⟹ `validate()` đã pass trong lần gọi gần nhất; `reviewedBy != null`.
- `aiGenerated == true` ⟹ reviewer trong `approve()` **phải khác** `createdBy` (four-eyes, §IV.1).
- `embedding` có thể null nếu AI Service chưa sinh xong → search degrade về text-only, không block `status=active`.
- `deprecated` = soft delete. **Không bao giờ hard-delete** từ code — chỉ platform admin qua DB hoặc batch job sau 1 năm.

### 3.2 Polymorphic question types

Dùng **single-collection inheritance** trong Mongo (`type` là discriminator qua Jackson `@JsonTypeInfo` + Spring Data `@TypeAlias`), mỗi type subclass override validation + grading. 11 subclass, chi tiết §V.

```java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @Type(value = McSingleQuestion.class,      name = "multiple_choice_single"),
    @Type(value = McMultiQuestion.class,       name = "multiple_choice_multi"),
    @Type(value = TrueFalseQuestion.class,     name = "true_false"),
    @Type(value = FillBlankQuestion.class,     name = "fill_blank"),
    @Type(value = MatchingQuestion.class,      name = "matching"),
    @Type(value = OrderingQuestion.class,      name = "ordering"),
    @Type(value = ShortAnswerQuestion.class,   name = "short_answer"),
    @Type(value = EssayQuestion.class,         name = "essay"),
    @Type(value = CodeExecutionQuestion.class, name = "code_execution"),
    @Type(value = DragDropQuestion.class,      name = "drag_drop"),
    @Type(value = HotspotQuestion.class,       name = "hotspot")
})
public abstract class Question { ... }
```

Mongo document cần field `type` ở top-level (discriminator). JSON schema validator ở `database/mongodb/schema.js` enforce `type ∈ { ...11 values }`.

### 3.3 Value objects

```java
public record QuestionMetadata(
    String topic,
    String subtopic,
    List<String> tags,           // normalized lowercase, no whitespace
    BloomLevel bloom,            // KNOWLEDGE | COMPREHENSION | APPLICATION | ANALYSIS | SYNTHESIS | EVALUATION
    String language,             // ISO-639-1 ("vi", "en")
    int estimatedTimeSeconds     // ≥ 10, ≤ 3600
) {
    public QuestionMetadata {
        if (tags == null) tags = List.of();
        if (estimatedTimeSeconds < 10 || estimatedTimeSeconds > 3600)
            throw new ValidationException("estimatedTimeSeconds out of range");
    }
}

public record IrtParams(
    int difficultyAssigned,      // 1-5, giáo viên gán thủ công
    double b,                    // calibrated difficulty (updated từ Analytics)
    double a,                    // discrimination
    double c,                    // guessing
    boolean calibrated,
    Instant calibratedAt,
    int responsesCount           // cỡ mẫu calibration
) { }

public record QuestionStats(
    int timesUsed,
    int correctCount,
    double correctRate,          // correctCount / timesUsed, recompute khi flush batch
    int avgTimeSeconds,
    double skipRate
) { }

public record EmbeddingRef(
    String model,                // "text-embedding-3-large"
    Instant updatedAt,
    String esDocId               // reference Elasticsearch doc (= question_id)
) { }

public enum QualityFlag {
    FACTUAL_ERROR, AMBIGUOUS, MULTIPLE_CORRECT, GRAMMAR_ERROR,
    INAPPROPRIATE_CONTENT, POSSIBLY_DUPLICATE, LOW_DISCRIMINATION,
    TOO_EASY, TOO_HARD
}
```

### 3.4 Aggregate: `QuestionVersion`

Immutable snapshot của câu hỏi tại mỗi lần chỉnh sửa nội dung.

```java
public class QuestionVersion {
    private ObjectId id;                  // Mongo _id
    private QuestionId questionId;
    private int version;                  // matches Question.version tại snapshot time
    private JsonNode contentSnapshot;     // full Question serialized (JSONB-equivalent)
    private UserId changedBy;
    private String changeReason;
    private Instant createdAt;
}
```

**Quy tắc:**
- **Không cho xoá** version. Khi câu hỏi đã dùng trong bài thi, `Exam Service` lưu `exam_questions.question_version` → sinh viên thi cũ vẫn thấy nội dung gốc.
- Khi rollback: tạo **version mới** với content của version cũ (KHÔNG sửa `version` number lùi).
- Retention: giữ vĩnh viễn cho câu hỏi `active`/`deprecated`. Xóa khi `Question` bị hard-delete (GDPR) — batch job sau 1 năm.

### 3.5 Aggregate: `QuestionReport`

Báo cáo từ học sinh/giáo viên về câu hỏi có vấn đề.

```java
public class QuestionReport {
    private ObjectId id;
    private QuestionId questionId;
    private UserId reportedBy;
    private ReportReason reason;          // WRONG_ANSWER, TYPO, UNCLEAR, OUTDATED, OTHER
    private String description;
    private ReportStatus status;          // PENDING | UNDER_REVIEW | RESOLVED | DISMISSED
    private UserId resolvedBy;
    private String resolutionNote;
    private Instant reportedAt;
    private Instant resolvedAt;
}
```

TTL Mongo: resolved report xóa sau 90 ngày (index có sẵn, `database/mongodb/schema.js §3`).

### 3.6 Tag taxonomy + access policy

```java
public record Tag(String code, String displayName, int useCount, Instant createdAt) { }

@Component
public class QuestionAccessPolicy {
    public boolean canRead(Authentication auth, Question q) {
        var ctx = UserContext.from(auth);
        return q.getOrgId() == null                          // shared (marketplace)
            || Objects.equals(q.getOrgId(), ctx.orgId())     // same org
            || ctx.hasPlatformRole(SUPER_ADMIN);
    }
    public boolean isOwner(Authentication auth, QuestionId id) { ... }
    public boolean isSameOrg(Authentication auth, QuestionId id) { ... }
}
```

Enforcement: §XIII.4.

---

## IV. DATA MODEL — invariants & business rules

> **⚠️ Schema DDL/JSON là nguồn truth duy nhất** ở:
> - `database/mongodb/schema.js` (6 collections: `questions`, `question_versions`, `question_reports`, `question_tags`, `question_imports`, `ai_prompts`)
> - `database/postgresql/schema.sql` §6 (`subjects` taxonomy) + §13 (`outbox`, `processed_events`)
> - `database/elasticsearch/schema.json` (`question_search` index)
>
> Section này **KHÔNG copy** field/DDL — chỉ mô tả invariant, retention, policy, usage pattern mà schema không thể diễn đạt. Thêm/đổi field: sửa schema master trước, rồi migration delta, rồi prose ở đây.

### 4.1 `questions` collection (Mongo — schema.js §1)

**Invariants business-level:**

- **Polymorphic discriminator**: field `type` ở top-level (không trong `content`). JSON schema validator enforce enum 11 giá trị.
- **`status` state machine** (§V): chỉ cho transition hợp lệ; repo throw `InvalidStateTransitionException` nếu code gọi sai.
- **`version`**: monotonic, bump mỗi lần `content` hoặc answer-related fields (options, correct_order, grading_config) thay đổi. Không bump cho `metadata.tags` / `stats` / `irt` update (không phải content change).
- **`stateVersion` fencing token**: bump mỗi state transition. Update lệnh dùng `{ _id, stateVersion: expected } → $set: { ..., stateVersion: expected+1 }`; nếu `modifiedCount=0` → throw `ConcurrentModificationException`, UseCase retry 3 lần rồi fail. Same pattern như `exam_attempts.state_version` (CLAUDE.md §3).
- **Four-eyes cho AI-generated**: nếu `aiGenerated=true`, lệnh `approve` verify `reviewer != createdBy`. Reject ở domain, không ở controller (dễ test unit).
- **`embedding`**: có thể null khi `status=draft/review` (AI chưa sinh xong). Khi `status=active`, nếu `embedding=null` → search text-only vẫn hoạt động; semantic search sẽ miss câu này.
- **Retention**: `deprecated` giữ vĩnh viễn cho audit/trace. Hard-delete chỉ khi GDPR user request → event `auth.user.deleted.v1` consume trigger reassign/anonymize (§XIII.5).

### 4.2 `subjects` table (PostgreSQL — schema.sql §6, line 221-232)

**Business rule:**
- `code` unique globally (cross-org) — convention `CS101`, `MATH201`, …
- `org_id = NULL` = platform-shared subject (marketplace Phase 3).
- Hierarchical qua `parent_id`; depth cap 5 (enforce app-level, không DDL).
- Khi `is_active=false`: câu hỏi gắn subject vẫn hoạt động, nhưng không cho tạo câu mới gắn subject này.
- Question service **read-only** trên `subjects` (cache Redis `subject:{code}` TTL 1h). CRUD subject là endpoint riêng `/api/v1/subjects/*`, platform admin.

### 4.3 `question_versions` (Mongo — schema.js §2)

**Business rule:**
- `(question_id, version)` unique — enforce DB + app.
- Immutable sau insert. KHÔNG có endpoint update/delete.
- Retention: giữ đời câu hỏi. GDPR hard-delete qua batch job khi `Question` bị hard-delete.

### 4.4 `question_reports` (Mongo — schema.js §3)

**Business rule:**
- `reportedBy` có thể là student (chỉ câu hỏi đã làm trong attempt) hoặc instructor (xem trong bank).
- Resolve chỉ bởi user có `question.approve` permission.
- Auto-resolve sau 90 ngày nếu status=PENDING → status=DISMISSED với note "auto-expired".
- TTL Mongo index: xóa resolved report sau 90 ngày (tiết kiệm storage; raw report cần lưu vĩnh viễn → export sang Analytics Data Lake trước khi xóa).

### 4.5 `question_imports` (Mongo — schema.js §5)

**Business rule:**
- Job status lifecycle: `pending → running → completed | failed | partially_failed`.
- File S3 gốc: TTL 7 ngày (cost). Report lỗi parse giữ trong record (`errors[]` max 1000 entry).
- Chunking: file > 1000 câu → split chunk 200 câu, process parallel bounded 4 worker (§X.2).

### 4.6 `outbox` + `processed_events` (PostgreSQL — schema.sql §13, line 679-716)

Chia sẻ với Auth/Exam. Question chỉ **APPEND** vào `outbox`, relayer riêng của Question service publish. Dedup consumer qua `processed_events` với `consumer_group = "question-service-<purpose>"` (xem §XI.2).

---

## V. QUESTION TYPE SPECIFICATIONS

### 5.1 Catalog đầy đủ 11 loại

| Type | Ý nghĩa | Grading path |
| ---- | ------- | ------------ |
| `multiple_choice_single` | 1 đáp án đúng trong N lựa chọn | sync, trivial |
| `multiple_choice_multi` | Nhiều đáp án đúng, phải chọn đủ | sync |
| `true_false` | Đúng / Sai | sync, trivial |
| `fill_blank` | Điền vào ô trống, accept list/regex | sync |
| `matching` | Nối cặp trái-phải | sync |
| `ordering` | Sắp xếp đúng thứ tự | sync |
| `short_answer` | Trả lời ngắn, NLP similarity | sync nếu confidence ≥ 0.75, else manual review |
| `essay` | Luận văn, chấm theo rubric | async (AI Service + manual review) |
| `code_execution` | Viết code, chạy test case | async (sandbox gVisor) |
| `drag_drop` | Kéo thả item vào zone | sync |
| `hotspot` | Click vào vùng trên ảnh | sync |

### 5.2 Schema chi tiết từng loại (type-specific fields)

> **Ghi chú**: các block JSON dưới chỉ minh hoạ payload shape của field type-specific. Field common (`id`, `org_id`, `subject_code`, `metadata`, `status`, `version`, `stateVersion`, `createdBy`, `irt`, `stats`, `embedding`, `createdAt`, `updatedAt`) xem §III.1 và `database/mongodb/schema.js §1`.

#### 5.2.1 multiple_choice_single / multiple_choice_multi

```json
{
  "type": "multiple_choice_single",
  "options": [
    { "id": "opt_a", "text": "Bubble Sort",  "is_correct": false, "explanation": "O(n²) xấu nhất" },
    { "id": "opt_b", "text": "Merge Sort",   "is_correct": true,  "explanation": "Luôn O(n log n)" }
  ]
}
```

**Validation:**
- 2 ≤ `options.length` ≤ 10
- `multi`: ít nhất 1 option `is_correct=true`
- `single`: chính xác 1 option `is_correct=true`
- `option.id` unique trong câu hỏi, regex `^opt_[a-z0-9_]{1,20}$`

**Grading `multi`:**
- Default: strict — phải chọn đúng bộ đáp án đúng (all-or-nothing)
- Optional: partial credit theo `grading_config.partial_scoring = true` (mỗi đúng +1/N, mỗi sai -1/N, min 0)

#### 5.2.2 true_false

```json
{
  "type": "true_false",
  "options": [
    { "id": "true",  "text": "Đúng", "is_correct": true },
    { "id": "false", "text": "Sai",  "is_correct": false }
  ]
}
```

#### 5.2.3 fill_blank

```json
{
  "type": "fill_blank",
  "content": { "text": "Thuật toán ổn định O(n log n) là ______." },
  "grading_config": {
    "accepted_answers": ["Merge Sort", "MergeSort", "merge sort"],
    "use_regex": false,
    "case_sensitive": false,
    "trim_whitespace": true,
    "blanks_count": 1
  }
}
```

**Multi-blank:** `content.text` có nhiều `______` → `accepted_answers: [[...], [...]]` theo thứ tự blank.

#### 5.2.4 matching

```json
{
  "type": "matching",
  "pairs": [
    { "left_id": "l1", "left_text": "Merge Sort",  "right_id": "r1", "right_text": "O(n log n)" },
    { "left_id": "l2", "left_text": "Bubble Sort", "right_id": "r2", "right_text": "O(n²)" }
  ],
  "grading_config": { "partial_credit": true }
}
```

Client shuffle `right` column khi render (server seed shuffle theo `attempt_id` để reproducible cho review sau thi).

#### 5.2.5 ordering

```json
{
  "type": "ordering",
  "items": [
    { "id": "i1", "text": "Đưa đỉnh xuất phát vào hàng đợi" },
    { "id": "i2", "text": "Lấy đỉnh đầu tiên ra" },
    { "id": "i3", "text": "Duyệt láng giềng" }
  ],
  "correct_order": ["i1", "i2", "i3"],
  "grading_config": { "strict": true }
}
```

**Grading:** strict = so sánh array hoàn toàn; non-strict = tính edit distance (Kendall tau), score = `1 - tau_distance / max_distance`.

#### 5.2.6 short_answer

```json
{
  "type": "short_answer",
  "grading_config": {
    "keywords": ["polymorphism", "inheritance", "override"],
    "min_similarity": 0.75,
    "max_words": 50,
    "case_sensitive": false,
    "sample_correct_answers": [
      "Polymorphism cho phép method cùng tên hoạt động khác nhau qua inheritance và override."
    ]
  }
}
```

**Grading logic:**
1. Tokenize + compute embedding của answer (async pre-compute qua AI Service; nếu latency > 200ms → fallback manual queue)
2. Cosine similarity với `sample_correct_answers` embeddings
3. Nếu `max(similarity) ≥ min_similarity` → correct
4. Nếu `0.5 ≤ similarity < 0.75` → `needs_manual_review=true`
5. Keyword coverage check (all keywords present) → bonus

#### 5.2.7 essay

```json
{
  "type": "essay",
  "grading_config": {
    "rubric_id": "UUID",
    "min_words": 150,
    "max_words": 500,
    "dimensions": [
      { "name": "content_accuracy", "max_points": 5, "description": "Nội dung chính xác" },
      { "name": "structure",        "max_points": 2 },
      { "name": "language_quality", "max_points": 3 }
    ],
    "ai_grading": {
      "enabled": true,
      "model": "gpt-4o",
      "confidence_threshold": 0.80,
      "require_human_review": true
    }
  }
}
```

**Grading flow (async):**
1. Exam Service publish `grading.request.v1` qua outbox (topic `exam.grading.requested.v1`)
2. AI Service chấm từng dimension → tổng hợp score + confidence + explanation
3. Nếu `confidence < threshold` hoặc `require_human_review=true` → queue giáo viên
4. Giáo viên xem AI suggestion + tự override → save

Question Service **không tham gia grading** — chỉ lưu cấu hình rubric.

#### 5.2.8 code_execution

```json
{
  "type": "code_execution",
  "content": {
    "text": "Viết hàm Python `sum_of_list(nums)` trả về tổng.",
    "code_snippet": "def sum_of_list(nums):\n    # Your code here\n    pass"
  },
  "grading_config": {
    "language": "python",
    "runtime_version": "3.12",
    "time_limit_ms": 2000,
    "memory_limit_mb": 128,
    "allow_network": false,
    "test_cases": [
      { "id": "tc1", "input": "[1,2,3]",     "expected": "6",       "hidden": false, "points": 3 },
      { "id": "tc2", "input": "[]",           "expected": "0",       "hidden": false, "points": 2 },
      { "id": "tc3", "input": "[-1,-2,-3]",   "expected": "-6",      "hidden": true,  "points": 3 },
      { "id": "tc4", "input": "[10000]*1000", "expected": "10000000","hidden": true,  "points": 2 }
    ],
    "reference_solution": "def sum_of_list(nums): return sum(nums)"
  }
}
```

**Grading flow (async):**
1. Exam publish `grading.request.v1` type=code
2. Code Runner Service (Python/Go worker) thực thi trong sandbox gVisor/Firecracker
3. Mỗi test case: match exact hoặc fuzzy (configurable)
4. Return `{passed_tests: [...], failed_tests: [...], total_points, execution_details}`

Hidden test case **không** được trả cho student view (stripForStudent drop `hidden=true` entries).

#### 5.2.9 drag_drop

```json
{
  "type": "drag_drop",
  "zones": [
    { "id": "z1", "label": "OSI Layer 1", "max_items": 2 },
    { "id": "z2", "label": "OSI Layer 7", "max_items": 3 }
  ],
  "items": [
    { "id": "it1", "text": "Cable", "correct_zone": "z1" },
    { "id": "it2", "text": "HTTP",  "correct_zone": "z2" },
    { "id": "it3", "text": "HTTPS", "correct_zone": "z2" }
  ]
}
```

#### 5.2.10 hotspot

```json
{
  "type": "hotspot",
  "content": { "media": [{ "type": "image", "url": "s3://.../anatomy.png", "width": 800, "height": 600 }] },
  "hotspots": [
    { "id": "h1", "x": 400, "y": 250, "radius": 30, "is_correct": true,  "label": "Tim" },
    { "id": "h2", "x": 200, "y": 400, "radius": 30, "is_correct": false, "label": "Phổi" }
  ],
  "grading_config": { "allow_multiple_clicks": false }
}
```

**Grading:** check điểm click `(cx, cy)` có thuộc circle `(x, y, radius)` của hotspot nào và `is_correct=true`.

---

## VI. STATE MACHINE

```
     ┌──────┐  submit_review   ┌────────┐  approve   ┌────────┐
     │draft ├─────────────────►│ review ├───────────►│ active │
     └──┬───┘                  └───┬────┘            └───┬────┘
        ▲                          │ reject              │ deprecate
        └──────────────────────────┘                     ▼
                                                    ┌────────────┐
                                                    │ deprecated │
                                                    └──────┬─────┘
                                                           │ restore (admin)
                                                           ▼
                                                      (back to review)
```

| Transition | Guard | Actor (permission) | `stateVersion` bump |
| ---------- | ----- | ------------------ | ------------------- |
| draft → review | `validate()` pass, có đủ content + metadata | creator (`question.update.own`) | +1 |
| review → active | reviewer ≠ creator **nếu** `aiGenerated=true` (four-eyes); nếu manual tạo bởi instructor cùng ≠ creator cho an toàn (soft check, warning) | reviewer (`question.approve`) | +1 |
| review → draft | có `rejection_reason` non-empty | reviewer (`question.approve`) | +1 |
| active → deprecated | không còn dùng trong `exam_attempts` đang chạy (query Exam Service qua gRPC hoặc check `exam.active_attempts.count(question_id) == 0`) | admin (`question.deprecate`) 🔒 sudo tier A | +1 |
| deprecated → review | — | admin (`question.deprecate`) 🔒 sudo tier A | +1 |

**Automation:**
- AI-generated questions vào trạng thái `review` trực tiếp (bỏ qua draft)
- Khi `aiQualityScore < 70` hoặc có flag `FACTUAL_ERROR`/`INAPPROPRIATE_CONTENT`: chặn approve tự động, bắt buộc manual review kỹ (UI warning banner)
- Khi `possibly_duplicate` flag với similarity ≥ 0.95: chặn publish, yêu cầu reviewer xác nhận "keep both" hoặc reject

**Implementation**: `QuestionStateMachine` bean dùng Spring State Machine hoặc custom pattern (enum + Map<Pair<Status, Action>, Transition>). Throw `InvalidStateTransitionException` nếu transition không hợp lệ → `409 QUESTION_INVALID_STATE_TRANSITION`.

---

## VII. SEARCH ARCHITECTURE

### 7.1 Hai đường tìm kiếm

**Đường 1: Full-text + faceted (Elasticsearch)**

```
GET /question_search/_search
{
  "query": {
    "bool": {
      "must":   [{ "multi_match": { "query": "thuật toán sắp xếp",
                                     "fields": ["content_text^3","topic^2","explanation"] } }],
      "filter": [
        { "term":  { "status": "active" } },
        { "term":  { "org_id": "..." } },
        { "terms": { "tags": ["sap_xep","thuat_toan"] } },
        { "range": { "difficulty_irt": { "gte": 0, "lte": 1.5 } } }
      ]
    }
  },
  "aggs": {
    "by_type":  { "terms": { "field": "type" } },
    "by_bloom": { "terms": { "field": "bloom_level" } }
  }
}
```

**Đường 2: Semantic KNN**

```
POST /question_search/_search
{
  "knn": {
    "field": "embedding",
    "query_vector": [0.01, ...],  // sinh bởi AI Service cho query text
    "k": 10,
    "num_candidates": 100
  }
}
```

Vector ở ES field `embedding` (dense_vector, dims 1536, `similarity: cosine`) — xem `database/elasticsearch/schema.json`.

### 7.2 Sync Mongo → Elasticsearch (event-driven qua outbox)

**Không** dùng Debezium CDC cho Mongo — lý do (ADR-001 §alternatives): CDC publish raw oplog, không có domain shape; Question cần event có chủ đích (Avro BACKWARD compat).

```
QuestionService app                           Elasticsearch
       │                                             │
       │ CREATE/UPDATE/DELETE question               │
       │ ─> Mongo write + INSERT INTO outbox (1 TX)  │
       │    events: question.created.v1 /            │
       │            question.updated.v1 /            │
       │            question.deprecated.v1 /         │
       │            question.published.v1            │
       │                                             │
       ├─► Relayer publish Kafka (§XI.2) ───────────►│ (Kafka topics)
       │                                             │
       └─► QuestionIndexWorker (cùng pod, consumer group) consume
           ├─ Check processed_events dedup
           ├─ Transform Question → ES doc
           ├─ Strip sensitive fields (answers, explanation)
           └─► ES index/delete ──────────────────────┘
```

**Embedding enrichment** (AI Service phụ trách sinh vector):

```
question.created.v1 / updated.v1 với content.text thay đổi
        │
        ▼
AI Service EmbeddingWorker consume
        │
        ▼
Gọi OpenAI embedding API / local model
        │
        ▼
Publish ai.embedding.ready.v1 {question_id, vector[1536], model, updated_at}
        │
        ▼
QuestionService consume → Mongo update EmbeddingRef + trigger ES reindex qua outbox
```

### 7.3 Cache strategy

| Query pattern | Redis key | TTL | Invalidation |
| ------------- | --------- | --- | ------------ |
| `GET /api/v1/questions/{id}` | `q:id:{q_id}:latest` | 30 phút | Khi `question.updated.v1` consume (pub/sub invalidation) |
| `GET /api/v1/questions/{id}/versions/{v}` | `q:id:{q_id}:v:{v}` | 2 giờ (immutable, không cần invalidate) | — |
| `POST /api/v1/questions/batch` | Per-id, dùng MGET | 30 phút | — |
| `GET /api/v1/questions/search?q=...&page=1&size=20` (default sort) | `q:search:{hash(query)}` | 5 phút | Khi câu mới `active` khớp filter (heuristic: flush toàn bộ pattern khi có `question.published.v1`) |
| `GET /api/v1/questions/search/semantic` | KHÔNG cache | — | User ý định mỗi lần khác |
| `GET /api/v1/subjects/{code}` | `subject:{code}` | 1 giờ | Khi `subject.updated.v1` (Phase 2) |
| `GET /api/v1/tags?q=&limit=20` | `tag:autocomplete:{prefix}` | 10 phút | Scheduled refresh từ Mongo |
| `q_subject:{code}:top_used` | List | 15 phút | Scheduled refresh (mỗi 15 phút query Mongo top-50 by times_used) |

**Key namespace**: tất cả Question Redis key prefix `q:` để tránh collision cross-service (Auth dùng `auth:`, Exam `exam:`, …).

**Pub/sub invalidation**: Khi consume `question.updated.v1` → publish Redis channel `q:invalidate` với payload `{question_id}`. Tất cả pod subscribe channel này → evict local caffeine cache (L1) + Redis key (L2). Avoid thundering herd bằng cache-aside + single-flight pattern (`CompletableFuture<Question>` per key).

---

## VIII. DUPLICATE DETECTION

### 8.1 Khi nào chạy

1. Khi tạo câu hỏi mới (POST) → trigger auto sau khi embedding sẵn sàng
2. Khi AI sinh câu hỏi (consume `ai.question.generated.v1`) → trigger bắt buộc trước khi approve
3. Manual qua endpoint `POST /api/v1/questions/{id}/check-duplicates`

### 8.2 Flow

```
Question created / updated (status ∈ {draft, review})
         │
         ▼
Outbox publish: question.check_duplicate.requested.v1
         │
         ▼
DuplicateDetectionWorker consume (same service, separate consumer group)
         │
         ├─► Wait AI embedding ready (timeout 60s, nếu hết → defer + retry)
         │
         ├─► ES KNN query k=5, filter same org_id + status=active
         │
         ├─► Score results:
         │    similarity ≥ 0.95 → BLOCKING_DUPLICATE
         │    0.92 ≤ s < 0.95   → WARNING_DUPLICATE
         │    0.85 ≤ s < 0.92   → SIMILAR (hint)
         │    s < 0.85          → UNIQUE
         │
         └─► Outbox publish: question.duplicate.detected.v1 {question_id, candidates: [...]}
             │
             ▼
             Self-consumer → Mongo $push aiQualityFlags=POSSIBLY_DUPLICATE (nếu ≥ 0.92)
             Notification Service consumer → email instructor
```

### 8.3 Ngưỡng similarity (tunable per-org qua `org_settings` — Phase 2)

| Ngưỡng | Hành động |
| ------ | --------- |
| ≥ 0.95 | Chặn publish (state transition review→active reject), yêu cầu reviewer xác nhận "keep both" hoặc reject câu mới |
| 0.92 - 0.95 | Cảnh báo rõ trên UI, cho phép publish nếu reviewer acknowledge + comment |
| 0.85 - 0.92 | Hiển thị gợi ý "câu hỏi tương tự" trên UI edit (không block) |
| < 0.85 | Không action |

---

## IX. IMPORT / EXPORT

### 9.1 Format hỗ trợ

| Format | Use case | Library |
| ------ | -------- | ------- |
| **CSV** | Simple import từ Excel | OpenCSV |
| **XLSX** | Template chuẩn nhiều sheet (1 sheet = 1 type) | Apache POI |
| **GIFT** | Moodle-style text format | Custom ANTLR parser |
| **QTI 2.1/3.0** | IMS standard, interop với LMS khác | XML + xerces |
| **JSON** | Full fidelity, backup/restore | Jackson |
| **MoodleXML** | Migrate từ Moodle | XSL transform + custom |

### 9.2 Import pipeline (async + idempotent)

```
POST /api/v1/questions/import (multipart file)
         │  + header Idempotency-Key: <uuid> (§XII.0.6)
         │
         ▼
Check idempotency Redis → nếu hit: return cached 202
         │
         ▼
Save file → S3 temp bucket (TTL 7 days), encrypt SSE-KMS
         │
         ▼
BEGIN TX
  Insert Mongo question_imports {job_id, imported_by, file_s3_key, format, status=pending, total=null}
  Insert PG outbox (question.import.requested.v1, key=job_id)
COMMIT
         │
         ▼
Trả về client 202 {job_id, status_url}
         │
         ▼
ImportWorker consume (relayer-triggered, consumer group "question-service-import")
         │
         ├─► processed_events dedup check
         ├─► Parse file theo format
         ├─► Validate từng câu (per type, §V)
         ├─► Duplicate detection (optional qua flag)
         ├─► Chunk 200 câu/lần, parallel 4 worker
         ├─► Insert Mongo `questions` với status=draft
         ├─► Outbox publish `question.created.v1` per question (trong TX batch)
         └─► Update job.status=completed|partially_failed|failed
            với {total, success, failed, errors[...]} (cap 1000 error)
         │
         ▼
Outbox publish `question.import.completed.v1`
         │
         ▼
Notification Service consume → email import_by
```

**Chunking**: file > 1000 câu → chia chunk 200/lần, process parallel với bounded parallelism (4 worker cùng job).

### 9.3 Export (async)

```
POST /api/v1/questions/export { filters, format, include_answers }
         │  + Idempotency-Key
         │
         ▼
Create job record + outbox event `question.export.requested.v1`
         │
         ▼
ExportWorker: Query Mongo (stream cursor để không OOM) → Generate file → Upload S3
         │
         ▼
Outbox `question.export.completed.v1` + presigned URL (TTL 24h)
         │
         ▼
Trả về client qua GET /export/{job_id} polling hoặc SSE
```

**Permission check**: `include_answers=true` yêu cầu `question.export.answers` permission (không default; seed chỉ cho `admin`, `platform_admin`).

---

## X. CACHE + PERFORMANCE

### 10.1 Profile tải

Kỳ thi đỉnh điểm: 10k học sinh start exam đồng thời, mỗi exam 30 câu hỏi.

| Operation | QPS tính | Chiến lược |
| --------- | -------- | ---------- |
| `BatchGetQuestions` (50 câu/exam) | 10k start/phút = 167/s × batch 50 | Cache Redis MGET; warm khi `exam.published.v1` consume |
| `GetQuestion` (adaptive next question) | ~100/s sau mỗi answer | Cache per-question hit ≥ 95% |
| Search text (instructor) | 50/s | Rate limit per user + ES cluster |
| Search semantic | 20/s | Rate limit chặt (10/min/user) vì KNN query đắt |
| CRUD | 5/s | Trivial |

### 10.2 Cache warming

Khi giáo viên `POST /api/v1/exams/{id}/publish`:
- Exam Service publish `exam.published.v1` (outbox)
- Question Service `QuestionCacheWarmer` consume → preload tất cả `question_ref_id` của exam vào Redis với TTL = `duration_minutes + 1 day`.
- Parallel MGET 50/batch, tránh hot partition.

### 10.3 Scaling

- **Stateless** → HPA theo CPU 70% + custom metric `question_cache_hit_ratio < 0.8` trigger scale out (pod mới chia tải cache miss).
- **MongoDB**: read-heavy → secondary read preference với staleness ≤ 10s OK cho search/list; primary cho `GetQuestion` (lo nghịch lý read own write sau update).
- **Elasticsearch**: 3 node min, scale shards khi index > 500k câu (số shard hiện tại 3, xem `schema.json`).
- **Virtual threads**: `spring.threads.virtual.enabled=true` cho toàn bộ I/O path (không có CPU-bound hotspot như Argon2 ở Auth → không cần thread pool riêng).

---

## XI. EVENTS — TRANSACTIONAL OUTBOX + AVRO

> **⚠️ Nguồn truth topic name + schema**: `shared-contracts/avro/TOPICS.md` (catalog tất cả topic của repo) và `shared-contracts/avro/question/*.avsc`. Bảng ở §XI.1 là **view tóm tắt**. PR đổi topic phải cập nhật cả 2 nơi + CLAUDE.md §8 trong cùng commit (CI grep check).

Tuân thủ **ADR-001** (`docs/adr/ADR-001-sla-rpo-outbox.md`) và CLAUDE.md §3:

- **Không** gọi `kafkaTemplate.send()` trực tiếp từ UseCase. Mọi state change → insert vào bảng `outbox` (PG) trong **cùng transaction** với domain change (Mongo).
- **Gotcha**: state change ở Mongo + outbox ở PG ⟹ **cross-datastore transaction**. Không có 2PC. Dùng pattern **"PG first + Mongo on ack"** hoặc **"Mongo first + PG outbox qua saga"**. Lựa chọn: **Mongo first (đồng bộ trong 1 PG TX chỉ chứa outbox row)** — tức là:
  1. Mongo `save(question)` (không TX, Mongo single-doc atomic)
  2. PG `@Transactional`: insert `outbox` row với payload đã chứa question state post-update (gồm `version`, `stateVersion`, `updated_at`)
  3. Nếu bước 2 fail sau khi bước 1 OK → retry 3 lần; nếu vẫn fail → dead-letter queue (log + alert), reconciler batch job scan `questions` với `updated_at > outbox.last_published_at` để tự phát hiện miss.
- **Trade-off**: khả năng mất event trong window ngắn giữa step 1 và step 2 (crash giữa hai bước). Reconciler patch lỗ hổng. RPO tight vẫn đạt ≤ 5s trong happy path.
- Relayer (leader-elected qua `pg_try_advisory_lock`) poll `SELECT ... FOR UPDATE SKIP LOCKED`, publish Kafka, `UPDATE published_at`. Mỗi pod chạy relayer thread nhưng chỉ 1 leader thắng lock.
- Payload encode **Avro**, schema publish lên **Apicurio Schema Registry** với compat mode `BACKWARD`.

### 11.1 Phân loại event theo độ quan trọng (ADR-001 §3)

**Critical — BẮT BUỘC qua outbox** (drive state change ở service khác, mất event = sai logic):

| Topic (v1) | Aggregate key | Payload (Avro record) | Consumer |
| ---------- | ------------- | --------------------- | -------- |
| `question.created.v1` | `question_id` | `{question_id, org_id, type, status, subject_code, created_by, version, created_at}` | ES indexer (self), AI embedding worker |
| `question.updated.v1` | `question_id` | `{question_id, version, state_version, changed_fields[], content_changed: bool, updated_at}` | ES indexer, AI (nếu content changed), Cache invalidator |
| `question.published.v1` | `question_id` | `{question_id, type, subject_code, reviewed_by, published_at}` | ES indexer (set status=active), Audit, Exam (pre-warm cache nếu nằm trong exam đã publish) |
| `question.deprecated.v1` | `question_id` | `{question_id, deprecated_by, deprecated_at, reason}` | ES indexer (set status=deprecated — không remove để search audit), Exam (flag không cho thêm vào exam mới), Cache invalidator |
| `question.version.created.v1` | `question_id` | `{question_id, version, changed_by, change_reason, created_at}` | Exam (nếu question đang dùng → snapshot version tại attempt start) |
| `question.check_duplicate.requested.v1` | `question_id` | `{question_id, org_id, trigger: enum{create, update, manual}}` | DuplicateDetectionWorker (self) |
| `question.duplicate.detected.v1` | `question_id` | `{question_id, candidates: [{id, similarity, title}], threshold_class: enum{blocking, warning, similar}}` | Self (update flags), Notification (email instructor) |
| `question.import.requested.v1` | `job_id` | `{job_id, org_id, file_s3_key, format, imported_by, requested_at}` | ImportWorker (self) |
| `question.import.completed.v1` | `job_id` | `{job_id, status: enum{completed, partially_failed, failed}, total, success, failed, errors_url?}` | Notification (email) |
| `question.export.requested.v1` / `.completed.v1` | `job_id` | tương tự | ExportWorker / Notification |

**Fire-and-forget — KHÔNG qua outbox** (analytics / stats, mất vài event chấp nhận được):

| Topic (v1) | Key | Payload | Consumer |
| ---------- | --- | ------- | -------- |
| `question.view.v1` | `question_id` | `{question_id, user_id, context: enum{edit, search, preview}, ts}` | Analytics (hot questions, recommendation) |
| `question.search.performed.v1` | `user_id` | `{user_id, query, filters, result_count, latency_ms, ts}` | Analytics (search quality metrics) |

Lý do tách: `question.view.v1` có thể cao 5k/phút — tốn write amp trên outbox. Mất vài event không ảnh hưởng business.

### 11.2 Consumed events (with `processed_events` dedup)

Question Service consume các event bên ngoài, dedup qua `processed_events` (bảng shared `schema.sql §13`):

| Topic | Consumer group | Hành động |
| ----- | -------------- | --------- |
| `ai.question.generated.v1` | `question-service-ai-gen` | Insert Mongo `questions` với `status=review`, `aiGenerated=true`, `version=1`; outbox `question.created.v1` + `question.check_duplicate.requested.v1` |
| `ai.embedding.ready.v1` | `question-service-embed` | Mongo update `EmbeddingRef`; outbox `question.updated.v1` (để ES reindex với vector mới) |
| `ai.quality.scored.v1` | `question-service-quality` | Mongo update `aiQualityScore`, `aiQualityFlags` |
| `exam.attempt.answer_submitted.v1` | `question-service-stats` | Buffer in-memory → flush batch 30s: group by question_id, `$inc: {stats.times_used, stats.correct_count}`, recompute `stats.correct_rate` |
| `analytics.irt.calibrated.v1` | `question-service-irt` | Mongo update `irt.b`, `.a`, `.c`, `.calibrated=true`, `.calibratedAt`, `.responsesCount` |
| `auth.user.deleted.v1` | `question-service-gdpr` | Nếu user là creator: reassign `createdBy` → org admin; nếu `anonymized=true`: set `createdBy=NULL` (DDL cho phép) |
| `exam.published.v1` | `question-service-cache-warmer` | Pre-load question cache (§X.2) |

**Consumer pattern** (mandatory — CLAUDE.md §3 idempotent consumer):

```java
@KafkaListener(topics = "ai.question.generated.v1", groupId = "question-service-ai-gen")
@Transactional  // PG transaction cho processed_events + outbox
public void onAiQuestionGenerated(ConsumerRecord<String, GenericRecord> record) {
    var eventId = UUID.fromString(new String(record.headers().lastHeader("event_id").value(), UTF_8));

    // Idempotency gate
    int inserted = processedEventsRepo.insertIfAbsent(eventId, "question-service-ai-gen", record.topic());
    if (inserted == 0) {
        log.info("duplicate event, skip", kv("event_id", eventId));
        return;
    }

    // Business logic
    var payload = avroMapper.toAiQuestionGenerated(record.value());
    var question = questionFactory.fromAiPayload(payload);
    mongoRepo.insert(question);                       // Mongo write (extern to PG TX)

    outbox.publish(
        "question.created.v1",
        "question.created",
        "question", question.getId().toString(),
        CreatedPayload.from(question),
        question.getId().toString()
    );
    outbox.publish(
        "question.check_duplicate.requested.v1",
        "question.check_duplicate",
        "question", question.getId().toString(),
        new CheckDuplicatePayload(question.getId(), question.getOrgId(), "create"),
        question.getId().toString()
    );
}
```

**⚠️ Cross-store gotcha**: `@Transactional` chỉ bao PG (processed_events + outbox). Mongo write **ngoài** TX — có thể commit Mongo rồi fail PG TX → leave `question` trong Mongo mà không event. Fix: **Mongo write SAU PG insert processed_events** nhưng TRƯỚC outbox publish — nếu Mongo fail throw → PG TX roll back (processed_events rollback) → retry OK. Nếu Mongo OK + outbox fail: reconciler batch job scan `questions.updated_at > watermark` phát hiện, re-emit outbox.

### 11.3 Code pattern — outbox publisher

**Encoding strategy**: payload lưu **JSON (JSONB)** ở bảng `outbox` (dễ debug). **Relayer** encode Avro trước khi publish Kafka (giống Auth §11.2).

**⚠️ Propagation rule** (critical cho đúng outbox invariant):

- **UseCase** (caller): `@Transactional(propagation = REQUIRED)` — mở PG TX cho outbox insert
- **QuestionOutboxPublisher**: `@Transactional(propagation = MANDATORY)` — **bắt buộc đã có TX**, throws `IllegalTransactionStateException` nếu caller gọi ngoài TX

Tại sao MANDATORY không phải REQUIRED? Same reasoning như Auth §11.2: nếu REQUIRED, caller quên `@Transactional` → publisher tự mở TX riêng → outbox row commit tách rời Mongo write / state → half-write bug silent. MANDATORY fail-fast ngay lúc dev.

Enforcement: unit test `testPublisherThrowsWithoutTx()` assert `IllegalTransactionStateException` khi gọi ngoài `@Transactional`.

```java
@Service
@Transactional(propagation = Propagation.MANDATORY)
class QuestionOutboxPublisher {
    private final OutboxRepository repo;
    private final ObjectMapper jsonMapper;

    public void publish(String topic, String eventType,
                        String aggregateType, String aggregateId,
                        Object payload, String partitionKey) {
        UUID eventId = UUID.randomUUID();
        var row = new OutboxRow(
            eventId, aggregateType, aggregateId,
            topic, eventType,
            jsonMapper.valueToTree(payload),
            Map.of("trace_id", MDC.get("trace_id"),
                   "schema_version", "1",
                   "source", "question-service"),
            partitionKey
        );
        repo.save(row);
    }
}

// UseCase — BẮT BUỘC @Transactional
@Service
class CreateQuestionUseCase {
    @Transactional  // REQUIRED default — bao PG outbox
    public QuestionId execute(CreateQuestionCommand cmd, UserContext ctx) {
        // 1. Build + validate domain
        Question q = questionFactory.create(cmd, ctx);
        q.validate();

        // 2. Mongo write (ngoài PG TX nhưng trong cùng method — Mongo single-doc atomic)
        mongoRepo.insert(q);

        // 3. Outbox (PG TX ngữ cảnh sẵn)
        outbox.publish(
            "question.created.v1",
            "question.created",
            "question", q.getId().toString(),
            CreatedPayload.from(q),
            q.getId().toString()  // partitionKey = question_id → consumer order đúng
        );
        return q.getId();
    }
}

// Relayer — pattern giống Auth §11.2, copy-paste khác namespace
@Component
class QuestionOutboxRelayer implements SmartLifecycle {
    private static final long BATCH_BUDGET_MS = 3_000;
    private static final int  BATCH_SIZE      = 500;
    private final KafkaTemplate<String, GenericRecord> kafka;
    private final OutboxRepository repo;
    private final AdvisoryLeaderLock leaderLock;   // pg_try_advisory_lock
    private volatile boolean running;

    @Scheduled(fixedDelay = 100)
    void pollAndPublish() {
        if (!running || !leaderLock.tryAcquire()) return;
        try {
            long deadline = System.currentTimeMillis() + BATCH_BUDGET_MS;
            List<OutboxRow> batch = repo.claimPending(BATCH_SIZE);  // FOR UPDATE SKIP LOCKED
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
            var rec = new ProducerRecord<>(row.topic(), row.partitionKey(), avro);
            rec.headers().add("trace_id", row.headers().get("trace_id").getBytes(UTF_8));
            rec.headers().add("event_id", row.eventId().toString().getBytes(UTF_8));
            rec.headers().add("source", "question-service".getBytes(UTF_8));
            return kafka.send(rec).completable()
                .thenAccept(meta -> repo.markPublished(row.eventId()))
                .exceptionally(ex -> {
                    repo.markFailure(row.eventId(), trim(ex.getMessage(), 500));
                    metrics.publishFailed.increment(row.topic(), classify(ex));
                    return null;
                });
        } catch (Exception serializeErr) {
            repo.markFailure(row.eventId(), "serde: " + trim(serializeErr.getMessage(), 400));
            metrics.publishFailed.increment(row.topic(), "serde");
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override public void stop()  { running = false; kafka.flush(); }
    @Override public void start() { running = true; }
    @Override public boolean isRunning() { return running; }
}
```

### 11.4 Avro schema convention

- File: `shared-contracts/avro/question/question.created.v1.avsc`
- Namespace: `vn.smartquiz.question.v1`
- Rule BACKWARD compat: **chỉ được add field với default**, **không** remove/rename, **không** đổi type. Breaking → topic `.v2` mới, consumer self-migrate.
- CI gate: `./gradlew :shared-contracts:avroCompatCheck`.

### 11.5 Producer/Relayer config

Giống Auth §11.4 (`acks=all`, idempotence, zstd, poll 100ms, batch budget 3s). Thêm:

| Setting | Value | Lý do |
| ------- | ----- | ----- |
| Kafka retention `question.*` | 7 ngày | Consumer dedup window (`processed_events` lưu 7 ngày) |
| Avro compression | zstd | Payload có `content` dài (câu hỏi nhiều text) → compression ratio cao |

### 11.6 Metric outbox bắt buộc

| Metric | Alert |
| ------ | ----- |
| `question_outbox_pending_size` (gauge) | warning > 1k, critical > 10k |
| `question_outbox_publish_lag_seconds` (histogram) | p99 > 5s = critical (vi phạm RPO) |
| `question_outbox_publish_failed_total{reason,topic}` (counter) | spike > 10/min → page |
| `question_processed_events_dedup_total{result}` (counter) | — (debug) |

---

## XII. API ENDPOINTS

### 12.0 API conventions

Convention đồng bộ với Auth §12.0 (`docs/auth-service-design.md`). Mọi endpoint §12.1-12.9 **phải** tuân theo. §12.10 có template (create + search) kèm JSON Schema cho OpenAPI gen.

#### 12.0.1 Versioning

- **Tất cả endpoint REST** có prefix `/api/v1/`. Bảng dưới có thể viết tắt `/questions/...` = `/api/v1/questions/...`.
- **Rule shorthand vs literal**: bảng + prose trong section flow có thể shorthand. **NHƯNG** literal path vào config (CORS, OpenAPI spec, OAuth redirect nếu có, Kubernetes Ingress path matcher) **phải dùng full** `/api/v1/questions/...`.
- **Breaking change**: bump major → `/api/v2/`, giữ `/api/v1/` tối thiểu 6 tháng song song + header `Sunset: <date>` (RFC 8594).
- **gRPC**: proto package `vn.smartquiz.question.v1` — bump package khi breaking.

#### 12.0.2 Content-Type & encoding

- Request `Content-Type: application/json; charset=utf-8` (trừ import endpoint: `multipart/form-data`). Sai → `415`.
- Response luôn `application/json; charset=utf-8` kể cả error (RFC 7807).
- Không nhận trailing slash — canonical `308 Permanent Redirect`.
- Request body size cap: **10 MB** (import endpoint) / **1 MB** (các endpoint khác). Vượt → `413 IMPORT_TOO_LARGE`.

#### 12.0.3 Naming + formats

Giống Auth §12.0.3: path `kebab-case`, JSON field `snake_case`, enum `lowercase_snake`, header `X-Kebab-Case`, UUID canonical lowercase, timestamp ISO 8601 UTC with `Z`, duration seconds int hoặc `PT...` ISO 8601.

#### 12.0.4 Status code conventions

Giống Auth §12.0.4. Tóm tắt endpoint-specific:
- `POST /questions` → **201 Created** + `Location: /api/v1/questions/{id}`
- `POST /questions/{id}/submit-review` → **200** với body state mới (không 204 vì FE cần state_version mới)
- `POST /questions/import` → **202 Accepted** với `{job_id, status_url}`
- `DELETE /questions/{id}` → **204** (soft delete, set status=deprecated)
- `GET /questions/{id}` không tồn tại → **404** + RFC 7807 `QUESTION_NOT_FOUND`
- Deprecate câu đang dùng exam active → **409** `QUESTION_IN_USE`
- Publish câu duplicate ≥ 0.95 → **409** `QUESTION_DUPLICATE_BLOCKED`
- Semantic search query < 2 ký tự → **422** `QUESTION_VALIDATION_FAILED`

#### 12.0.5 Error response — RFC 7807 Problem Details

```json
{
  "type": "https://smartquiz.vn/errors/validation-failed",
  "title": "Dữ liệu câu hỏi không hợp lệ",
  "status": 422,
  "code": "QUESTION_VALIDATION_FAILED",
  "trace_id": "abc123",
  "timestamp": "2026-04-22T10:05:22Z",
  "errors": [
    { "field": "options",
      "code": "MULTIPLE_CORRECT_OPTIONS",
      "message": "Câu single choice chỉ được có 1 option is_correct=true (hiện có 2)" },
    { "field": "metadata.estimated_time_seconds",
      "code": "OUT_OF_RANGE",
      "message": "Phải từ 10s đến 3600s" }
  ]
}
```

FE dùng `errors[].field` highlight field sai trên form tạo/sửa câu hỏi (polymorphic form theo `type`).

#### 12.0.6 Idempotency

POST mutation bắt buộc hỗ trợ `Idempotency-Key` header:

- UUID v4 client sinh. Server cache response (status + body) Redis `q:idempotency:{user_id}:{key}` TTL **24h**.
- Replay cùng key trong 24h → trả cached response, không re-execute.
- Conflict (cùng key, body khác) → `409 QUESTION_IDEMPOTENCY_KEY_REUSE_DIFFERENT_BODY`.

**Endpoint bắt buộc idempotency**: `/questions` (create), `/questions/import`, `/questions/export`, `/questions/{id}/approve`, `/questions/{id}/deprecate`, `/questions/bulk-approve`, `/questions/bulk-update`.

**Endpoint KHÔNG cần** (natively idempotent hoặc không mutation): GET, `/questions/{id}/check-duplicates` (chỉ publish event — duplicate OK).

#### 12.0.7 Rate limit headers

Mọi response endpoint có rate limit (§XIII.3) trả:

```
X-RateLimit-Limit: 30
X-RateLimit-Remaining: 27
X-RateLimit-Reset: 2026-04-22T10:20:00Z
Retry-After: 60                            # chỉ khi 429
```

Body 429 giống Auth §12.0.7:

```json
{
  "type": "https://smartquiz.vn/errors/rate-limit",
  "title": "Quá nhiều request",
  "status": 429,
  "code": "QUESTION_RATE_LIMIT",
  "retry_after": 60,
  "limit": 30,
  "window": "1m",
  "trace_id": "...",
  "timestamp": "..."
}
```

#### 12.0.8 Pagination — cursor-based

Convention **cursor-based** (stable under concurrent insert). Query: `?cursor=<opaque>&limit=20`.

- `limit` max **100**, default **20**. Vượt → `422`.
- Response:
  ```json
  {
    "items": [ ... ],
    "page_info": { "next_cursor": "eyJpZCI6IjEyMzQifQ==", "has_next": true }
  }
  ```
- Không offset-based — trùng/thiếu khi concurrent insert.
- Endpoint list: `/questions/search`, `/questions/{id}/versions`, `/admin/reports`, `/questions/import/{job_id}/errors` (pagination error list).

#### 12.0.9 Standard headers

**Client nên gửi**:

| Header | Required | Purpose |
| ------ | -------- | ------- |
| `Authorization: Bearer <jwt>` | Có — trừ §12.1 public | — |
| `X-Request-Id: <uuid>` | Không — server sinh nếu thiếu | Client request tracking |
| `Idempotency-Key: <uuid>` | Có — endpoint §12.0.6 | — |
| `Accept-Language: vi,en;q=0.8` | Không — default `vi` | Error message i18n |
| `X-Org-Id: <uuid>` | Không — default lấy từ JWT `org_id` | Override khi user multi-org switch |

**Server luôn trả**: `X-Request-Id`, `X-Trace-Id`, `Content-Type`, `Cache-Control: no-store` cho mutation / private resource; `Cache-Control: public, max-age=60` cho `/subjects/*`, `/tags/*` (taxonomy ít đổi).

#### 12.0.10 No response envelope

Response không wrap `{data: ...}`. GET resource trả resource trực tiếp. List endpoint có `items + page_info` (đây là `Page<T>` resource, không phải envelope).

### 12.1 Public endpoints (không cần JWT)

| Method | Path | Body | Response | Rate limit |
| ------ | ---- | ---- | -------- | ---------- |
| GET | `/subjects/public` | — | `200 {items: [...], page_info}` — chỉ subject `is_public=true` (marketplace Phase 3) | 60/min/IP |

Tạm thời Phase 1-2 gần như không có public endpoint — toàn bộ yêu cầu JWT.

### 12.2 CRUD — instructor/admin

| Method | Path | Body / Query | Permission | Notes |
| ------ | ---- | ------------ | ---------- | ----- |
| POST | `/questions` | `QuestionCreateDto` polymorphic theo `type` | `question.create` | 201 + Location |
| GET | `/questions/{id}` | — | `question.read.org` + `@qPolicy.canRead` | Cache 30m |
| GET | `/questions/{id}/versions` | `?cursor=&limit=` | `question.read.org` | Cursor pagination |
| GET | `/questions/{id}/versions/{v}` | — | `question.read.org` | Immutable, cache 2h |
| PATCH | `/questions/{id}` | partial update | `question.update.own` + isOwner HOẶC `question.update.any` + sameOrg | Auto bump version nếu content changed |
| DELETE | `/questions/{id}` | — | `question.deprecate` 🔒 sudo tier A | Soft: set `status=deprecated` |

**Request body polymorphism**: Jackson `@JsonTypeInfo(property="type")` — §III.2.

### 12.3 Lifecycle

| Method | Path | Permission | Sudo |
| ------ | ---- | ---------- | ---- |
| POST | `/questions/{id}/submit-review` | `question.update.own` + isOwner | — |
| POST | `/questions/{id}/approve` | `question.approve` + (reviewer ≠ creator nếu ai_generated) | — |
| POST | `/questions/{id}/reject` + `{reason}` | `question.approve` | — |
| POST | `/questions/{id}/deprecate` | `question.deprecate` | 🔒 A |
| POST | `/questions/{id}/restore` | `question.deprecate` | 🔒 A |
| POST | `/questions/{id}/rollback` `{version}` | `question.update.own` hoặc `question.update.any` | — |

### 12.4 Search & filter

| Method | Path | Query / Body | Permission |
| ------ | ---- | ------------ | ---------- |
| GET | `/questions/search` | `q=`, `subject=`, `tags=`, `type=`, `status=`, `bloom_level=`, `difficulty_range=`, `correct_rate_range=`, `lang=`, `cursor=`, `limit=`, `sort=` | `question.read.org` |
| POST | `/questions/search/semantic` | `{query_text, k=10, min_similarity=0.7, filters}` | `question.read.org` |
| POST | `/questions/search/similar` | `{question_id, k=5}` | `question.read.org` |

Response search: xem template §12.10.2.

### 12.5 Bulk operations

| Method | Path | Body | Permission | Sudo |
| ------ | ---- | ---- | ---------- | ---- |
| POST | `/questions/batch` | `{ids: [...]}` (max 100) | `question.read.org` | — |
| POST | `/questions/bulk-update` | `{ids: [...], patch: {...}}` (max 50) | `question.update.any` | — |
| POST | `/questions/bulk-approve` | `{ids: [...]}` (max 50) | `question.approve` | — |
| POST | `/admin/questions/bulk-deprecate` | `{ids: [...]}` (max 100) | `question.deprecate` | 🔒 B (step-up token bind body_hash) |

### 12.6 Import/Export

| Method | Path | Permission |
| ------ | ---- | ---------- |
| POST | `/questions/import` (multipart) | `question.import` |
| GET | `/questions/import/{job_id}` | `question.import` (owner) |
| GET | `/questions/import/{job_id}/errors` | `question.import` |
| POST | `/questions/export` | `question.export` (+ `question.export.answers` nếu `include_answers=true`) |
| GET | `/questions/export/{job_id}` | owner |
| GET | `/questions/export/templates/{format}` | `question.import` |

### 12.7 Quality, duplicate, reports

| Method | Path | Permission |
| ------ | ---- | ---------- |
| POST | `/questions/{id}/check-duplicates` | `question.update.own` hoặc `question.approve` |
| POST | `/questions/{id}/request-ai-review` | `question.approve` |
| GET | `/questions/{id}/quality-report` | `question.approve` |
| POST | `/questions/{id}/report` | authenticated (any role) |
| GET | `/admin/reports?status=pending&cursor=&limit=` | `question.approve` |
| POST | `/admin/reports/{id}/resolve` | `question.approve` |

### 12.8 Stats + taxonomy

| Method | Path | Permission |
| ------ | ---- | ---------- |
| GET | `/questions/{id}/stats` | `question.read.org` |
| GET | `/questions/stats/popular?subject=&limit=` | `question.read.org` |
| GET | `/subjects?org_id=&parent=` | authenticated |
| POST | `/subjects` | `subject.create` |
| PATCH | `/subjects/{id}` | `subject.update` |
| GET | `/tags?q=&limit=20` | authenticated (autocomplete) |
| POST | `/tags` | `tag.manage` |

### 12.9 gRPC (internal — cho Exam Service)

```proto
syntax = "proto3";
package vn.smartquiz.question.v1;

service QuestionService {
    rpc GetQuestion(GetQuestionRequest) returns (Question);
    rpc BatchGetQuestions(BatchGetRequest) returns (BatchGetResponse);
    // Strip đáp án/explanation/hidden test cases cho student view
    rpc GetQuestionForStudent(GetForStudentRequest) returns (StudentQuestion);
    rpc IncrementUsageCount(UsageIncrement) returns (google.protobuf.Empty);
    rpc StreamQuestionUpdates(Filter) returns (stream QuestionUpdate);
}
```

**Authentication gRPC**: interceptor đọc JWT từ metadata `authorization: Bearer ...` → verify qua JWKS (§XIII.2) → populate `Context` với `UserContext`. Deny nếu thiếu hoặc invalid → `UNAUTHENTICATED`.

**Timeout + CB**: Exam client config 500ms timeout, 10 fail / 30s → CB open 60s (Resilience4j).

**StudentQuestion** bỏ: `is_correct`, `explanation`, `correct_order`, `correct_zone`, `grading_config.accepted_answers`, `reference_solution`, `test_cases[hidden=true]`, `aiQualityScore`, `aiQualityFlags`.

### 12.10 Endpoint contract template — reference cho OpenAPI

Hai endpoint điển hình dưới làm **template** cho tất cả endpoint còn lại. Convention §12.0 apply. OpenAPI spec đầy đủ nằm ở `app/src/main/resources/static/openapi.yaml` — **nguồn truth** BE/FE codegen.

#### 12.10.1 POST /api/v1/questions

**Request:**

```http
POST /api/v1/questions HTTP/2
Host: question.smartquiz.vn
Content-Type: application/json; charset=utf-8
Authorization: Bearer eyJ...
X-Request-Id: 7c9e6f8b-2a3d-4e5f-b1a0-1234567890ab
Idempotency-Key: 11111111-2222-3333-4444-555555555555

{
  "type": "multiple_choice_single",
  "subject_code": "CS101",
  "content": { "text": "Thuật toán sắp xếp nào luôn O(n log n)?" },
  "options": [
    { "id": "opt_a", "text": "Bubble Sort",  "is_correct": false },
    { "id": "opt_b", "text": "Merge Sort",   "is_correct": true,  "explanation": "Luôn O(n log n)" },
    { "id": "opt_c", "text": "Quick Sort",   "is_correct": false,
      "explanation": "Worst case O(n²)" },
    { "id": "opt_d", "text": "Selection Sort","is_correct": false }
  ],
  "metadata": {
    "topic": "algorithms", "subtopic": "sorting",
    "tags": ["sap_xep","thuat_toan"],
    "bloom": "comprehension",
    "language": "vi",
    "estimated_time_seconds": 45
  },
  "irt": { "difficulty_assigned": 3 }
}
```

**Request JSON Schema (OpenAPI 3.1 style — polymorphic):**

```yaml
QuestionCreateRequest:
  oneOf:
    - $ref: '#/components/schemas/McSingleCreate'
    - $ref: '#/components/schemas/McMultiCreate'
    - $ref: '#/components/schemas/TrueFalseCreate'
    - $ref: '#/components/schemas/FillBlankCreate'
    - $ref: '#/components/schemas/MatchingCreate'
    - $ref: '#/components/schemas/OrderingCreate'
    - $ref: '#/components/schemas/ShortAnswerCreate'
    - $ref: '#/components/schemas/EssayCreate'
    - $ref: '#/components/schemas/CodeExecutionCreate'
    - $ref: '#/components/schemas/DragDropCreate'
    - $ref: '#/components/schemas/HotspotCreate'
  discriminator:
    propertyName: type
    mapping:
      multiple_choice_single: '#/components/schemas/McSingleCreate'
      # ... 10 mappings còn lại

McSingleCreate:
  type: object
  required: [type, subject_code, content, options, metadata]
  additionalProperties: false
  properties:
    type:         { type: string, enum: [multiple_choice_single] }
    subject_code: { type: string, pattern: '^[A-Z]{2,10}[0-9]{0,4}$' }
    content:
      type: object
      required: [text]
      properties:
        text:  { type: string, minLength: 5, maxLength: 5000 }
        media: { type: array, items: { $ref: '#/components/schemas/MediaRef' } }
    options:
      type: array
      minItems: 2
      maxItems: 10
      items:
        type: object
        required: [id, text, is_correct]
        additionalProperties: false
        properties:
          id:          { type: string, pattern: '^opt_[a-z0-9_]{1,20}$' }
          text:        { type: string, minLength: 1, maxLength: 500 }
          is_correct:  { type: boolean }
          explanation: { type: string, maxLength: 1000 }
    metadata: { $ref: '#/components/schemas/QuestionMetadata' }
    irt:
      type: object
      properties:
        difficulty_assigned: { type: integer, minimum: 1, maximum: 5 }
    explanation: { type: string, maxLength: 2000 }
    hint:        { type: string, maxLength: 500 }
    reference_links: { type: array, items: { type: string, format: uri } }
```

**Response — 201 Created:**

```http
HTTP/2 201
Content-Type: application/json; charset=utf-8
Location: /api/v1/questions/a0000000-0000-0000-0000-000000000042
X-Request-Id: 7c9e6f8b-2a3d-4e5f-b1a0-1234567890ab
X-Trace-Id: 4bf92f3577b34da6a3ce929d0e0e4736
Cache-Control: no-store

{
  "question_id": "a0000000-0000-0000-0000-000000000042",
  "type": "multiple_choice_single",
  "status": "draft",
  "version": 1,
  "state_version": 1,
  "subject_code": "CS101",
  "content": { "text": "Thuật toán sắp xếp nào luôn O(n log n)?" },
  "options": [ ... ],
  "metadata": { ... },
  "irt": { "difficulty_assigned": 3, "calibrated": false },
  "stats": { "times_used": 0, "correct_rate": 0.0 },
  "ai_generated": false,
  "created_by": "u0000000-0000-0000-0000-000000000004",
  "created_at": "2026-04-22T10:05:22.123Z",
  "updated_at": "2026-04-22T10:05:22.123Z"
}
```

**Response JSON Schema:**

```yaml
QuestionResponse:
  type: object
  required: [question_id, type, status, version, state_version, subject_code, metadata, created_by, created_at, updated_at]
  properties:
    question_id:   { type: string, format: uuid }
    type:          { type: string, enum: [multiple_choice_single, ...] }
    status:        { type: string, enum: [draft, review, active, deprecated] }
    version:       { type: integer, minimum: 1 }
    state_version: { type: integer, minimum: 1 }
    # ... rest — polymorphic union giống McSingleCreate nhưng + thêm meta fields
```

**Error responses:**

| Status | Code | Khi nào |
| ------ | ---- | ------- |
| 400 | `QUESTION_MALFORMED_REQUEST` | JSON parse fail, content-type sai |
| 401 | `AUTH_TOKEN_INVALID` | JWT thiếu/sai |
| 403 | `AUTH_FORBIDDEN` | Thiếu `question.create` permission |
| 409 | `QUESTION_IDEMPOTENCY_KEY_REUSE_DIFFERENT_BODY` | Replay cùng key body khác |
| 422 | `QUESTION_VALIDATION_FAILED` | Semantic fail — body có `errors[]` |
| 429 | `QUESTION_RATE_LIMIT` | 30/phút/user |
| 503 | `QUESTION_DEPENDENCY_DOWN` | Mongo hoặc PG outbox unavailable (CB open) |

#### 12.10.2 GET /api/v1/questions/search

**Request:**

```http
GET /api/v1/questions/search?q=thu%E1%BA%ADt%20to%C3%A1n%20s%E1%BA%AFp%20x%E1%BA%BFp
   &subject=CS101
   &tags=sap_xep
   &type=multiple_choice_single
   &status=active
   &bloom_level=comprehension
   &difficulty_range=0.0,2.0
   &cursor=eyJpZCI6IjEyMyJ9
   &limit=20
   &sort=relevance_desc HTTP/2
Authorization: Bearer eyJ...
```

**Request schema (query params):**

```yaml
QuestionSearchParams:
  type: object
  properties:
    q:                  { type: string, minLength: 2, maxLength: 200 }
    subject:            { type: string }
    tags:               { type: string, description: "CSV of tag codes" }
    type:               { type: string, enum: [multiple_choice_single, ...] }
    status:             { type: string, enum: [draft, review, active, deprecated] }
    bloom_level:        { type: string, enum: [knowledge, comprehension, application, analysis, synthesis, evaluation] }
    difficulty_range:   { type: string, pattern: '^-?[0-9.]+,-?[0-9.]+$' }
    correct_rate_range: { type: string, pattern: '^[0-9.]+,[0-9.]+$' }
    lang:               { type: string, enum: [vi, en] }
    cursor:             { type: string }
    limit:              { type: integer, minimum: 1, maximum: 100, default: 20 }
    sort:               { type: string, enum: [relevance_desc, created_desc, times_used_desc, difficulty_asc] }
```

**Response 200:**

```json
{
  "items": [
    {
      "question_id": "a0000000-0000-0000-0000-000000000042",
      "type": "multiple_choice_single",
      "subject_code": "CS101",
      "content": { "text": "Thuật toán ..." },
      "metadata": { "topic": "algorithms", "tags": ["sap_xep"], "bloom": "comprehension" },
      "stats": { "times_used": 245, "correct_rate": 0.82 },
      "_score": 4.21
    }
  ],
  "aggregations": {
    "by_type":    [{ "key": "multiple_choice_single", "count": 120 }],
    "by_bloom":   [{ "key": "analysis", "count": 88 }],
    "by_subject": [{ "key": "CS101", "count": 95 }]
  },
  "page_info": {
    "next_cursor": "eyJpZCI6IjE4MyJ9",
    "has_next": true
  },
  "total_approx": 245
}
```

**Note**: `total_approx` từ ES — có thể lệch nhỏ vì ES replication; không dùng cho business logic critical.

#### 12.10.3 Pattern tổng hợp (các endpoint còn lại)

Mỗi endpoint OpenAPI phải có:

1. **Operation ID** `{verb}{Resource}` — `createQuestion`, `searchQuestions`, `deprecateQuestion`, …
2. **Summary** 1 dòng + **Description** chi tiết link sang design doc section
3. **Request body/query schema** với `additionalProperties: false`, `required`, constraints
4. **Response schema** per status code — 2xx + tất cả error 4xx
5. **Examples** happy path + error
6. **Security** — `security: [{ bearerAuth: [] }]`
7. **`x-codeSamples`** (optional) curl/JS/Python
8. **Rate limit** trong description hoặc `x-ratelimit` extension

Tooling:
- BE: `springdoc-openapi` tự-gen từ `@RestController` + `@Operation`, merge với `openapi.yaml` static
- FE: `openapi-typescript-codegen` → TS client tự động
- Contract test: Spring Cloud Contract hoặc Pact consumer-driven

---

## XIII. SECURITY HARDENING

### 13.1 Sudo mode + step-up token

**Tier A — `auth_time` threshold 5 phút**: endpoint thay đổi cấu hình ngân hàng câu hỏi — `/questions/{id}/deprecate`, `/questions/{id}/restore`, `DELETE /questions/{id}`.

**Tier B — per-action step-up token bound body_hash** (chỉ highest-risk bulk ops):
- `POST /admin/questions/bulk-deprecate` (có thể khoá hàng trăm câu hỏi → bài thi active có thể miss question)

Flow giống Auth §5.1.1 — Question **không phát** challenge token, chỉ verify. Auth Service phát qua `POST /api/v1/auth/step-up/init` với `action: "question.bulk_deprecate"`, body_hash bind đến body request. Question receive `X-Step-Up-Token` header → verify qua `StepUpVerifier` (gRPC sang Auth hoặc JWK verify local nếu token RS256).

### 13.2 JWT verification — consumer side

Question Service **không phát** JWT, chỉ verify. Cấu hình:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: https://auth.smartquiz.vn/.well-known/jwks.json
          issuer-uri: https://auth.smartquiz.vn
          cache-duration: 1h
```

**⚠️ Trap quan trọng** (copy từ Auth §3.3 — cùng bug mặc định ở Spring Security):

```java
// SecurityConfig.java
@Bean
JwtAuthenticationConverter jwtAuthConverter() {
    var authoritiesConverter = new JwtGrantedAuthoritiesConverter();
    authoritiesConverter.setAuthoritiesClaimName("authorities");  // KHÔNG phải scope/scp
    authoritiesConverter.setAuthorityPrefix("");                  // KHÔNG prefix SCOPE_
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
    converter.setPrincipalClaimName("sub");
    return converter;
}

@Bean
SecurityFilterChain api(HttpSecurity http, JwtAuthenticationConverter conv) throws Exception {
    return http
        .authorizeHttpRequests(a -> a
            .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
            .requestMatchers("/v3/api-docs/**", "/swagger-ui/**").permitAll()
            .anyRequest().authenticated())
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(conv)))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(CsrfConfigurer::disable)   // stateless Bearer
        .build();
}
```

Nếu không config `setAuthoritiesClaimName("authorities")` + empty prefix → `hasAuthority('question.create')` **không bao giờ match** → 403 silent everywhere. Test end-to-end qua Testcontainers với real JWT từ Auth Service.

**Invalidation cache permission**: consume `auth.role.changed.v1` → evict Redis key `user:{user_id}:permissions` (TTL 60s default).

### 13.3 RBAC matrix (permission-based)

Default grant cho 4 system roles — chỉ **seed** cấu hình, không enforcement. Seed trong `database/postgresql/seed.sql` `role_permissions`. Org admin có thể tạo custom role (Phase 2).

| Action | Permission code | student | instructor | admin | platform_admin |
| ------ | --------------- | ------- | ---------- | ----- | -------------- |
| View câu active (via attempt only) | (stripped via Jackson View) | ✔ | — | — | — |
| List org bank | `question.read.org` | ✖ | ✔ | ✔ | ✔ |
| Create | `question.create` | ✖ | ✔ | ✔ | ✔ |
| Edit own | `question.update.own` | ✖ | ✔ | ✔ | ✔ |
| Edit others in org | `question.update.any` | ✖ | ✖ | ✔ | ✔ |
| Submit for review | `question.update.own` | ✖ | ✔ | ✔ | ✔ |
| Approve review | `question.approve` | ✖ | ✔ (≠ owner nếu `ai_generated`) | ✔ | ✔ |
| Deprecate | `question.deprecate` 🔒 A | ✖ | ✖ | ✔ | ✔ |
| Bulk-deprecate | `question.deprecate` 🔒 B | ✖ | ✖ | ✔ | ✔ |
| Import | `question.import` | ✖ | ✔ | ✔ | ✔ |
| Export | `question.export` | ✖ | ✔ | ✔ | ✔ |
| Export với answer | `question.export.answers` | ✖ | ✖ | ✔ | ✔ |
| Report | `question.report` | ✔ | ✔ | ✔ | ✔ |
| Resolve report | `question.approve` | ✖ | ✔ | ✔ | ✔ |
| Delete permanent (GDPR) | (chỉ platform via script) | ✖ | ✖ | ✖ | ✔ |

```java
@PreAuthorize("hasAuthority('question.update.own') and @qPolicy.isOwner(authentication, #id)")
@PatchMapping("/api/v1/questions/{id}")
public QuestionResponse updateQuestion(@PathVariable UUID id,
                                        @RequestBody @Valid QuestionUpdateRequest req,
                                        Authentication auth) { ... }

@PreAuthorize("hasAuthority('question.deprecate') and @sudoCheck.recentAuth(authentication, 300)")
@PostMapping("/api/v1/questions/{id}/deprecate")
public void deprecate(@PathVariable UUID id, Authentication auth) { ... }
```

### 13.4 Data isolation (multi-tenant)

Mongo query mặc định filter `org_id ∈ {user.org_id, null}` qua aspect/interceptor:

```java
@Aspect
@Component
class OrgScopedQueryAspect {
    @Around("@annotation(org.springframework.data.mongodb.repository.Query)")
    public Object injectOrgFilter(ProceedingJoinPoint pjp) { ... }
}

// Hoặc repo method tự thêm:
public List<Question> searchInOrg(Query q, UserContext ctx) {
    var filter = Filters.and(
        Filters.or(
            Filters.eq("org_id", ctx.orgId()),
            Filters.eq("org_id", null)           // shared marketplace
        ),
        q.toMongoFilter()
    );
    return mongo.find(filter);
}
```

Platform admin (`platform_role=super_admin` claim) → bypass org filter.

### 13.5 Strip sensitive fields cho student

Jackson View:

```java
public class QuestionViews {
    public static class Student {}
    public static class Instructor extends Student {}
    public static class Owner extends Instructor {}
}

public class Option {
    @JsonView(QuestionViews.Student.class)    private String id;
    @JsonView(QuestionViews.Student.class)    private String text;
    @JsonView(QuestionViews.Instructor.class) private boolean isCorrect;
    @JsonView(QuestionViews.Owner.class)      private String explanation;
}
```

**gRPC `GetQuestionForStudent`** tự động dùng `Student` view — strip `is_correct`, `explanation`, `correct_order`, `correct_zone`, `grading_config.accepted_answers`, `reference_solution`, test_cases hidden, `aiQualityScore`, `aiQualityFlags`.

### 13.6 Rate limit (Redis sliding window — Lua atomic, pattern Auth §9.1)

| Endpoint | Key | Limit |
| -------- | --- | ----- |
| POST /questions | `q:rl:create:{user_id}` | 30 / phút |
| PATCH /questions/{id} | `q:rl:update:{user_id}` | 60 / phút |
| POST /questions/import | `q:rl:import:{user_id}` | 5 / giờ |
| POST /questions/export | `q:rl:export:{user_id}` | 10 / giờ |
| GET /questions/search | `q:rl:search:{user_id}` | 120 / phút |
| POST /questions/search/semantic | `q:rl:semantic:{user_id}` | 10 / phút |
| gRPC GetQuestion | `q:rl:grpc:{svc}` | 5000 / phút / service (Exam) |

### 13.7 Encryption + secrets

| Dữ liệu | Cách | Key |
| ------- | ---- | --- |
| `questions.explanation` (nếu chứa PII — hiếm) | app-level mask log (không encrypt at rest) | — |
| S3 upload file import/export | SSE-KMS | AWS KMS |
| MongoDB disk | Storage encryption (MongoDB Enterprise hoặc filesystem LUKS) | — |
| Redis | TLS in-transit, disk encryption host-level | — |
| Kafka | TLS + SASL_SCRAM | — |

Secret rotation:
- Mongo credentials: Vault dynamic secrets (Phase 2) — 30 ngày
- Redis password: 90 ngày
- S3 access key: IAM Role (không rotate thủ công — IRSA trong EKS)

### 13.8 HTTP security headers

```
Strict-Transport-Security: max-age=31536000; includeSubDomains; preload
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Referrer-Policy: strict-origin-when-cross-origin
Permissions-Policy: geolocation=(), camera=()
Content-Security-Policy: default-src 'none'; connect-src 'self'; img-src 'self' https://media.smartquiz.vn
```

### 13.9 Security test cases bắt buộc (checklist §XVII.4)

- [ ] JWT với `alg:none`, `alg:HS256` (confused deputy)
- [ ] JWT signed bằng public key JWKS
- [ ] Org isolation: user org A truy cập `GET /questions/{id}` với id của org B → 403/404
- [ ] Platform admin bypass đúng scope (chỉ endpoint có ghi `platform-scope`)
- [ ] gRPC metadata authorization (missing → UNAUTHENTICATED; wrong org → PERMISSION_DENIED)
- [ ] Sudo mode: `deprecate` sau khi `auth_time > 5 phút` → 403 `AUTH_SUDO_REQUIRED`
- [ ] Step-up token: bulk-deprecate với body_hash mismatch → 403 `AUTH_STEPUP_INVALID`
- [ ] NoSQL injection qua query param (Mongo `$where`, `$regex`)
- [ ] File upload type spoof (.exe đổi .csv) → reject ở MIME sniffer
- [ ] Import file XLSX với formula injection (=cmd|' /C calc'!A0) → sanitize trước render
- [ ] Idempotency-Key replay attack (key cũ, body khác)
- [ ] Rate limit bypass qua X-Forwarded-For spoofing (trust only gateway header)
- [ ] Race: 2 thread cùng approve (state_version fencing → 1 thắng, 1 fail 409)
- [ ] Consumer idempotency: re-deliver `ai.question.generated.v1` 3 lần → chỉ 1 Mongo insert
- [ ] Outbox poisoning: payload malformed → relayer mark last_error, không block queue

---

## XIV. OBSERVABILITY

Stack CLAUDE.md §2: **Micrometer → Prometheus**, **OpenTelemetry OTLP**, **Loki**.

### 14.1 Metrics (Prometheus, expose qua `/actuator/prometheus`)

| Metric | Type | Label |
| ------ | ---- | ----- |
| `question_crud_total` | counter | `op=create\|update\|delete\|approve`, `type` |
| `question_get_duration_seconds` | histogram | `source=cache\|mongo`, `type` |
| `question_grpc_duration_seconds` | histogram | `rpc=get\|batch_get\|get_for_student` |
| `question_grpc_batch_size` | histogram | — |
| `question_search_duration_seconds` | histogram | `kind=text\|semantic\|faceted` |
| `question_cache_hit_ratio` | gauge | — |
| `question_import_jobs_total` | counter | `status=success\|failed\|partial`, `format` |
| `question_import_processing_duration_seconds` | histogram | `format` |
| `question_ai_generated_total` | counter | `status=approved\|rejected\|pending` |
| `question_duplicate_detected_total` | counter | `class=blocking\|warning\|similar` |
| `question_status_transitions_total` | counter | `from`, `to` |
| `question_es_sync_lag_seconds` | gauge | — |
| `question_outbox_pending_size` | gauge | — (xem §XI.6) |
| `question_outbox_publish_lag_seconds` | histogram | — |
| `question_processed_events_dedup_total` | counter | `result=duplicate\|new`, `topic` |
| `question_stats_buffer_size` | gauge | — (answer-submitted consumer buffer) |

### 14.2 Tracing (OpenTelemetry)

- `io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter`
- Export OTLP gRPC → `otel-collector:4317` → fanout Tempo/Jaeger
- Span attribute quan trọng: `question.id`, `question.type`, `question.org_id`, `question.search.kind`, `question.search.k`, `question.import.format`, `question.import.total`
- **Cấm** set raw content, answers, explanation, test case expected làm attribute
- Propagation qua gateway/Exam gRPC: W3C `traceparent` header/metadata

### 14.3 SLO (reference ADR-001 §1 + §I.4)

| SLI | Target | Ghi chú |
| --- | ------ | ------- |
| `gRPC GetQuestion` cache-hit p99 | < 10ms | Exam start attempt critical path |
| `gRPC GetQuestion` cache-miss p99 | < 50ms | Mongo index lookup |
| `BatchGetQuestions` 50 câu p99 | < 100ms | MGET Redis |
| Search text p99 | < 200ms | ES |
| Search semantic p99 | < 300ms | ES KNN |
| Cache hit ratio | > 90% | trong window kỳ thi; ngoài window best-effort |
| ES sync lag p99 | < 5s | RPO critical event |
| Outbox publish lag p99 | < 5s | ADR-001 |
| Availability (window kỳ thi) | **99.95%** | ≤ 22 phút/tháng — Exam blocking dep |
| Availability (ngoài kỳ thi) | 99.9% | ≤ 43,8 phút/tháng |

### 14.4 Logs — structured JSON + MDC

Logback `net.logstash.logback:logstash-logback-encoder`. Filter `MdcFilter` set MDC keys: `trace_id`, `span_id`, `user_id`, `org_id`, `request_id`, `client_ip`, `question_id` (khi UseCase chạm câu hỏi cụ thể).

```json
{
  "ts": "2026-04-22T10:05:22.123Z",
  "level": "INFO",
  "service": "question-service",
  "trace_id": "7c9e6f8b2a3d4e5f...",
  "span_id": "1a2b3c4d...",
  "request_id": "req-abc123",
  "user_id": "u0000000-0000-0000-0000-000000000004",
  "org_id": "11111111-1111-1111-1111-111111111111",
  "question_id": "a0000000-0000-0000-0000-000000000042",
  "event": "question.status_transition",
  "from": "review",
  "to": "active",
  "ai_generated": true
}
```

**Masking filter bắt buộc** (Logback `MaskingPatternLayout`):
- Regex mask: `accepted_answers`, `correct_order`, `correct_zone`, `is_correct`, `reference_solution`, `test_cases`, `explanation` (khi log raw body)
- Chặn log full question body mặc định — chỉ log `question_id` + metadata

Log ship qua promtail → Loki (retention 14 ngày). Index `service`, `level`, `event`, `user_id`, `question_id`.

### 14.5 Alerts

| Alert | Điều kiện | Severity |
| ----- | --------- | -------- |
| `QuestionServiceDown` | up == 0 / 2 phút | critical |
| `CacheHitRateLow` | `question_cache_hit_ratio` < 0.7 / 10 phút | warning |
| `EsIndexingLag` | `question_es_sync_lag_seconds` p99 > 30s / 5 phút | warning |
| `EsIndexingLagCritical` | > 60s / 2 phút | critical (RPO risk) |
| `OutboxBacklog` | `question_outbox_pending_size` > 10k | critical (ADR-001) |
| `OutboxPublishLag` | p99 > 5s | critical |
| `ImportJobStuck` | pending > 1 giờ | warning |
| `SearchLatencyHigh` | text p99 > 500ms / 5 phút | warning |
| `DuplicateBlockSpike` | `question_duplicate_detected_total{class="blocking"}` > 20/min | warning (khả năng AI generate spam) |
| `ConsumerLagHigh` | Kafka consumer lag > 10k / 5 phút (topic `ai.question.generated.v1`) | warning |
| `SLOBurnRate` | error budget tiêu > 2%/hour trong window kỳ thi | critical |

---

## XV. ERROR HANDLING

### 15.1 Format lỗi chuẩn (RFC 7807 Problem Details)

Cùng format Auth §15.1 — `type`, `title`, `status`, `code`, `trace_id`, `timestamp`. Với 422 thêm `errors[]` field-level.

### 15.2 Bảng mã lỗi

| Code | HTTP | Ý nghĩa | Khi nào dùng |
| ---- | ---- | ------- | ------------ |
| `QUESTION_MALFORMED_REQUEST` | 400 | JSON parse fail, content-type sai | — |
| `QUESTION_VALIDATION_FAILED` | 422 | Semantic fail (option count, required field) | Body có `errors[]` field-level — §12.0.5 |
| `QUESTION_NOT_FOUND` | 404 | — | — |
| `QUESTION_ACCESS_DENIED` | 403 | Khác org, không platform_admin | Dùng khi GET/PATCH trả 404 chung chung không cho biết lý do thật (chống enumeration). 403 chỉ khi org-level info cho phép biết câu có tồn tại nhưng không phép |
| `QUESTION_INVALID_STATE_TRANSITION` | 409 | E.g. approve câu đang draft, deprecate câu deprecated | — |
| `QUESTION_STATE_VERSION_MISMATCH` | 409 | Fencing token conflict | Retry từ client, đọc state_version mới |
| `QUESTION_DUPLICATE_BLOCKED` | 409 | Similarity ≥ 0.95 | Response body kèm `candidates[]` |
| `QUESTION_IN_USE` | 409 | Cố deprecate câu đang dùng exam active | Body kèm `active_exam_count`, `active_attempt_count` |
| `QUESTION_IDEMPOTENCY_KEY_REUSE_DIFFERENT_BODY` | 409 | Idempotency key đã dùng với body khác | — |
| `QUESTION_RATE_LIMIT` | 429 | Vượt quota §XIII.6 | Header `Retry-After`, body `retry_after/limit/window` — §12.0.7 |
| `IMPORT_FORMAT_INVALID` | 400 | File không parse được | — |
| `IMPORT_TOO_LARGE` | 413 | > 10MB hoặc > 10k câu | — |
| `IMPORT_JOB_NOT_FOUND` | 404 | — | — |
| `SEARCH_QUERY_TOO_SHORT` | 422 | `q.length < 2` | — |
| `SEARCH_INVALID_CURSOR` | 400 | Cursor không parse được (opaque base64 JSON) | — |
| `AUTH_TOKEN_INVALID` / `AUTH_TOKEN_EXPIRED` | 401 | JWT reject ở JWKS verify | Forward từ filter — dùng code chung Auth §15.2 |
| `AUTH_FORBIDDEN` | 403 | Thiếu permission | — |
| `AUTH_SUDO_REQUIRED` | 403 | Cần re-auth 5 phút (tier A) | Body kèm `reauth_url: /api/v1/auth/reauth` |
| `AUTH_STEPUP_REQUIRED` | 400 | Cần step-up token (tier B) | Body kèm `init_url: /api/v1/auth/step-up/init` |
| `AUTH_STEPUP_INVALID` | 403 | Step-up token sai/expired/body_hash mismatch | — |
| `QUESTION_DEPENDENCY_DOWN` | 503 | Mongo/ES/Kafka unavailable (CB open) | Header `Retry-After: 30` |
| `QUESTION_INTERNAL` | 500 | Unhandled | Log chi tiết, trace_id |

---

## XVI. DEPLOYMENT & INFRASTRUCTURE

### 16.1 Kubernetes manifest (tóm tắt)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: question-service, namespace: smartquiz }
spec:
  replicas: 3
  strategy: { type: RollingUpdate, rollingUpdate: { maxSurge: 1, maxUnavailable: 0 } }
  template:
    spec:
      containers:
        - name: question
          image: registry.smartquiz.vn/question-service:1.0.0
          ports:
            - { name: http, containerPort: 3003 }
            - { name: grpc, containerPort: 4003 }
            - { name: mgmt, containerPort: 9003 }
          env:
            - { name: SPRING_PROFILES_ACTIVE, value: prod }
            - { name: SPRING_THREADS_VIRTUAL_ENABLED, value: "true" }
          envFrom:
            - configMapRef: { name: question-config }
            - secretRef:    { name: question-secrets }
          resources:
            requests: { cpu: 500m, memory: 768Mi }
            limits:   { cpu: 2,    memory: 1536Mi }
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: mgmt }
            initialDelaySeconds: 30
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: mgmt }
            periodSeconds: 5
          startupProbe:
            httpGet: { path: /actuator/health/readiness, port: mgmt }
            failureThreshold: 40
```

+ `HorizontalPodAutoscaler` (CPU 70%, custom metric `question_cache_hit_ratio < 0.7` trigger, min 3, max 20)
+ `PodDisruptionBudget` minAvailable=2
+ `NetworkPolicy` ingress từ API Gateway + Exam Service; egress đến MongoDB/PostgreSQL/Redis/Kafka/Elasticsearch/S3/AI-Service/Media-Service

### 16.2 Cấu hình môi trường

| Key | Prod | Dev |
| --- | ---- | --- |
| `MONGODB_URI` | Vault secret | `mongodb://localhost:27017/smartquiz` |
| `PG_URL` (outbox) | Vault | `jdbc:postgresql://localhost:5432/smartquiz` |
| `REDIS_URL` | `redis://redis:6379/3` | `redis://localhost:6379/3` |
| `KAFKA_BROKERS` | `kafka-0:9092,kafka-1:9092,kafka-2:9092` | `localhost:9092` |
| `ELASTICSEARCH_URL` | `https://es-cluster:9200` | `http://localhost:9200` |
| `APICURIO_REGISTRY_URL` | `https://apicurio:8080/apis/registry/v2` | `http://localhost:8080/apis/registry/v2` |
| `JWT_JWKS_URI` | `https://auth.smartquiz.vn/.well-known/jwks.json` | `http://localhost:3001/.well-known/jwks.json` |
| `S3_BUCKET_IMPORT` | Vault | `smartquiz-import-dev` |
| `AI_SERVICE_URL` | `http://ai-service:8201` | `http://localhost:8201` |

### 16.3 Scaling & thread model

- Stateless (state ở Mongo + PG + Redis + ES) → scale horizontal thoải mái
- **Full I/O-bound** → Virtual Threads via `spring.threads.virtual.enabled=true`. Không có CPU-bound hotspot như Argon2 → không cần thread pool riêng.
- Peak load: 10k start/phút × 50 câu × `BatchGet` cache hit 95% → 167 × 50 × 5% cache-miss = 417 Mongo query/s. 3-5 pod đủ.
- HPA scale out tự động khi peak thi qua custom metric cache hit ratio.

### 16.4 Disaster recovery

| Scenario | RPO | RTO | Biện pháp |
| -------- | --- | --- | --------- |
| Mất 1 pod | 0 | < 5s | K8s reschedule |
| Mất MongoDB | < 1 phút | < 15 phút | MongoDB replica set 3 node + PITR via oplog + snapshots |
| Mất PostgreSQL (outbox) | < 1 phút | < 15 phút | Patroni streaming replication + PITR |
| Mất Elasticsearch | N/A (read-only đối với search) | < 30 phút | ES cluster 3 node; search fallback Mongo text (degraded, slow) |
| Mất Redis | 0 (critical data ở Mongo) | < 2 phút | Redis Sentinel/Cluster |
| Mất Kafka | Event buffer trong outbox (pending) | < 15 phút | Kafka 3 broker min; relayer pause tự động, resume sau recovery |
| S3 region outage | Import/export không hoạt động | < 1 giờ | Cross-region replication bucket (Phase 3) |

---

## XVII. TESTING STRATEGY

### 17.1 Pyramid + coverage gate (JaCoCo)

```
          E2E (10%)  ← create question → publish → search flow
       Integration (30%)  ← Testcontainers: Mongo + PG + Redis + Kafka + ES + Apicurio
   Unit tests (60%)  ← grading per type, validation, state machine
```

| Layer | JaCoCo gate (line coverage) |
| ----- | --------------------------- |
| `domain/*` (pure logic, no IO) | ≥ **80%** |
| `application/*` (UseCase) | ≥ **70%** |
| `infrastructure/*` | best-effort (integration test phủ) |
| Global | ≥ **75%** |

CI fail nếu coverage regress > 2% so với baseline branch `main`.

### 17.2 Test tools

| Layer | Tool |
| ----- | ---- |
| Unit | JUnit 5, AssertJ, Mockito |
| Integration | **Testcontainers** (MongoDB 7, PostgreSQL 16, Redis 7, Confluent Kafka, Elasticsearch 8, Apicurio), **WireMock** (AI Service + Media Service stub) |
| Contract | **Spring Cloud Contract** (producer-side stub cho Exam Service consumer gRPC) + Avro compat check qua Apicurio |
| Security | OWASP ZAP baseline scan trong CI; Burp định kỳ; `@security-engineer` review trước merge |
| Load | k6 — target `BatchGetQuestions` 500 RPS với p99 < 100ms |

### 17.3 Unit — grading per type

```java
@Test
void fillBlank_regex_match_case_insensitive() {
    var q = new FillBlankQuestion(
        GradingConfig.regex("(?i)merge\\s+sort", /*caseSensitive=*/false, /*trimWhitespace=*/true));
    var payload = AnswerPayload.of("{\"text\":\"Merge Sort\"}");
    assertThat(q.autoGrade(payload).correct()).isTrue();
}

@Test
void mcMulti_strict_policy_any_wrong_is_zero() {
    var q = new McMultiQuestion(
        opts("A+","B+","C","D"), /*partialCredit=*/false, /*points=*/10);
    var payload = AnswerPayload.of("{\"selected_options\":[\"A\",\"C\"]}");  // 1 đúng, 1 sai
    assertThat(q.autoGrade(payload).earnedPercent()).isEqualTo(0.0);
}

@Test
void stateMachine_approve_ai_generated_rejects_same_creator() {
    var q = QuestionFactory.aiGenerated("user-1");
    q.submitForReview();
    assertThatThrownBy(() -> q.approve(new UserId("user-1")))
        .isInstanceOf(InvalidStateTransitionException.class)
        .hasMessageContaining("four-eyes");
}
```

### 17.4 Integration test bắt buộc — outbox + Kafka (ADR-001 §impl.5)

```java
@SpringBootTest
@Testcontainers
class CreateQuestionOutboxIntegrationTest {
    @Container static MongoDBContainer   mongo = new MongoDBContainer("mongo:7");
    @Container static PostgreSQLContainer<?> pg  = new PostgreSQLContainer<>("postgres:16");
    @Container static KafkaContainer     kafka = new KafkaContainer(...);
    @Container static GenericContainer<?> apicurio = ...;
    @Container static ElasticsearchContainer es = ...;

    @Test
    void create_question_publishes_event_even_if_relayer_crashes_mid_flight() {
        // given
        var cmd = QuestionFactory.validCreateCommand();

        // when
        var qid = createQuestionUseCase.execute(cmd, ctx);

        // simulate relayer crash before publish
        relayerTestHook.pauseBefore(PublishStage.KAFKA_SEND);
        Thread.sleep(500);
        relayerTestHook.resume();

        // then — event eventually on Kafka exactly once
        await().atMost(10, SECONDS).untilAsserted(() -> {
            var records = kafkaConsumer.poll("question.created.v1");
            assertThat(records).hasSize(1);
            assertThat(records.get(0).key()).isEqualTo(qid.toString());
        });
        assertThat(outboxRepo.pendingCount()).isZero();
    }

    @Test
    void ai_question_consumed_twice_inserts_once() {
        var record = fixtureKafkaRecord("ai.question.generated.v1", fixture.aiPayload());
        consumer.onAiQuestionGenerated(record);
        consumer.onAiQuestionGenerated(record);   // duplicate delivery

        assertThat(mongoRepo.count()).isEqualTo(1);
        assertThat(processedEventsRepo.count("question-service-ai-gen")).isEqualTo(1);
    }
}
```

### 17.5 Security test cases bắt buộc

Đã liệt kê §XIII.9. Viết thành `@Test` trong `SecurityIntegrationTest` hoặc scan OWASP ZAP rule set.

### 17.6 Contract test

Spring Cloud Contract — Question là producer gRPC, Exam là consumer. Stub JAR publish mavenLocal; Exam test pick stub.

```groovy
Contract.make {
    request {
        method 'POST'
        url '/grpc/GetQuestion'
        body([ question_id: 'a0000000-0000-0000-0000-000000000042' ])
    }
    response {
        status 200
        body([ question_id: 'a0000000-...', type: 'multiple_choice_single', status: 'active' ])
    }
}
```

### 17.7 Load test

```js
// k6
export let options = {
  stages: [
    { duration: '2m', target: 500 },  // ramp to 500 RPS
    { duration: '5m', target: 500 },  // hold
    { duration: '1m', target: 0 },
  ],
  thresholds: {
    'http_req_duration{endpoint:batch_get}':    ['p(99)<100'],
    'http_req_duration{endpoint:search}':       ['p(99)<200'],
  },
};
```

---

## XVIII. ROADMAP & OPEN QUESTIONS

### 18.1 Gate trước khi scaffold (CLAUDE.md §9)

**Phải xong trước khi bắt tay code UseCase đầu tiên:**

- [x] MongoDB schema + validator (`database/mongodb/schema.js`)
- [x] Elasticsearch mapping (`database/elasticsearch/schema.json`)
- [x] PostgreSQL `subjects` + outbox/processed_events (`schema.sql`)
- [x] ADR-001 (SLA + RPO + outbox)
- [ ] **OpenAPI 3.1 spec** (`app/src/main/resources/static/openapi.yaml`) — endpoint MVP §18.2, review trước khi FE code question bank
- [ ] Avro schema MVP trong `shared-contracts/avro/question/`: `question.created.v1`, `question.updated.v1`, `question.published.v1`, `question.deprecated.v1`, `question.version.created.v1`
- [ ] Register Avro schema lên Apicurio dev instance (BACKWARD compat)
- [ ] `shared-outbox-starter` Gradle plugin sẵn sàng (dùng chung với Auth/Exam — ADR-001 §consequences)
- [ ] ADR quyết định **cross-datastore outbox** cho Question (Mongo write + PG outbox) — §XI lead paragraph. Ghi rõ risk window + reconciler pattern.

### 18.2 MVP (Q2/2026)

- [ ] Flyway migration V1 cho Question-service-specific PG delta (nếu có)
- [ ] CRUD 11 types với validation Jakarta Bean Validation + type-specific policy
- [ ] State machine draft/review/active/deprecated với fencing token `state_version`
- [ ] gRPC `GetQuestion`, `BatchGetQuestions`, `GetQuestionForStudent` cho Exam
- [ ] Search full-text ES (faceted)
- [ ] Cache Redis + pub/sub invalidation (L1 caffeine + L2 Redis)
- [ ] Version history immutable
- [ ] **Outbox relayer** chạy inside pod với advisory-lock leader election
- [ ] Publish `question.created/updated/published/deprecated.v1` qua outbox
- [ ] Consume `auth.role.changed.v1` để evict perm cache
- [ ] Consume `exam.attempt.answer_submitted.v1` cho stats batch 30s

### 18.3 Phase 2 (Q3/2026)

- [ ] Semantic search KNN (ES dense_vector) + hybrid rerank
- [ ] AI-generated question workflow (consume `ai.question.generated.v1`) + quality check
- [ ] Duplicate detection (consume `ai.embedding.ready.v1` → KNN query)
- [ ] Import CSV/Excel/GIFT (3 format MVP)
- [ ] Question reports flow (student + instructor)
- [ ] Custom role per-org (Phase 2 của RBAC)
- [ ] Reconciler batch job scan Mongo watermark → outbox miss detection

### 18.4 Phase 3 (Q4/2026)

- [ ] QTI 2.1 import/export
- [ ] MoodleXML migration tool
- [ ] Multi-language question (auto-translate UI, 1 câu hỏi nhiều version theo `language`)
- [ ] A/B test framework (2 variant câu hỏi cùng kiểm tra cùng concept)
- [ ] DIF (Differential Item Functioning) detection → flag câu bất công theo demographic
- [ ] Parameterized questions (Toán: random số, render time compute answer)
- [ ] Marketplace: `org_id=NULL` + `is_public=true`, audit access log

### 18.5 Open questions

1. **Cross-datastore outbox (Mongo + PG)**: đã chọn Mongo-first + PG outbox + reconciler (§XI lead). Cần ADR-004 formalize + test scenario edge case crash.
2. **Câu hỏi đã dùng trong exam có được edit không?** → Được edit nhưng **phải bump version**, exam cũ giữ nguyên snapshot qua `exam_questions.question_version`. Nếu edit đáp án của câu **đang được làm** → cấm (Exam query trước khi cho approve qua gRPC `HasActiveAttempts`).
3. **Chia sẻ câu hỏi giữa org (marketplace)?** → Phase 3. Cơ chế: `org_id=NULL + is_public=true`, audit access event `question.marketplace.accessed.v1`.
4. **Version rollback?** → Có. `POST /api/v1/questions/{id}/rollback {version}` tạo version **mới** với content của version cũ (monotonic bump).
5. **Parameterized questions?** → Phase 3. Thêm `template_params` + compute answer dynamic tại render time. Schema delta khi có ADR.
6. **Lưu vector trong Mongo hay chỉ ES?** → Chỉ ES (Mongo không tối ưu vector, lưu 2 chỗ waste disk). Mongo giữ `EmbeddingRef` metadata để track.
7. **Cold cache khi deploy mới?** → Pre-warm script chạy sau pod ready: load top 10k most-used questions vào Redis (5 phút job, không block readiness).
8. **OpenAPI codegen cho FE**: generate TS client tự động từ `openapi.yaml` (`openapi-typescript-codegen`), lock version.
9. **Reconciler batch job frequency**: 5 phút quét. Trade-off RPO vs write amp. Cần đo trên staging trước khi lock.
10. **Bulk export với answer có cần đặc quyền riêng?** → Đã tách `question.export.answers` permission (§XIII.3). Bulk export file không encrypt — cân nhắc SSE-KMS client-side encryption Phase 3.

---

## XIX. AGENTS & SKILLS KHI SCAFFOLD (theo CLAUDE.md §7)

Workflow khi bắt đầu implement Question Service — invoke theo thứ tự:

1. **`@spring-boot-engineer`** — scaffold Gradle multi-project (`app` + `api-grpc`), convention plugins (spotless, checkstyle, jacoco), entity Spring Data Mongo + JPA `subjects`, repository, UseCase skeleton, controller REST + gRPC.
   - Skill hỗ trợ auto: `spring-boot`, `design-patterns` (State + Strategy cho 11 type), `jpa-patterns` (subjects, outbox).
2. **`@security-engineer`** — wire Spring Security 6 Resource Server với JWKS, `JwtAuthenticationConverter` config (trap §XIII.2), `@PreAuthorize` permission-based, sudo-check bean (copy Auth §5.1.1), data isolation aspect.
3. Viết test (skill `spring-boot` + `jpa-patterns` + `code-quality` hỗ trợ).
4. **`@code-reviewer`** — review trước commit, gate quality.
5. **`@docker-expert`** → **`@kubernetes-specialist`** — containerize multi-stage Dockerfile + K8s manifest + HPA/PDB/NetworkPolicy.

**Ranh giới agents vs CLAUDE.md** (CLAUDE.md §7): agent chỉ biết best-practice Spring Boot chung. Rule project-specific ở CLAUDE.md §3 (outbox + fencing + JWT RS256 + idempotent consumer) + ADR-001 + §XI + §XIII của doc này phải được Claude chủ động áp. Nếu agent sinh code vi phạm → stop, fix theo doc, rồi tiếp.

**Skill auto-load khi edit code trong repo**: `spring-boot`, `code-quality`, `design-patterns`, `jpa-patterns`, `logging-patterns` — không cần invoke thủ công; chúng chỉ gợi ý best practice khi Claude edit file Java.

---

_Tài liệu thuộc Hệ Thống Thi Trực Tuyến Thông Minh — Question Service Design v2.0 — Tháng 4/2026._
