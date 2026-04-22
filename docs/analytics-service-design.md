# ANALYTICS SERVICE - THIẾT KẾ CHI TIẾT

**Hệ Thống Thi Trực Tuyến Thông Minh** | Version 1.2 | Tháng 4/2026

Tài liệu này mở rộng mục "Analytics Service" trong `design.md`, ở mức đủ để triển khai code production.

**Tài liệu nguồn ràng buộc (phải đọc trước khi thay đổi):**
- `CLAUDE.md` §2 (stack lock), §3 (NFR lock — outbox, idempotent consumer, JWT RS256+JWKS), §9 (prereqs scaffold)
- `docs/adr/ADR-001-sla-rpo-outbox.md` (SLA 99.9%, RPO ≤5s, transactional outbox + processed_events)
- `docs/adr/ADR-002-analytics-vs-cheating-split.md` (tách Analytics ≠ Cheating; topic fan-out; chia database trong cùng ClickHouse cluster)
- `docs/auth-service-design.md` §3.3 (RBAC permission-based, không hardcode role), §3.4 (permission `analytics.*`), §5.1 (JWT claim), §11 (outbox pattern reference)
- `database/clickhouse/schema.sql` §1-8 (toàn bộ fact tables + MV hiện có)
- `database/postgresql/schema.sql` §13 (outbox/processed_events) — dùng chung cho event publish + consumer dedup
- `shared-contracts/avro/TOPICS.md` (catalog topic) + `shared-contracts/avro/analytics/*.avsc` (event schema — BACKWARD compat)

**Changelog v1.2 (2026-04-22) — post-review fixes:**
- **§4.3**: fix contradiction `question_irt_params` — clarify `ReplacingMergeTree` giữ **latest per question**, history lưu ở **S3 Iceberg `curated/question_irt_history/`**. Tương tự `experiment_definitions` thêm cột `updated_at`, giữ latest state; audit history qua Kafka event. `question_dif_flags` giữ append-only, reviewed state chuyển sang PG bảng `dif_reviews` (§18.6 item 7).
- **§9.2 + §10.4**: fix ownership gap cho `analytics.export` — instructor **chỉ own exam**; thêm `@examAccessPolicy.isOwner` vào policy expression (match auth-service-design.md §3.4 seed matrix `✔(own exam)`).
- **§11.1**: reclassify `analytics.cohort.assigned.v1` xuống **fire-and-forget** (consumer là notification nhẹ, không drive state machine; regen weekly). Bổ sung cột "Lý do fire-and-forget" giải thích rule phân loại.
- **§11.4**: bổ sung `retries=Integer.MAX_VALUE` (**required** bởi `enable.idempotence=true` trong Kafka ≥ 3.x), `request.timeout.ms=5000`, `linger.ms=20`.
- **§9.4**: fix row policy missing session var setup — thêm `ClickHouseQueryExecutor.runAsUser()` snippet dùng `SET SQL_app_current_org_id`; 3 service account role tĩnh (instructor/admin/platform_admin) thay vì `SET ROLE` động; integration test bắt buộc.
- **§5.5 mới**: clarify flow `exam_facts` — MVP chỉ ghi khi `exam.attempt.graded.v1`, không ghi `submitted`; dashboard in-progress lấy từ Flink → Redis `rt:exam:*`. Tránh double-count do 2 event cho cùng `attempt_id`.
- **§18.6**: thêm item 6 (exam_facts flow doc-only), item 7 (PG bảng `dif_reviews` cho reviewed state).
- **Cross-repo fix**: `docs/auth-service-design.md §3.4` — bổ sung `analytics.experiment` vào row platform-scope (trước đây permission này chỉ xuất hiện trong Analytics design, Auth chưa seed).

**Changelog v1.1 (2026-04-22) — align stack + NFR theo CLAUDE.md §3 + auth-service-design.md §11:**
- **§II**: thêm cấu trúc Gradle multi-project (api, flink-jobs, spark-jobs) + §2.3 build quality gate (Spotless/Checkstyle/JaCoCo — CLAUDE.md §2). Repo layout align với `services/auth/`.
- **§IV (Data model)**: rewrite — ClickHouse DDL là nguồn truth ở `database/clickhouse/schema.sql`, bỏ copy cột; chỉ giữ invariant + TTL + business rule. Flag các bảng **đã** có vs **phải bổ sung**.
- **§V**: làm rõ 2 path ingestion (Kafka Engine vs Flink CDC) + **consumer idempotency** qua `processed_events` (ADR-001). Dedupe key = `event_id`.
- **§VIII → §XII (API)**: bổ sung **§12.0 API conventions** (versioning `/api/v1/`, content-type, naming snake_case, status code, RFC 7807, Idempotency-Key, rate limit headers, cursor-based pagination, no envelope). §12.7 endpoint contract template cho 2 endpoint điển hình với JSON Schema.
- **§X (Events) → §XI**: bổ sung **outbox pattern cho event Analytics PUBLISH** (`analytics.irt.calibrated.v1`, `analytics.dif.flagged.v1`, `analytics.experiment.concluded.v1`, `analytics.report.ready.v1` — critical cho Question Service / Notification). Fire-and-forget cho event volume cao (`analytics.dashboard.view.v1`). Reference shared-contracts/avro/TOPICS.md làm nguồn truth.
- **§XIII Security hardening** mới: JWT verification JWKS offline, row-level security ClickHouse, PII masking export, CORS, TLS, header chuẩn.
- **§XIV Observability**: align metric naming + tracing OTel + MDC masking filter + Loki JSON encoder (CLAUDE.md §2). SLO reference ADR-001 §1.
- **§XV Error handling**: RFC 7807 Problem Details + bảng error code đầy đủ (`ANALYTICS_*`).
- **§XVII Testing**: JaCoCo coverage gate (domain ≥80%, application ≥70%), Testcontainers matrix (ClickHouse + Kafka + Apicurio + Postgres), integration test outbox + consumer dedup, security test case.
- **§XVIII Roadmap**: thêm §18.1 gate trước scaffold + §18.6 schema delta block single-source.
- **§I**: clarify ADR-002 đã accept — Analytics giữ database **riêng** trong shared ClickHouse cluster; không cross-call trực tiếp với Cheating (chỉ qua event).
- Permission code alignment với `auth-service-design.md §3.4`: `analytics.self`, `analytics.exam`, `analytics.org`, `analytics.export`, `analytics.experiment`. Service **không hardcode role**.

---

## I. TỔNG QUAN

### 1.1 Vai trò & phạm vi

Analytics Service là **cỗ máy insight** của hệ thống — biến luồng sự kiện thô thành báo cáo có giá trị cho 3 đối tượng: học sinh (self-insight), giáo viên (dashboard kỳ thi), admin (điều hành & BI). Đồng thời đóng vai trò **ML platform** cho các tác vụ calibration (IRT), cohort, DIF.

**Nguyên tắc thiết kế (bám ADR-002):**
- **Tách biệt hoàn toàn khỏi hot path** — Analytics down, bài thi vẫn chạy.
- **Eventual consistency là OK** — dashboard trễ 5 phút chấp nhận được (≠ Cheating Detection phải real-time).
- **Precomputed > on-the-fly** — materialized view cho mọi dashboard thường dùng.
- **Query phân tích dùng ClickHouse**, không bao giờ chạm PostgreSQL source of truth.
- **Data lake S3 là truth cuối cùng** — có thể rebuild ClickHouse từ đây nếu mất.
- **Batch + stream hybrid** (Kappa-ish) — cùng event stream feed cả 2.
- **Không cross-call Cheating Service trực tiếp** — consume chung Kafka topic `cheat.alert.generated.v1` (ADR-002 §implementation).

| Trách nhiệm | Không thuộc phạm vi |
| ----------- | ------------------- |
| Consume events Kafka → ClickHouse (CDC + streaming) | Phát event domain (các service tự publish) |
| Maintain materialized views cho dashboard | OLTP queries (service khác tự truy vấn PG) |
| Real-time dashboards (Flink) | Real-time bảng xếp hạng đang thi (Redis + Exam Service) |
| Batch reports hàng tuần / tháng (Spark) | Chấm điểm (Exam Service + AI Service) |
| **IRT calibration** (EM algorithm) | Dùng IRT khi chọn câu (Exam Service) |
| Cohort analysis, DIF detection | Phát hiện gian lận real-time (Cheating Service — ADR-002) |
| A/B testing framework | Feature flags runtime (LaunchDarkly / custom) |
| Data export (CSV, Excel, Parquet) cho tổ chức | Import data (Question Service) |
| Data lake S3/Iceberg + query engine (Trino) | Video lưu trữ (Media Service) |
| Student learning curve, recommendation | Personalization khi làm bài (tương lai) |
| Admin BI dashboards | BI tool thương mại (Metabase/Looker — tuỳ chọn) |

### 1.2 Stack công nghệ

> Đã lock theo `CLAUDE.md §2`. Đổi công nghệ phải viết ADR mới — đừng tự ý thay trong design doc.

| Thành phần | Chọn | Lý do |
| ---------- | ---- | ----- |
| API service | **Java 21 LTS + Spring Boot 3.3+** | Thống nhất (CLAUDE.md §2); serving dashboard queries. `spring.threads.virtual.enabled=true` cho I/O path (JDBC, Redis, gRPC, WebClient). |
| Stream processor | Apache Flink 1.18 | Exactly-once, complex CEP, stateful |
| Batch processor | Apache Spark 3.5 | Large-scale ML, joins khổng lồ |
| OLAP store | ClickHouse 23.x | 100x PostgreSQL với 100M+ rows. Shared cluster với Cheating, **tách database** (ADR-002) |
| Data lake format | Apache Iceberg trên S3 | Schema evolution, time-travel, ACID |
| Query engine over lake | Trino (Starburst OSS) | Federated SQL qua Iceberg + ClickHouse |
| Kafka | Confluent Kafka 3.6 + **Apicurio** Schema Registry (Avro BACKWARD compat) | CLAUDE.md §2 (không phải Confluent Schema Registry — đã lock Apicurio) |
| CDC | Debezium 2.x | PostgreSQL WAL → Kafka (path B §V) |
| Workflow orchestration | Apache Airflow 2.8 | Batch job scheduling |
| Feature store (Phase 3) | Feast | Serving features cho ML online |
| Notebook / exploration | JupyterHub + Trino | Data analyst workflow |
| BI UI | Custom React 19 dashboard (CLAUDE.md §2) + embedded Metabase | Giáo viên dùng custom UI; admin deep-dive Metabase |
| ML | scikit-learn, XGBoost, statsmodels (IRT) | Standard; Python 3.12 + `uv` lock (CLAUDE.md §2) |
| Build Java | **Gradle** (wrapper pinned) + **Spotless** + **Checkstyle** + **JaCoCo** | Quality gate CI bắt buộc (§2.3) |
| Event publish reliability | **Transactional Outbox** (ADR-001) | Analytics publish `analytics.irt.calibrated.v1` → Question Service consume; mất event = IRT drift |
| Consumer dedup | `processed_events` per-service (ADR-001) | At-least-once Kafka + idempotent handler |
| JWT verification | Spring Security 6 OAuth2 Resource Server + JWKS cache 1h | Từ Auth Service (auth-service-design.md §5.4) |
| Migration | **clickhouse-migrations** (dbmate-style) cho CH; Flyway cho PG (outbox/processed_events share với Auth) | CLAUDE.md §2 |
| Test | JUnit 5 + AssertJ + **Testcontainers** (ClickHouse, Kafka, Postgres, Apicurio, MinIO) + WireMock (gRPC stub) | CLAUDE.md §2 |
| Observability | **Micrometer → Prometheus**, **OpenTelemetry OTLP** (traces+metrics), **Loki** log push | CLAUDE.md §2 stack chuẩn |
| Logging | SLF4J + Logback JSON (logstash-logback-encoder) + **MDC** (`trace_id`, `user_id`, `org_id`, `exam_id`) | Format AI-friendly cho Claude Code debug |

### 1.3 Port & giao thức

| Giao thức | Port | Mục đích |
| --------- | ---- | -------- |
| HTTP REST (HTTP/2) | `3006` | Dashboard API, report download |
| gRPC | `4006` | Internal — Question Service pull IRT params; Cheating gọi Analytics lấy historical agg (ADR-002) |
| JWKS consume | (outbound) | Lấy từ `https://auth.smartquiz.vn/.well-known/jwks.json`, cache 1h |
| OpenAPI spec | `3006/v3/api-docs` · `3006/swagger-ui.html` | Contract FE (prereq §18.1) |
| Actuator (health, metrics) | `9006/actuator/*` | Prometheus scraping + K8s probes |
| Flink JobManager UI | `8081` (internal) | Ops |
| Spark History | `18080` (internal) | Ops |
| Trino coordinator | `8080` (internal) | Query federation |

### 1.4 Yêu cầu phi chức năng

| Chỉ số | Mục tiêu |
| ------ | -------- |
| Dashboard p95 (cached / materialized view) | < 500ms |
| Dashboard p99 (ad-hoc query ClickHouse) | < 2s |
| Real-time dashboard lag (Flink) | < 5s |
| Batch report freshness | 24h (daily), 7 ngày (weekly) |
| Events throughput | 500k events/phút peak |
| Data retention ClickHouse | 25 tháng (partition theo tháng) — ghi chú: `exam_facts/answer_analytics/cheat_analytics` TTL **36 tháng** (xem `clickhouse/schema.sql`) vì cohort năm học + so sánh YoY |
| Data retention S3 data lake | 7 năm (compliance) |
| IRT calibration cycle | Daily batch (2 AM) |
| Availability (dashboard) | 99.9% (aligned ADR-001 §1 platform default) |
| Availability (ad-hoc Trino) | 99.5% (chấp nhận 3.6h/tháng — best-effort) |
| **Consumer lag p95** (event → ClickHouse hot tables) | < 5 phút (path A Kafka Engine) / < 60s (path B Flink CDC cho critical path) |
| **Outbox publish lag p99** (cho event Analytics produce) | < 5s (ADR-001 §2 RPO) |

---

## II. KIẾN TRÚC TỔNG THỂ

### 2.1 Lambda/Kappa hybrid

```
                       ┌──────────────────────┐
                       │   Service events     │
                       │  (Auth/Exam/Cheat…)  │
                       └──────────┬───────────┘
                                  │
                                  ▼
                  ┌────────────────────────────┐
                  │      Apache Kafka          │
                  │  (event source of truth)   │
                  └────┬────────┬──────────┬───┘
                       │        │          │
              ┌────────▼──┐  ┌──▼────────┐ │
              │  Flink    │  │ Kafka→CH  │ │
              │ Streaming │  │ (sink)    │ │
              └──┬────────┘  └──────┬────┘ │
                 │                  │      │
                 │                  ▼      ▼
                 │          ┌──────────────────┐
                 │          │   ClickHouse     │
                 │          │  (hot analytics) │
                 │          └────┬─────────────┘
                 │               │
                 │         ┌─────▼──────┐
                 │         │ Materialized│
                 │         │ Views       │
                 │         └─────┬──────┘
                 │               │
                 └───────────────┼─────► Query Layer ◄────┐
                                 │       (API service)     │
                                 │                          │
                  ┌──────────────▼─────────────────┐        │
                  │   S3 + Iceberg (Data Lake)     │        │
                  │   all events partitioned       │        │
                  └────┬─────────────┬─────────────┘        │
                       │             │                      │
                 ┌─────▼──────┐   ┌──▼──────┐              │
                 │  Spark     │   │  Trino  │◄─────────────┘
                 │  Batch ML  │   │ (ad-hoc)│
                 │  IRT/DIF   │   └─────────┘
                 │  Cohort    │
                 └─────┬──────┘
                       │
                       ▼
          ┌──────────────────────────────┐
          │ Outbox (PG) — ADR-001         │
          │  analytics.irt.calibrated.v1 │
          │  analytics.dif.flagged.v1    │
          └──────────┬───────────────────┘
                     │ Relayer (advisory-lock leader)
                     ▼
                   Kafka → Question Service / Notification
```

### 2.2 Khi nào dùng cái gì

| Scenario | Engine |
| -------- | ------ |
| Dashboard giáo viên "bao nhiêu lượt đang thi" | Flink + Redis cache 1s |
| Dashboard "điểm trung bình bài thi hôm nay" | ClickHouse materialized view (trễ 5 phút) |
| Student "so với class percentile" | ClickHouse query trực tiếp |
| Admin "so sánh 2 kỳ thi YoY" | ClickHouse query trực tiếp hoặc Trino |
| IRT calibration hằng ngày | Spark batch (đọc Iceberg) |
| Ad-hoc data analyst ("câu hỏi nào có pattern DIF theo giới tính?") | Trino/Jupyter |
| Export raw data cho giáo viên (CSV 1 exam) | ClickHouse SELECT + stream to file |
| Data science training dataset | Spark đọc Iceberg + export Parquet |

### 2.3 Module Gradle multi-project (align CLAUDE.md §2 + auth-service §2.2)

```
services/analytics/
├── settings.gradle.kts           # include: api, flink-jobs, spark-jobs, api-grpc
├── build.gradle.kts              # root — convention plugins (spotless, checkstyle, jacoco)
├── gradle/                       # wrapper (pinned)
├── api-grpc/                     # .proto + generated stubs (publish → mavenLocal)
│   ├── src/main/proto/
│   └── build.gradle.kts
├── api/                          # Spring Boot API (port 3006)
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/vn/smartquiz/analytics/
│       │   ├── AnalyticsServiceApplication.java
│       │   ├── config/           # SecurityConfig (JWKS), ClickHouseConfig, KafkaConfig, OutboxConfig
│       │   ├── controller/       # @RestController + @ControllerAdvice (RFC 7807)
│       │   ├── grpc/             # gRPC server (GetQuestionIrt, BatchGetQuestionIrt, RecordExposure)
│       │   ├── application/      # UseCase (GetStudentProgressUseCase, ...)
│       │   ├── domain/
│       │   │   ├── query/        # DashboardQueryBuilder, IrtParams (value)
│       │   │   ├── experiment/   # ExperimentDefinition, StatisticalTester
│       │   │   └── cohort/       # CohortDefinition
│       │   ├── infrastructure/
│       │   │   ├── clickhouse/   # ClickHouseTemplate (JDBC driver) + row mappers
│       │   │   ├── trino/        # TrinoJdbcClient
│       │   │   ├── kafka/        # Consumer (idempotent via processed_events) + Outbox relayer
│       │   │   ├── s3/           # S3Client (download exports)
│       │   │   ├── airflow/      # AirflowRestClient (trigger ad-hoc batch)
│       │   │   └── flink/        # FlinkRestClient (monitor jobs)
│       │   └── common/           # Exception, ErrorCode (ANALYTICS_*), MdcFilter
│       ├── main/resources/
│       │   ├── application.yml
│       │   ├── logback-spring.xml           # JSON encoder + mask filter (email, ip)
│       │   ├── db/migration/                # Flyway delta cho PG (outbox dùng chung)
│       │   ├── clickhouse/migrations/       # clickhouse-migrations delta
│       │   └── static/openapi.yaml          # OpenAPI 3.1 spec (prereq §18.1)
│       └── test/java/...
├── flink-jobs/                   # Flink streaming jobs (§VI)
│   ├── build.gradle.kts
│   └── src/main/java/vn/smartquiz/analytics/flink/
│       ├── ExamSessionMetricsJob.java
│       ├── RealtimeLeaderboardJob.java
│       └── CheatRiskAggregatorJob.java
├── spark-jobs/                   # Spark batch jobs (§VII)
│   ├── build.gradle.kts
│   └── src/main/scala/vn/smartquiz/analytics/spark/
│       ├── IrtCalibrationJob.scala
│       ├── DifDetectionJob.scala
│       ├── CohortSegmentationJob.scala
│       └── WeeklyReportJob.scala
├── airflow/
│   └── dags/                     # Python DAG definitions
└── README.md
```

**Rule migration:** ClickHouse schema master ở `database/clickhouse/schema.sql` (shared reference). Analytics service chỉ commit delta riêng vào `clickhouse/migrations/` với prefix `V{yyyymmddhhmm}__analytics_*.sql`. **Không** được sửa migration đã release (immutable — giống Flyway).

### 2.4 Build quality gate (align auth §2.3)

| Tool | Cấu hình | Gate fail khi |
| ---- | -------- | ------------- |
| Spotless | `googleJavaFormat('1.19.2')` + `removeUnusedImports()` + `trimTrailingWhitespace()` | Format lệch → CI fail, gợi ý `./gradlew spotlessApply` |
| Checkstyle | `config/checkstyle/checkstyle.xml` (Google style + project override) | Bất kỳ error → CI fail |
| JaCoCo | Report HTML + XML → Codecov | `domain/` line coverage < **80%**, `application/` < **70%** |
| OWASP dependency-check | `./gradlew dependencyCheckAggregate` nightly | CVSS ≥ 7.0 trên compile deps |
| Error Prone | Google static analysis — bật cho `main` | Any new warning → review block |
| **Avro compat** | `./gradlew :shared-contracts:avroCompatCheck` (Apicurio) | Schema breaking vs live → CI fail |

---

## III. MODULE INTERNAL (Spring Boot API)

### 3.1 Sơ đồ lớp (layered architecture)

```
┌──────────────────────────────────────────────────────────────┐
│  Controllers (REST + gRPC)                                   │
│  ─ StudentAnalyticsController  ─ TeacherDashboardController  │
│  ─ AdminReportController       ─ ExportController            │
│  ─ ExperimentController (A/B)  ─ AnalyticsGrpcService        │
├──────────────────────────────────────────────────────────────┤
│  Application Services (use cases)                            │
│  ─ GetStudentProgressUseCase                                 │
│  ─ GetExamDashboardUseCase, GetQuestionStatsUseCase          │
│  ─ BuildCohortReportUseCase, GetDIFReportUseCase             │
│  ─ ExportAnalyticsUseCase, ScheduleReportUseCase             │
│  ─ CreateExperimentUseCase, StopExperimentUseCase            │
│  ─ PublishIrtCalibratedUseCase (wraps outbox)                │
├──────────────────────────────────────────────────────────────┤
│  Domain / Analytics Queries                                  │
│  ─ DashboardQueryBuilder (ClickHouse SQL DSL, không raw)     │
│  ─ IrtParams (value), CohortDefinition                       │
│  ─ ExperimentDefinition, StatisticalTester                   │
├──────────────────────────────────────────────────────────────┤
│  Infrastructure                                              │
│  ─ ClickHouseTemplate, TrinoJdbcClient                       │
│  ─ AnalyticsOutboxPublisher (TX-bound, MANDATORY propagation)│
│  ─ KafkaConsumerIdempotent (processed_events dedup)          │
│  ─ S3Client (download exports)                               │
│  ─ AirflowRestClient (trigger ad-hoc batch)                  │
│  ─ FlinkRestClient (monitor jobs)                            │
└──────────────────────────────────────────────────────────────┘
```

Cấu trúc repo đầy đủ xem §2.3.

---

## IV. DATA MODEL — invariants & business rules

> **⚠️ ClickHouse DDL là nguồn truth duy nhất ở `database/clickhouse/schema.sql`.** PostgreSQL DDL ở `database/postgresql/schema.sql` §13 (outbox/processed_events) — dùng chung với Auth/Exam (không phải bảng domain riêng Analytics).
>
> Section này **KHÔNG copy cột** — chỉ mô tả invariant, retention, policy, usage pattern mà DDL không diễn đạt. Nếu cần thêm/đổi cột: sửa `clickhouse/schema.sql` trước, rồi update `clickhouse/migrations/` delta (§18.6), rồi mới động tới prose ở đây.

### 4.1 Bảng hot analytics (đã có, đừng recreate)

| Bảng | File | Retention | Ordering key | Dùng cho |
| ---- | ---- | --------- | ------------ | -------- |
| `exam_facts` | `clickhouse/schema.sql` §1 | 36 tháng (cohort năm học) | `(org_id, exam_id, started_at)` | Dashboard exam, student progress, IRT training |
| `answer_analytics` | §2 | 36 tháng | `(org_id, question_ref_id, date)` | Question stats, IRT calibration |
| `cheat_analytics` | §3 | 36 tháng | `(org_id, exam_id, occurred_at)` | Heat-map gian lận (Consume từ Cheating — ADR-002) |
| `user_activity_facts` | §7 | **25 tháng** (GDPR minimize) | `(org_id, user_id, ts)` | Learning behavior |
| `question_feedback_facts` | §7 | vĩnh viễn | `(org_id, question_ref_id, ts)` | Rating/report aggregate |
| `experiment_exposures` | §7 | **6 tháng** sau experiment end | `(experiment_id, user_id)` | A/B testing |

**Invariants business-level:**
- `exam_facts.org_id` NOT NULL — mọi query giáo viên/admin đều filter org_id → nếu null thì row không reachable (bug indexing upstream).
- `answer_analytics.question_ref_id` là **reference string** (không phải UUID) vì Question Service dùng MongoDB `_id` string (xem `question-service-design.md`).
- `cheat_analytics` **consume-only** — không UPDATE. Source of truth là Cheating Service (`cheat_events` PG).
- `experiment_exposures` row append-only; nếu cần soft-end experiment → UPDATE `experiment_definitions` table (MVP: tạo sau — xem §4.3), không sửa exposure.

### 4.2 Materialized views hiện có

| MV | View đọc | Refresh | Dùng cho |
| -- | -------- | ------- | -------- |
| `mv_exam_daily_stats` | `v_exam_daily_stats` | Realtime (trigger insert) | Dashboard exam overview |
| `mv_question_stats` | `v_question_stats` | Realtime | IRT input + dashboard câu hỏi |
| `mv_cheat_weekly` | `v_cheat_weekly` | Realtime | Cheat trend teacher/admin |
| `mv_student_progress` | `v_student_progress` | Realtime | Student learning curve |
| `mv_user_first_attempt` | `v_user_first_attempt` | Realtime | Cohort month assignment |

**Rule MV:** ClickHouse MV là **INSERT trigger** — dữ liệu flow từ base table → MV state. Nếu query dashboard chậm do cardinality cao, **thêm PROJECTION** (ALTER TABLE ... ADD PROJECTION) thay vì MV mới. MV mới chỉ tạo khi aggregate pattern khác hẳn (vd rollup cohort, rollup month-of-year).

### 4.3 Bảng sẽ bổ sung (chưa có trong schema — §18.6 delta)

```sql
-- Experiment definitions (metadata) — latest state per experiment_id
CREATE TABLE IF NOT EXISTS experiment_definitions (
    experiment_id      UUID,
    code               LowCardinality(String),
    name               String,
    status             LowCardinality(String),   -- 'draft','running','paused','concluded'
    variants_json      String,                   -- JSON config (variants + traffic)
    eligibility_json   String,                   -- JSON rule
    primary_metric     LowCardinality(String),
    secondary_metrics  Array(String),
    min_samples        UInt32,
    started_at         DateTime64(3),
    ends_at            Nullable(DateTime64(3)),
    concluded_at       Nullable(DateTime64(3)),
    winner_variant     Nullable(String),
    p_value            Nullable(Float32),
    updated_at         DateTime64(3)
) ENGINE = ReplacingMergeTree(updated_at)
ORDER BY (experiment_id);

-- IRT calibrated params — LATEST per question (không phải history)
-- History đầy đủ lưu ở S3 Iceberg (curated/question_irt_history) — query qua Trino khi cần.
CREATE TABLE IF NOT EXISTS question_irt_params (
    question_ref_id    String,
    org_id             UUID,
    a                  Float32,
    b                  Float32,
    c                  Float32,
    n_responses        UInt32,
    calibrated_at      DateTime64(3),
    calibration_method LowCardinality(String),   -- '3pl_em','bayes_shrink'
    converged          UInt8,
    point_biserial     Float32
) ENGINE = ReplacingMergeTree(calibrated_at)
ORDER BY (org_id, question_ref_id);

-- DIF flags — append-only log (1 row per detection run)
CREATE TABLE IF NOT EXISTS question_dif_flags (
    question_ref_id    String,
    org_id             UUID,
    demographic_group  LowCardinality(String),   -- 'gender','age_band','org_plan'
    mh_stat            Float32,
    p_value            Float32,
    flagged_at         DateTime64(3),
    reviewed           UInt8,
    reviewer_user_id   Nullable(UUID)
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(flagged_at)
ORDER BY (org_id, question_ref_id, flagged_at);
```

**Invariants:**
- **`question_irt_params` chỉ giữ bản calibrated mới nhất per `(org_id, question_ref_id)`** — `ReplacingMergeTree(calibrated_at)` + nightly `OPTIMIZE FINAL` dedupe. History calibration đầy đủ lưu ở **S3 Iceberg** `curated/question_irt_history/` (Spark job §7.1 append song song), query qua Trino cho debug/rollback. Consumer Question Service subscribe `analytics.irt.calibrated.v1` để nhận push current params vào MongoDB `question.irt_params`.
- **`experiment_definitions` cũng giữ latest state** — `ReplacingMergeTree(updated_at)`, mỗi API mutation (`/experiments/{id}/stop`, status change) insert row mới với `updated_at=NOW()`; `OPTIMIZE FINAL` dedupe. Transition hợp lệ: `draft → running → paused ↔ running → concluded`. Khi `status=concluded` phải set `concluded_at` + `winner_variant` + `p_value`. Audit trail history state transitions đi qua outbox event `analytics.experiment.*.v1` + Kafka retention — không duplicate trong CH.
- **`question_dif_flags` append-only** — mỗi lần DIF job phát hiện bias insert 1 row; không update. `reviewed` flag set qua API admin (`PATCH /analytics/dif-flags/{id}/review`) → INSERT row mới với same `(question_ref_id, flagged_at)` + `reviewed=1` sẽ duplicate. **Fix**: dùng `UPDATE question_dif_flags SET reviewed=1, reviewer_user_id=? WHERE question_ref_id=? AND flagged_at=?` (ALTER TABLE UPDATE — mutation async); hoặc chuyển `question_dif_flags` sang `ReplacingMergeTree(flagged_at)` nếu cần update thường xuyên. MVP chọn mutation (DIF review tần suất thấp).

### 4.4 Dictionaries (ClickHouse từ PostgreSQL)

Để tránh join PG mỗi query, ClickHouse load dictionary từ PG replica read-only:

```sql
CREATE DICTIONARY IF NOT EXISTS dict_users (
    id UUID, full_name String, email String, org_id UUID
) PRIMARY KEY id
SOURCE(POSTGRESQL(
    host 'pg-replica' port 5432 user 'clickhouse_reader'
    password '...' db 'smartquiz' table 'users'))
LIFETIME(MIN 300 MAX 600)
LAYOUT(COMPLEX_KEY_HASHED());
```

Refresh 5-10 phút. Query: `SELECT dictGet('dict_users', 'full_name', user_id) AS name, ...`.

**Tương tự** cho `dict_orgs`, `dict_subjects`, `dict_roles` (các bảng dim ít thay đổi). **Không tạo dict cho `questions`** — quá lớn (MongoDB, không phải PG), dùng gRPC → Question Service thay thế.

### 4.5 Outbox & processed_events (shared PG — ADR-001)

Analytics Service dùng chung 2 bảng `outbox` + `processed_events` ở PostgreSQL (`database/postgresql/schema.sql` §13):

- **`outbox`** — khi Analytics publish critical event (§XI.1). Record insert cùng transaction với domain mutation (vd `question_irt_params` insert + outbox row `analytics.irt.calibrated.v1`).
- **`processed_events`** — consumer Analytics dedupe at-least-once Kafka. PK = `(event_id, topic)` với `consumer_group='analytics-*'` label.

DDL không đổi; business rule tại §V.3 (consumer) + §XI.2 (publisher).

---

## V. DATA INGESTION PIPELINE

### 5.1 Hai path vào ClickHouse

**Path A — Kafka Engine trong ClickHouse** (events domain nhỏ volume, không cần transform phức tạp):

```sql
CREATE TABLE exam_facts_queue (...) ENGINE = Kafka
SETTINGS kafka_broker_list  = 'kafka-0:9092,kafka-1:9092,kafka-2:9092',
         kafka_topic_list   = 'exam.attempt.submitted.v1',
         kafka_group_name   = 'clickhouse_exam_facts',
         kafka_format       = 'AvroConfluent',
         kafka_num_consumers = 3;

CREATE MATERIALIZED VIEW mv_exam_facts_writer TO exam_facts
AS SELECT ... FROM exam_facts_queue;
```

Latency event → ClickHouse: ~500ms-2s. Đủ cho dashboard.

**Path B — Flink CDC cho PostgreSQL source** (nguồn sự thật bất biến + cần transform):

```
PostgreSQL WAL
    ↓ Debezium Kafka Connect
Kafka topic: quiz.public.exam_attempts (CDC format)
    ↓ Flink SQL (exactly-once + transform + dedupe)
    ↓
ClickHouse sink connector
```

Path B dùng khi cần **strong consistency** với PG (e.g., score chính thức). Latency < 60s.

### 5.2 Schema Registry

Tất cả events qua Kafka đăng ký Avro schema ở **Apicurio** (CLAUDE.md §2, không phải Confluent SR):
- Compat mode **BACKWARD** only — chỉ được add field với default; không remove/rename/đổi type.
- Schema evolution review qua PR ở `shared-contracts/avro/`.
- CI gate: `./gradlew :shared-contracts:avroCompatCheck` chạy Apicurio compat check vs schema đang live.

### 5.3 Consumer idempotency (ADR-001)

Mọi Kafka consumer trong Analytics **phải dedupe bằng `event_id`** vào bảng `processed_events` để xử lý at-least-once của Kafka:

```java
@KafkaListener(topics = "exam.attempt.submitted.v1", groupId = "analytics-exam-facts")
@Transactional
public void handle(ConsumerRecord<String, GenericRecord> rec) {
    UUID eventId = UUID.fromString(
        new String(rec.headers().lastHeader("event_id").value(), UTF_8));

    // INSERT ... ON CONFLICT DO NOTHING — atomic dedupe
    int inserted = processedEventsRepo.markProcessedIfAbsent(
        eventId, rec.topic(), "analytics-exam-facts");
    if (inserted == 0) {
        metrics.dedup.increment("duplicate", rec.topic());
        return;    // đã xử lý rồi, skip
    }

    // Business logic — ghi ClickHouse, trigger aggregation
    examFactsSink.insert(toDomain(rec.value()));
    metrics.dedup.increment("new", rec.topic());
}
```

**Lưu ý:**
- ClickHouse insert **ngoài** transaction PG (ClickHouse không tham gia XA). Nếu CH insert fail → exception bubble → TX PG rollback → `processed_events` row không commit → lần replay thành công.
- **Không dùng Kafka offset commit thủ công** — để consumer group Kafka tự commit sau khi `@Transactional` PG thành công (Spring Kafka `AckMode.RECORD` + `isolation.level=read_committed`).
- `processed_events` cleanup job giữ 7 ngày (≥ Kafka retention max) — batch DELETE nightly.

### 5.4 Catalog event consume (tóm tắt)

| Topic (v1) | Path | Dedup group | Sink |
| ---------- | ---- | ----------- | ---- |
| `auth.login.success.v1` | A | `analytics-user-activity` | `user_activity_facts` |
| `auth.user.registered.v1` | A | `analytics-cohort` | (trigger cohort month assign) |
| `exam.attempt.started.v1` | A | `analytics-exam-stream` | Flink state → Redis leaderboard (không ghi `exam_facts`) |
| `exam.answer.submitted.v1` | A | `analytics-answer` | `answer_analytics` (insert per answer) |
| `exam.attempt.submitted.v1` | B (Flink CDC) | `analytics-exam-facts` | `exam_facts` **INSERT** với score preliminary (nếu auto-graded) hoặc `raw_score=NULL` |
| `exam.attempt.graded.v1` | B (Flink CDC) | `analytics-exam-graded` | `exam_facts` **UPSERT** qua Flink Upsert-Kafka connector + CH ReplacingMergeTree dedup |
| `cheat.event.detected.v1` | A | `analytics-cheat` | `cheat_analytics` |
| `cheat.alert.generated.v1` | A | `analytics-cheat-alert` | Flink heat-map + `cheat_analytics` |
| `question.created.v1` / `.updated.v1` | A | `analytics-question-dim` | Dictionary refresh trigger |
| `ai.cost.recorded.v1` | A | `analytics-ai-cost` | AI cost aggregate |

**Nguồn truth topic name:** `shared-contracts/avro/TOPICS.md`. Khi lệch → file shared-contracts thắng.

### 5.5 Flow `exam_facts` — submitted → graded (2 phase)

`exam_facts` là fact quan trọng nhất, nhận 2 event cho cùng `attempt_id`:

```
Phase 1 — on exam.attempt.submitted.v1 (Flink CDC từ PG exam_attempts, path B):
  INSERT exam_facts (
    attempt_id, exam_id, user_id, org_id, subject_code,
    started_at, submitted_at,
    raw_score=NULL,          -- essay chưa chấm
    max_score, percentage_score=NULL, passed=0,
    attempt_number, time_spent_sec,
    risk_score=0, flagged=0, cheat_events_count=0,
    country_code, device_type,
    final_theta=0, final_se=0
  )
  Dashboard teacher thấy "298 submitted, 14 in progress" ngay sau event.

Phase 2 — on exam.attempt.graded.v1 (sau khi Grading hoàn tất):
  INSERT exam_facts (cùng attempt_id, SAME ordering key)
    với raw_score, percentage_score, passed, risk_score, flagged,
        cheat_events_count, final_theta, final_se — điền đủ.

  ClickHouse ReplacingMergeTree(submitted_at) trên bảng hiện tại?
    → KHÔNG. `exam_facts` hiện là MergeTree() — schema.sql §1.
    → Row sau OVERWRITE row trước: cần đổi sang ReplacingMergeTree
      HOẶC dùng CollapsingMergeTree.
```

**Vấn đề**: `exam_facts` ở `clickhouse/schema.sql §1` hiện **MergeTree() thường** — không dedupe. Phase 1 + Phase 2 sẽ tạo 2 row duplicate cho cùng `attempt_id` → double-count trong mọi aggregate.

**Fix (schema delta §18.6 item 6 — thêm)**: đổi `exam_facts` sang:
```sql
ENGINE = ReplacingMergeTree(ingestion_ts)    -- cột mới, timestamp Flink sink ghi
ORDER BY (org_id, exam_id, attempt_id)        -- ĐỔI: thêm attempt_id để dedupe đúng key
```
- `ingestion_ts`: Flink sink tự add, max wins → phase 2 overwrite phase 1.
- ORDER BY phải chứa `attempt_id` — là dedupe key thực sự. `started_at` rời ra khỏi ordering key, vẫn giữ làm field partition.
- Query `v_exam_daily_stats` (MV đọc) cần `FINAL` modifier hoặc `argMax(column, ingestion_ts)` để lấy row mới nhất — có cost. Alternative: **Exam Service không publish 2 event tách biệt** → chỉ publish `exam.attempt.graded.v1` khi state cuối cùng ready, Analytics skip `submitted` cho `exam_facts` (chỉ dùng cho Flink leaderboard live).

**MVP quyết định**: alternative đơn giản hơn — `exam_facts` **chỉ ghi khi graded**, không ghi khi submitted. Trade-off: dashboard "298 submitted" phải lấy từ Flink streaming (Redis `rt:exam:{id}:stats`), không từ `exam_facts`. Đổi lại schema `exam_facts` không cần thay đổi DDL. Cập nhật §18.6 item 6 thành "clarify flow" thay vì "alter engine".

---

## VI. FLINK STREAMING JOBS

### 6.1 Job 1 — Real-time exam session metrics

**Input:** Kafka topics `exam.attempt.started.v1`, `exam.answer.submitted.v1`, `exam.attempt.submitted.v1`.

**Output:** Redis `rt:exam:{exam_id}:stats` updated mỗi 1 giây.

```java
DataStream<AttemptEvent> events = env.fromSource(kafkaSource,
    WatermarkStrategy.<AttemptEvent>forBoundedOutOfOrderness(Duration.ofSeconds(30)), "events");

events
    .keyBy(AttemptEvent::examId)
    .window(SlidingEventTimeWindows.of(Time.minutes(5), Time.seconds(1)))
    .aggregate(new ExamStatsAggregator())    // {active, avg_score, submitted, flagged}
    .addSink(new RedisSink<>("rt:exam:{}:stats"));
```

Exam Service đọc Redis cho "live dashboard".

### 6.2 Job 2 — Real-time leaderboard (elastic)

Cho giáo viên xem live trong kỳ thi lớn. Redis Sorted Set `leaderboard:{exam_id}`.

```java
events.filter(e -> e.type() == "attempt.submitted")
     .keyBy(AttemptEvent::examId)
     .process(new LeaderboardUpdater());
```

### 6.3 Job 3 — Cheat correlation heat-map

Consume `cheat.alert.generated.v1` + `cheat.event.detected.v1`, xuất ra heat-map (user × event_type × hour) → ClickHouse insert qua sink connector.

### 6.4 Checkpoint & state

- Enable Flink checkpointing mỗi 30s, state backend RocksDB on S3.
- Exactly-once semantics với Kafka source (read_committed) + idempotent sink (ClickHouse ReplacingMergeTree dedupe trên primary key).
- Savepoints trước mỗi deploy để rollback dễ (`upgradeMode: savepoint` ở Flink Operator §19.2).

### 6.5 Monitoring Flink

- Flink metrics → Prometheus qua `metrics.reporter.prom`.
- Alert: job down, checkpoint duration > 10s, backpressure > 80% — chi tiết §XIV.5.

---

## VII. SPARK BATCH JOBS

### 7.1 Job 1 — IRT Calibration (daily, 2 AM)

**Mục tiêu:** Cập nhật tham số `a, b, c` của mỗi câu hỏi dựa trên responses mới, publish qua **outbox** về Question Service.

```scala
val spark = SparkSession.builder.getOrCreate()

val answers = spark.read.format("iceberg").load("answers_iceberg")
                   .filter($"answered_at" >= yesterday)

val candidates = answers.groupBy("question_ref_id")
                        .agg(count("*").as("n"))
                        .filter($"n" >= 30)      // cold-start threshold

val calibrated = candidates.mapPartitions { iter =>
    iter.map { row =>
        val qid = row.getAs[String]("question_ref_id")
        val responses = fetchResponsesWithTheta(qid)
        val (a, b, c) = emThreePL(responses, maxIter = 50, tol = 0.001)
        (qid, a, b, c, responses.size)
    }
}

// Write 1: ClickHouse (audit + history)
calibrated.write.format("clickhouse")
    .option("table", "question_irt_params").save()

// Write 2: Outbox qua JDBC — PG outbox row cho mỗi question
// KHÔNG publish Kafka trực tiếp từ Spark (vi phạm outbox pattern).
// Spark worker kết nối PG, INSERT outbox row trong từng partition TX.
calibrated.foreachPartition { iter =>
    val conn = pgDataSource.getConnection()
    conn.setAutoCommit(false)
    val ps = conn.prepareStatement(
      """INSERT INTO outbox(event_id, aggregate_type, aggregate_id, topic, event_type,
                            payload, partition_key, created_at)
         VALUES (gen_random_uuid(), 'question', ?, 'analytics.irt.calibrated.v1',
                 'IrtCalibrated', ?::jsonb, ?, NOW())""")
    iter.foreach { case (qid, a, b, c, n) =>
        ps.setString(1, qid)
        ps.setString(2, Json.toJson(IrtUpdate(qid, a, b, c, n)).toString)
        ps.setString(3, qid)   // partition by question → consumer order preserved
        ps.addBatch()
    }
    ps.executeBatch()
    conn.commit()
}
```

**EM algorithm (statsmodels-like):**
- E-step: với θ hiện tại của students, estimate expected correct probability.
- M-step: maximize log-likelihood với Newton-Raphson → update `a, b, c`.
- Convergence: |Δθ| < 0.001 hoặc 50 iter.

### 7.2 Job 2 — DIF Detection (weekly)

Mantel-Haenszel chi-square test: câu hỏi có bias theo demographic group (giới tính, tuổi, org) không?

```scala
val difResults = answers.join(users, "user_id")
    .groupBy("question_ref_id", "demographic_group")
    .agg(/* matched pairs */)
    .withColumn("mh_stat", udf_mantel_haenszel(...))
    .filter($"mh_stat" > critical_value)

difResults.write.format("iceberg").save("question_dif_flags")
// + INSERT outbox analytics.dif.flagged.v1 → Notification gửi giáo viên
```

### 7.3 Job 3 — Cohort segmentation (weekly)

K-means trên vector features: `{avg_score, time_per_q, attempts_per_week, correct_rate_by_bloom}`.

Output: `user_cohorts` table với `cohort_id` per user. Dùng cho personalized content recommendation (Phase 3).

### 7.4 Job 4 — Weekly/Monthly reports

Tạo Parquet + PDF report cho admin:
- Top 10 bài thi phổ biến
- Top 10 câu hỏi khó nhất
- Cheat rate trend
- User retention cohort table

Trigger bởi Airflow DAG, output S3 `reports/{org_id}/{year}/{week}.pdf` qua PDF renderer.

### 7.5 Spark cluster

- Kubernetes-native (spark-on-k8s) — driver + executor as pods.
- On-demand: Airflow `SparkKubernetesOperator` submit job.
- Resources: default 8 executor × 4 core × 8GB; scale theo data size.

---

## VIII. DATA LAKE (Iceberg)

### 8.1 Table structure

```
s3://smartquiz-lake/
├── raw/
│   ├── kafka_events/
│   │   ├── exam_attempt_events/     (Iceberg table, partition: date)
│   │   ├── answer_events/
│   │   └── cheat_events/
├── curated/
│   ├── exam_facts/                  (Iceberg; cleaned + enriched)
│   ├── answer_facts/
│   └── user_facts/
└── reports/
    └── {org_id}/...
```

### 8.2 Ingestion to lake

Kafka → S3 qua:
- Option A: Flink sink `FileSystem` với Iceberg format (preferred — exactly-once).
- Option B: Kafka Connect S3 Sink → Spark compaction job (đơn giản hơn, weakly-consistent).

Commit interval: 5 phút (small files → compact hourly).

### 8.3 Schema evolution

Iceberg hỗ trợ native:
- Add column (null default).
- Rename column (keeps data).
- Drop column (data retained, marked removed).

Flow: schema PR → Iceberg `ALTER TABLE` → consumers auto-adapt.

### 8.4 Time-travel queries

```sql
-- Qua Trino
SELECT * FROM iceberg.curated.exam_facts
FOR TIMESTAMP AS OF TIMESTAMP '2026-04-15 00:00:00'
```

Dùng cho:
- Reproduce report historical đúng với state lúc đó.
- Debug "số liệu hôm qua khác hôm nay" (dữ liệu backfill).

---

## IX. RBAC — PERMISSION CODE

> Nguồn truth RBAC ở **`auth-service-design.md §3.3 + §3.4`**. Analytics Service **không hardcode role** — chỉ check permission code từ claim `authorities` của JWT.

### 9.1 Permission code Analytics cần enforce

| Code | Scope | Mô tả | Role mặc định được grant |
| ---- | ----- | ----- | ----------------------- |
| `analytics.self` | own | Xem dashboard học tập của bản thân | student, instructor, admin, proctor |
| `analytics.exam` | org | Xem dashboard kỳ thi trong org | instructor, admin, proctor |
| `analytics.export` | org | Download CSV/Parquet dữ liệu exam | instructor (own exam), admin |
| `analytics.org` | org | Xem report toàn org | admin |
| `analytics.experiment` | platform | Tạo/dừng A/B experiment | platform_admin only |

Bảng role-permission đầy đủ ở `auth-service-design.md §3.4`. Analytics chỉ **consume** permission code, không định nghĩa role.

### 9.2 Policy expression (Spring Security)

```java
// ❌ KHÔNG làm thế này — hardcode role name
@PreAuthorize("hasRole('INSTRUCTOR')")
public DashboardDto getExamDashboard(UUID examId) { ... }

// ✅ Dashboard xem exam — permission + same-org check
@PreAuthorize("hasAuthority('analytics.exam') and @examAccessPolicy.isSameOrg(authentication, #examId)")
public DashboardDto getExamDashboard(UUID examId) { ... }

// ✅ Student progress phân cấp: own vs org
@PreAuthorize(
  "hasAuthority('analytics.self') and #userId == authentication.name" +
  " or hasAuthority('analytics.org') and @userAccessPolicy.isSameOrg(authentication, #userId)")
public ProgressDto getStudentProgress(UUID userId) { ... }

// ✅ Export exam results — instructor CHỈ own exam, admin org-wide
// Match auth-service-design.md §3.4: analytics.export = ✔(own exam) cho instructor, ✔ cho admin
@PreAuthorize(
  "hasAuthority('analytics.export') and @examAccessPolicy.isOwner(authentication, #request.examId)" +
  " or hasAuthority('analytics.org') and @examAccessPolicy.isSameOrg(authentication, #request.examId)")
public ExportJobDto exportExamResults(ExportRequest request) { ... }
```

Các bean policy (tự viết trong `infrastructure/security/`):
- `@examAccessPolicy.isOwner(auth, examId)` — query `exams.created_by == auth.name` (cache Redis 60s).
- `@examAccessPolicy.isSameOrg(auth, examId)` — query `exams.org_id == auth.claim('org_id')`.
- `@userAccessPolicy.isSameOrg(auth, userId)` — dùng `dict_users` cho subject user.

**Rule**: check **ownership tách khỏi check permission** — permission nói "action nào được làm", ownership nói "resource nào được chạm". Combine ở policy expression.

### 9.3 JWKS verification

Cấu hình trong `SecurityConfig` (align `auth-service-design.md §3.3`):

```java
@Bean
JwtAuthenticationConverter jwtAuthConverter() {
    var authoritiesConverter = new JwtGrantedAuthoritiesConverter();
    authoritiesConverter.setAuthoritiesClaimName("authorities");  // KHÔNG phải scope/scp
    authoritiesConverter.setAuthorityPrefix("");                  // raw permission code
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
    converter.setPrincipalClaimName("sub");
    return converter;
}

@Bean
SecurityFilterChain api(HttpSecurity http, JwtAuthenticationConverter conv) throws Exception {
    return http
        .authorizeHttpRequests(a -> a
            .requestMatchers("/actuator/health/**").permitAll()
            .anyRequest().authenticated())
        .oauth2ResourceServer(o -> o.jwt(j -> j
            .jwkSetUri("https://auth.smartquiz.vn/.well-known/jwks.json")  // cache 1h
            .jwtAuthenticationConverter(conv)))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(CsrfConfigurer::disable)
        .build();
}
```

### 9.4 Row-level security ClickHouse (defense in depth)

Ngay cả khi app layer có bug, ClickHouse row policy chặn leak org khác:

```sql
-- Setting phải declare trước khi dùng trong row policy (CH 23+)
SET GLOBAL ON CLUSTER '{cluster}' SETTING custom_setting_name = '';

-- Policy filter theo org_id user hiện tại
CREATE ROW POLICY IF NOT EXISTS org_filter ON exam_facts
    USING org_id = toUUID(getSetting('SQL_app_current_org_id'))
    TO role_instructor, role_admin;

-- Platform admin bypass
CREATE ROW POLICY IF NOT EXISTS org_filter_bypass ON exam_facts
    USING 1
    TO role_platform_admin;
```

**Cách app set session variable — BẮT BUỘC mỗi request** (nếu quên → policy filter thấy `''` → `toUUID('')` fail hoặc match 0 row → user thấy empty, dễ miss ở test):

```java
// ClickHouseSessionAwareDataSource wraps DataSource, intercept getConnection()
@Component
class ClickHouseQueryExecutor {
    private final DataSource ds;

    public <T> T runAsUser(Authentication auth, Function<Connection, T> query) {
        UUID orgId = UUID.fromString((String) ((Jwt) auth.getPrincipal()).getClaim("org_id"));
        boolean isPlatformAdmin = "super_admin".equals(
            ((Jwt) auth.getPrincipal()).getClaim("platform_role"));

        try (Connection conn = ds.getConnection()) {
            // ClickHouse JDBC (com.clickhouse:clickhouse-jdbc) hỗ trợ setClientInfo
            // hoặc set qua SET statement per session
            try (Statement st = conn.createStatement()) {
                st.execute("SET SQL_app_current_org_id = '" + orgId + "'");
                // KHÔNG set role cho platform_admin — dùng GRANT role tĩnh trong CH users.xml
                // (ở §16.5 secrets); session chỉ override org_id
            }
            return query.apply(conn);
        }
    }
}

// UseCase
@Transactional(readOnly = true)
public DashboardDto getExamDashboard(UUID examId) {
    return chExecutor.runAsUser(auth, conn -> {
        // Query chỉ thấy rows thuộc org_id của user + exam_id
        try (PreparedStatement ps = conn.prepareStatement(
            "SELECT * FROM v_exam_daily_stats WHERE exam_id = ?")) {
            ps.setObject(1, examId);
            return mapper.toDashboard(ps.executeQuery());
        }
    });
}
```

**Rule bắt buộc:**
- **Không** set role động qua `SET ROLE ...` trong session — dễ escalate bug. Role gán tĩnh ở `users.xml` (config map) theo service account: `analytics_api_instructor_role`, `analytics_api_admin_role`, `analytics_api_platform_admin_role`. App chọn DataSource theo claim `platform_role` của JWT (3 pool riêng, cache 60s).
- **Integration test bắt buộc** (§17.6): seed 2 org, JWT org A, query exam_facts → assert chỉ thấy org A rows; JWT với `platform_role=super_admin` → thấy cả 2.
- Nếu migration mới thêm bảng fact → **phải** tạo row policy tương ứng (checklist §18.6 schema delta).

---

## X. API ENDPOINTS CHI TIẾT

### 10.0 API conventions

Bảng §10.1-10.5 là tóm tắt. **Contract đầy đủ ở OpenAPI spec** `api/src/main/resources/static/openapi.yaml` (§18.1 prereq). Mọi endpoint **phải** tuân theo convention dưới — align `auth-service-design.md §12.0`. §10.7 có template 2 endpoint điển hình.

#### 10.0.1 Versioning

- **Tất cả endpoint REST** có prefix `/api/v1/` (bảng dưới viết tắt `/analytics/...` = `/api/v1/analytics/...`).
- **Rule shorthand vs literal**: bảng + prose dùng shorthand OK. NHƯNG cookie/CORS/OpenAPI phải dùng full `/api/v1/analytics/...`.
- **Endpoint chuẩn không versioned:** `/actuator/*`, `/v3/api-docs`, `/swagger-ui.html`.
- **Breaking change:** bump major → `/api/v2/`, giữ `/api/v1/` tối thiểu 6 tháng + header `Sunset: <date>` (RFC 8594).
- **Content negotiation:** `Accept: application/json` default hoặc `Accept: application/vnd.smartquiz.v1+json`.
- **gRPC** (§10.6) dùng proto package `vn.smartquiz.analytics.v1` — bump khi breaking.

#### 10.0.2 Content-Type & encoding

- Request: `Content-Type: application/json; charset=utf-8`. Từ chối khác với `415`. Export upload (future) dùng `multipart/form-data`.
- Response: luôn `application/json; charset=utf-8` — kể cả error (RFC 7807). Export **download** trả `text/csv`, `application/vnd.ms-excel`, `application/octet-stream` (Parquet).
- Không nhận trailing slash — `/api/v1/analytics/me/progress/` → `308`.
- Request body size cap: **1 MB**. Vượt → `413`.

#### 10.0.3 Naming + formats

| Thành phần | Rule | Ví dụ |
| ---------- | ---- | ----- |
| Path segment | lowercase, hyphen-separated | `/analytics/exams/{id}/question-stats` |
| Query param | lowercase, hyphen-separated | `?subject-code=MATH&from=2026-01-01` |
| JSON field | **snake_case** | `avg_score`, `time_spent_sec`, `calibrated_at` |
| JSON enum value | lowercase snake_case | `"feedback_type": "rating"` |
| HTTP header custom | `X-` prefix, kebab-case | `X-Request-Id`, `X-Analytics-Cache` |
| UUID | canonical hyphenated, lowercase | `a0000000-0000-0000-0000-000000000004` |
| Timestamp | **ISO 8601 UTC with Z** | `"2026-04-22T10:05:22.123Z"` |
| Duration trong body | seconds (int) hoặc ISO 8601 duration | `"time_spent_sec": 1800` |
| Bool | native JSON | `true`/`false` |
| Missing vs null | **Missing** = "không nói về field"; **`null`** = "field tồn tại, unset" | — |

#### 10.0.4 Status code conventions

| Code | Dùng khi | Body |
| ---- | -------- | ---- |
| `200 OK` | GET thành công, POST/PATCH trả resource | JSON |
| `201 Created` | POST tạo experiment/report job | JSON + header `Location` |
| `202 Accepted` | Export async, schedule report | `{request_id, status: "queued"}` |
| `204 No Content` | DELETE, stop experiment | — |
| `400` | JSON malformed, content-type sai | RFC 7807 |
| `401` | JWT thiếu/sai | RFC 7807 + `WWW-Authenticate: Bearer` |
| `403` | Permission thiếu | RFC 7807 |
| `404` | Resource không tồn tại | RFC 7807 |
| `409` | State conflict (experiment đã running, idempotency key mismatch) | RFC 7807 |
| `413` | Result quá lớn (> 10M rows) — gợi ý export thay vì API | RFC 7807 |
| `422` | Semantic validation fail (param không hợp lệ) | RFC 7807 + `errors[]` |
| `429` | Rate limit | RFC 7807 + `Retry-After`, `X-RateLimit-*` |
| `500` | Unhandled server error | RFC 7807 chỉ có `trace_id` |
| `503` | ClickHouse down, materialized view chưa ready | RFC 7807 + `Retry-After` |
| `504` | Query vượt 30s timeout | RFC 7807 |

**400 vs 422**: 400 cho syntax (JSON malformed); 422 cho semantic (`from > to`, `limit > 100`).

#### 10.0.5 Error response — RFC 7807 Problem Details

Xem §XV.1 format chuẩn. Field bổ sung cho 422:

```json
{
  "type": "https://smartquiz.vn/errors/validation-failed",
  "title": "Tham số không hợp lệ",
  "status": 422,
  "code": "ANALYTICS_VALIDATION_FAILED",
  "trace_id": "abc123",
  "timestamp": "2026-04-22T10:05:22Z",
  "errors": [
    { "field": "from", "code": "INVALID_RANGE", "message": "from phải trước to" },
    { "field": "limit", "code": "OUT_OF_RANGE", "message": "limit max 100" }
  ]
}
```

#### 10.0.6 Idempotency

POST endpoint mutation **phải hỗ trợ** `Idempotency-Key` header (RFC draft):
- Format UUID v4 client sinh.
- Server cache response Redis `idempotency:analytics:{key}:{user_id}` TTL **24h**.
- Replay cùng key trong 24h → trả response cache, KHÔNG re-execute.
- Conflict (cùng key, body khác) → `409 IDEMPOTENCY_KEY_REUSE_DIFFERENT_BODY`.

Endpoint bắt buộc idempotency: `/analytics/export/*`, `/experiments` (create), `/admin/reports/generate`.

Endpoint KHÔNG cần: GET (natively idempotent), `/experiments/{id}/stop` (state-based, idempotent tự nhiên).

#### 10.0.7 Rate limit headers

Mọi response endpoint có rate limit (§XII.3) trả:

```
X-RateLimit-Limit: 1000
X-RateLimit-Remaining: 987
X-RateLimit-Reset: 2026-04-22T11:00:00Z
Retry-After: 900        # chỉ khi 429
```

Body 429:
```json
{
  "type": "https://smartquiz.vn/errors/rate-limit",
  "title": "Quá nhiều query",
  "status": 429,
  "code": "ANALYTICS_RATE_LIMIT",
  "retry_after": 900,
  "limit": 1000,
  "window": "1h",
  "trace_id": "...",
  "timestamp": "..."
}
```

#### 10.0.8 Pagination

Convention **cursor-based** (stable under concurrent insert). Query: `?cursor=<opaque>&limit=50`.

- `limit` max **100**, default **20**. Vượt max → `422`.
- Response:
  ```json
  {
    "items": [ ... ],
    "page_info": {
      "next_cursor": "eyJpZCI6IjEyMzQifQ==",
      "has_next": true
    }
  }
  ```
- Không dùng offset-based — ClickHouse `OFFSET` lớn scan tốn kém.
- List endpoints Analytics: `/analytics/me/attempts/history`, `/admin/reports/available`, `/experiments`, `/analytics/exams/{id}/attempts`.

#### 10.0.9 Standard request/response headers

Client nên gửi:

| Header | Required |
| ------ | -------- |
| `Authorization: Bearer <jwt>` | Có — trừ public endpoint (none ở Analytics) |
| `X-Request-Id: <uuid>` | Không — server sinh nếu thiếu |
| `Idempotency-Key: <uuid>` | Endpoint §10.0.6 |
| `Accept-Language: vi,en;q=0.8` | Không — default `vi` |

Server luôn trả:

| Header | Nội dung |
| ------ | -------- |
| `X-Request-Id` | Echo hoặc mới sinh |
| `X-Trace-Id` | OTel trace id |
| `X-Analytics-Cache: HIT\|MISS\|STALE` | Cache status (Redis + MV) — giúp FE debug |
| `Cache-Control: no-store` | Default; dashboard cached Redis 60s trả `max-age=60` |

#### 10.0.10 No response envelope

Response KHÔNG wrap `{data: ...}`. GET resource trả resource trực tiếp; error RFC 7807. List endpoint có shape `{items, page_info}` (§10.0.8).

---

### 10.1 Student-facing

| Method | Path | Output | Permission |
| ------ | ---- | ------ | ---------- |
| GET | `/analytics/me/progress?subject-code=&from=&to=` | Learning curve | `analytics.self` |
| GET | `/analytics/me/strengths-weaknesses` | Phân tích bloom level + topic | `analytics.self` |
| GET | `/analytics/me/attempts/history?cursor=&limit=` | Paginated past attempts | `analytics.self` |
| GET | `/analytics/me/compared-to-peers?exam-id=` | Percentile + distribution | `analytics.self` |
| GET | `/analytics/me/recommendations` | Gợi ý câu hỏi nên luyện (dùng IRT) | `analytics.self` |

### 10.2 Teacher / instructor

| Method | Path | Output | Permission |
| ------ | ---- | ------ | ---------- |
| GET | `/analytics/exams/{id}/overview` | Attempts, avg, pass rate, flagged | `analytics.exam` + same-org check |
| GET | `/analytics/exams/{id}/distribution` | Histogram scores | `analytics.exam` |
| GET | `/analytics/exams/{id}/question-stats` | Per-question correct rate, time | `analytics.exam` |
| GET | `/analytics/exams/{id}/difficulty-vs-time` | Scatter plot | `analytics.exam` |
| GET | `/analytics/exams/{id}/cheat-summary` | Layer breakdown, suspicious attempts | `analytics.exam` |
| GET | `/analytics/questions/{id}/performance` | Time series stats | `analytics.exam` |
| GET | `/analytics/subjects/{code}/class-performance?class-id=` | — | `analytics.exam` |

### 10.3 Admin / org

| Method | Path | Permission |
| ------ | ---- | ---------- |
| GET | `/analytics/orgs/{id}/usage?month=` | `analytics.org` |
| GET | `/analytics/orgs/{id}/users/active-monthly` | `analytics.org` |
| GET | `/analytics/orgs/{id}/cheat-rate-trend` | `analytics.org` |
| GET | `/analytics/orgs/{id}/ai-usage-summary` | `analytics.org` |
| GET | `/admin/reports/available?cursor=&limit=` | `analytics.org` |
| POST | `/admin/reports/generate` body `{type, params}` → async `202` | `analytics.org` + Idempotency-Key |
| GET | `/admin/reports/{id}/download` | `analytics.org` |

### 10.4 Export

| Method | Path | Output | Permission |
| ------ | ---- | ------ | ---------- |
| POST | `/analytics/export/exam-results` body `{exam_id, format}` | CSV/XLSX/Parquet async `202 {job_id}` | `analytics.export` **+ isOwner(exam_id)** HOẶC `analytics.org` + same-org; Idempotency-Key |
| POST | `/analytics/export/question-bank-stats` body `{subject_code, format}` | — | `analytics.org` (chỉ admin) + Idempotency-Key |
| GET | `/analytics/export/{job_id}` | Status + download URL (S3 pre-signed) | Job owner (job.created_by == auth.sub) HOẶC `analytics.org` same-org |

> **⚠️ Ownership enforcement**: `analytics.export` alone KHÔNG đủ cho instructor — phải check `isOwner(exam_id)` (instructor tạo exam). Admin có `analytics.org` bypass ownership check nhưng vẫn same-org. Xem §9.2 policy expression. Auth §3.4 seed matrix: `analytics.export` ✔(own exam) cho instructor.

### 10.5 Experiments (A/B) — platform_admin only

| Method | Path | Permission |
| ------ | ---- | ---------- |
| POST | `/experiments` | `analytics.experiment` + Idempotency-Key |
| GET | `/experiments?cursor=&limit=` | `analytics.experiment` |
| GET | `/experiments/{id}/results` | `analytics.experiment` |
| POST | `/experiments/{id}/stop` | `analytics.experiment` |
| GET | `/experiments/{id}/exposures?cursor=&limit=` | `analytics.experiment` |

### 10.6 gRPC (internal)

```proto
syntax = "proto3";
package vn.smartquiz.analytics.v1;

service AnalyticsService {
    rpc GetQuestionIrt(GetQuestionIrtRequest) returns (IrtParams);
    rpc BatchGetQuestionIrt(BatchIrtRequest) returns (BatchIrtResponse);
    rpc RecordExposure(RecordExposureRequest) returns (Empty);   // A/B exposure logging
    rpc GetUserSegment(UserSegmentRequest) returns (UserSegment);
    rpc GetHistoricalAggregate(HistAggRequest) returns (HistAggResponse);  // CDS dùng (ADR-002)
}
```

Consumer:
- **Question Service**: `BatchGetQuestionIrt` khi chọn câu adaptive.
- **Cheating Detection** (ADR-002 §implementation): `GetHistoricalAggregate` cho L6 cross-attempt similarity.
- **Exam Service**: `RecordExposure` khi user vào variant experiment.

Cache response Redis 60s.

### 10.7 Endpoint contract template — reference cho OpenAPI generation

Hai endpoint điển hình dưới làm **template** cho tất cả endpoint còn lại. Convention §10.0 apply. OpenAPI spec đầy đủ nằm ở `api/src/main/resources/static/openapi.yaml` — **nguồn truth** cho BE/FE codegen.

#### 10.7.1 GET /api/v1/analytics/exams/{id}/overview

**Request:**

```http
GET /api/v1/analytics/exams/7c9e6f8b-2a3d-4e5f-b1a0-1234567890ab/overview HTTP/2
Host: analytics.smartquiz.vn
Accept: application/json
Authorization: Bearer eyJhbGciOiJSUzI1NiIsImtpZCI6IjIwMjYtMDQtMDEifQ...
X-Request-Id: 7c9e6f8b-2a3d-4e5f-b1a0-1234567890ab
```

**Response 200:**

```http
HTTP/2 200
Content-Type: application/json; charset=utf-8
X-Request-Id: 7c9e6f8b-2a3d-4e5f-b1a0-1234567890ab
X-Trace-Id: 4bf92f3577b34da6a3ce929d0e0e4736
X-Analytics-Cache: HIT
X-RateLimit-Limit: 1000
X-RateLimit-Remaining: 987
X-RateLimit-Reset: 2026-04-22T11:00:00Z
Cache-Control: max-age=60

{
  "exam_id": "7c9e6f8b-2a3d-4e5f-b1a0-1234567890ab",
  "title": "Kiểm tra giữa kỳ Toán 10",
  "total_attempts": 312,
  "submitted_count": 298,
  "in_progress_count": 14,
  "avg_score": 72.3,
  "median_score": 74.0,
  "p90_score": 91.5,
  "pass_rate": 0.68,
  "avg_time_sec": 1820,
  "flagged_count": 7,
  "generated_at": "2026-04-22T10:05:00Z"
}
```

**Response Schema:**

```yaml
ExamOverviewResponse:
  type: object
  required: [exam_id, total_attempts, avg_score, generated_at]
  additionalProperties: false
  properties:
    exam_id:          { type: string, format: uuid }
    title:            { type: string }
    total_attempts:   { type: integer, minimum: 0 }
    submitted_count:  { type: integer, minimum: 0 }
    in_progress_count:{ type: integer, minimum: 0 }
    avg_score:        { type: number, format: float, minimum: 0, maximum: 100 }
    median_score:     { type: number, format: float, minimum: 0, maximum: 100 }
    p90_score:        { type: number, format: float, minimum: 0, maximum: 100 }
    pass_rate:        { type: number, format: float, minimum: 0, maximum: 1 }
    avg_time_sec:     { type: integer, minimum: 0 }
    flagged_count:    { type: integer, minimum: 0 }
    generated_at:     { type: string, format: date-time }
```

**Error responses:**

| Status | Code | Khi nào |
| ------ | ---- | ------- |
| 401 | `AUTH_TOKEN_INVALID` | JWT sai/thiếu |
| 403 | `ANALYTICS_FORBIDDEN` | Thiếu `analytics.exam` hoặc không cùng org với exam |
| 404 | `EXAM_NOT_FOUND` | `exam_id` không tồn tại |
| 503 | `ANALYTICS_DATA_NOT_READY` | Materialized view chưa refresh lần đầu (exam mới tạo) |
| 504 | `ANALYTICS_QUERY_TIMEOUT` | Query ClickHouse > 30s |

#### 10.7.2 POST /api/v1/analytics/export/exam-results

**Request:**

```http
POST /api/v1/analytics/export/exam-results HTTP/2
Content-Type: application/json; charset=utf-8
Authorization: Bearer eyJ...
Idempotency-Key: 8a9f1e2b-3c4d-4567-89ab-cdef01234567

{
  "exam_id": "7c9e6f8b-2a3d-4e5f-b1a0-1234567890ab",
  "format": "xlsx",
  "include_pii": false
}
```

**Request Schema:**

```yaml
ExportExamResultsRequest:
  type: object
  required: [exam_id, format]
  additionalProperties: false
  properties:
    exam_id:     { type: string, format: uuid }
    format:      { type: string, enum: [csv, xlsx, parquet] }
    include_pii: { type: boolean, default: false, description: "Admin only; false mặc định mask email/IP" }
```

**Response 202:**

```http
HTTP/2 202
Content-Type: application/json; charset=utf-8
Location: /api/v1/analytics/export/job_01H7Z8Y6AE5

{
  "job_id": "job_01H7Z8Y6AE5",
  "status": "queued",
  "format": "xlsx",
  "estimated_ready_at": "2026-04-22T10:08:00Z"
}
```

**Response Schema (job status):**

```yaml
ExportJobResponse:
  type: object
  required: [job_id, status]
  additionalProperties: false
  properties:
    job_id:    { type: string }
    status:    { type: string, enum: [queued, running, succeeded, failed] }
    format:    { type: string }
    estimated_ready_at: { type: string, format: date-time }
    download_url:       { type: string, format: uri, description: "Có khi status=succeeded; S3 pre-signed 15 phút" }
    failure_reason:     { type: string, description: "Có khi status=failed" }
```

**Error responses:**

| Status | Code | Khi nào |
| ------ | ---- | ------- |
| 400 | `ANALYTICS_MALFORMED_REQUEST` | JSON parse fail |
| 403 | `ANALYTICS_FORBIDDEN` | Thiếu `analytics.export` hoặc không own exam |
| 409 | `IDEMPOTENCY_KEY_REUSE_DIFFERENT_BODY` | Idempotency-Key reuse body khác |
| 413 | `ANALYTICS_RESULT_TOO_LARGE` | Exam > 10M rows (phải dùng Spark export) |
| 422 | `ANALYTICS_VALIDATION_FAILED` | `format` ngoài enum, `include_pii=true` mà user không phải admin |
| 429 | `ANALYTICS_RATE_LIMIT` | Vượt 10 export/h/user |

#### 10.7.3 Pattern tổng hợp (các endpoint còn lại)

Mỗi endpoint OpenAPI spec **phải** có:
1. **Operation ID** `{verb}{Resource}` — vd `getExamOverview`, `exportExamResults`.
2. **Summary** 1 dòng + **Description** link sang design doc section.
3. **Request body schema** với `additionalProperties: false`, `required`, constraints.
4. **Response schema** per status code — 2xx + tất cả error 4xx/5xx applicable.
5. **Examples** cho cả happy path và error.
6. **Security requirements** — `security: [{ bearerAuth: [] }]`.
7. **`x-codeSamples`** (optional) cho curl/JS/Python.
8. **Rate limit** note trong description hoặc `x-ratelimit` extension.

Tooling:
- BE: `springdoc-openapi` tự-gen từ `@RestController` + merge với `openapi.yaml` static.
- FE: `openapi-typescript-codegen` → TS client (align auth §18.5 OQ-8).

### 10.8 Query caching & cache invalidation

API layer cache (Redis):

| Endpoint pattern | TTL | Invalidation trigger |
| ---------------- | --- | -------------------- |
| `/exams/{id}/overview` | 60s | `attempt.graded.v1` cho exam → evict `exams:{id}:*` |
| `/exams/{id}/question-stats` | 5 phút | `attempt.graded.v1` |
| `/me/progress` | 5 phút | `attempt.graded.v1` cho user |
| `/me/recommendations` | 30 phút | `analytics.irt.calibrated.v1` cho bất kỳ q user từng làm |
| `/orgs/{id}/monthly-usage` | 1 giờ | cron nightly rebuild |

Cache invalidation chạy trong consumer Kafka của chính Analytics — không cần cross-service call.

---

## XI. EVENTS — TRANSACTIONAL OUTBOX + AVRO

> **⚠️ Nguồn truth topic + schema: `shared-contracts/avro/TOPICS.md`** (catalog tất cả topic của repo) và `shared-contracts/avro/analytics/*.avsc`. Bảng §11.1 là **view tóm tắt**. Khi lệch, file shared-contracts thắng. PR đổi topic **phải cập nhật cả 2 nơi + CLAUDE.md §8** trong cùng commit.

Tuân thủ **ADR-001** (`docs/adr/ADR-001-sla-rpo-outbox.md`) và CLAUDE.md §3:

- Event **critical** (drive state change ở service khác): insert `outbox` trong cùng transaction với domain mutation (ClickHouse insert hoặc PG insert nếu có).
- Relayer (leader-elected qua `pg_try_advisory_lock`) poll `SELECT ... FOR UPDATE SKIP LOCKED`, publish Kafka, `UPDATE published_at`.
- Payload encode **Avro**, schema ở Apicurio Schema Registry với compat mode **BACKWARD** (CLAUDE.md §2).

### 11.1 Phân loại event Analytics PUBLISH

**Critical — BẮT BUỘC qua outbox** (consumer drive state change; mất event = sai logic downstream):

| Topic (v1) | Aggregate key | Payload (Avro) | Consumer | Source |
| ---------- | ------------- | -------------- | -------- | ------ |
| `analytics.irt.calibrated.v1` | `question_ref_id` | `{question_id, a, b, c, n_responses, calibrated_at, method, converged}` | Question Service (update `question.irt_params` MongoDB → Exam Service chọn câu adaptive dùng params này) | Spark job §7.1 |
| `analytics.dif.flagged.v1` | `question_ref_id` | `{question_id, demographic_group, mh_stat, p_value, flagged_at}` | Question Service (flag review queue), Notification (email giáo viên) | Spark job §7.2 |
| `analytics.experiment.concluded.v1` | `experiment_id` | `{experiment_id, winner_variant, p_value, effect_size, concluded_at}` | Exam Service (rollout winner config), Notification | API `/experiments/{id}/stop` hoặc auto-stop |
| `analytics.report.ready.v1` | `report_id` | `{report_id, org_id, s3_url, type, generated_at, expires_at}` | Notification (gửi download link admin — link dùng 1 lần, mất event = admin không nhận được) | Async export job |

**Fire-and-forget — KHÔNG qua outbox** (mất vài event OK — không ảnh hưởng correctness state downstream):

| Topic (v1) | Key | Payload | Consumer | Lý do fire-and-forget |
| ---------- | --- | ------- | -------- | --------------------- |
| `analytics.cohort.assigned.v1` | `user_id` | `{user_id, cohort_id, cohort_name, assigned_at}` | Notification (recommendation push), AI Service (feature enrich) | Cohort regenerate weekly; mất event = tuần sau job chạy lại vẫn có. Consumer chỉ dùng cho **personalization nhẹ** (notification gợi ý, không drive state machine). Nếu AI Service cần strong-consistency cohort → Phase 3 chuyển lên critical. |
| `analytics.dashboard.view.v1` | `user_id` | `{user_id, dashboard_key, ts}` | Internal telemetry (Analytics consume chính mình để tune cache) | Spike 50k/phút lúc peak, write amp outbox không đáng |
| `analytics.export.started.v1` / `.finished.v1` | `job_id` | `{job_id, user_id, bytes, duration_ms}` | Metric aggregation | Audit, không drive state |

**Rule phân loại:** ADR-001 §3 — "outbox khi consumer drive state change downstream, fire-and-forget khi chỉ observability/notification nhẹ". Cohort assignment borderline, đánh giá lại khi AI Service đến Phase 3 và thực sự consume để routing model.

### 11.2 Code pattern — outbox publisher

**Encoding strategy:** payload lưu **JSONB** ở `outbox` (dễ debug). **Relayer** mới encode Avro trước khi publish Kafka (align `auth-service-design.md §11.2`).

**⚠️ Propagation rule — critical cho đúng outbox invariant:**

- **UseCase** (caller): `@Transactional(propagation = REQUIRED)` — mở TX.
- **AnalyticsOutboxPublisher**: `@Transactional(propagation = MANDATORY)` — **bắt buộc đã có TX**; throws `IllegalTransactionStateException` nếu caller gọi ngoài TX.

Tại sao MANDATORY không REQUIRED? Nếu REQUIRED: caller quên `@Transactional` → publisher tự mở TX riêng → outbox row commit trước/sau domain change → **half-write bug**, exactly cái outbox cần tránh. MANDATORY fail-fast ngay lúc dev.

Enforcement: unit test `testPublisherThrowsWithoutTx()` assert `IllegalTransactionStateException` khi gọi ngoài `@Transactional`.

```java
@Service
@Transactional(propagation = Propagation.MANDATORY)   // fail-fast nếu caller không có TX
class AnalyticsOutboxPublisher {
    private final OutboxRepository repo;
    private final ObjectMapper jsonMapper;

    public void publish(String topic, String eventType,
                        String aggregateType, String aggregateId,
                        Object payload, String partitionKey) {
        UUID eventId = UUID.randomUUID();
        OutboxRow row = new OutboxRow(
            eventId, aggregateType, aggregateId,
            topic, eventType,
            jsonMapper.valueToTree(payload),
            Map.of("trace_id", MDC.get("trace_id"),
                   "schema_version", "1"),
            partitionKey
        );
        repo.save(row);
    }
}

// UseCase — publish experiment result
@Service
class ConcludeExperimentUseCase {
    @Transactional  // REQUIRED default
    public void execute(UUID experimentId, ExperimentResult result) {
        ExperimentDefinition ed = experimentRepo.findById(experimentId).orElseThrow();
        ed.conclude(result);
        experimentRepo.save(ed);

        outbox.publish(
            "analytics.experiment.concluded.v1",
            "ExperimentConcluded",
            "experiment",
            experimentId.toString(),
            new ExperimentConcludedPayload(experimentId, result.winnerVariant(),
                result.pValue(), result.effectSize(), Instant.now()),
            experimentId.toString()   // partition key
        );
    }
}

// Relayer — leader-elected via pg_try_advisory_lock, async publish + per-row error isolation
@Component
class AnalyticsOutboxRelayer implements SmartLifecycle {
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
            GenericRecord avroPayload = avroMapper.toAvro(row);
            ProducerRecord<String, GenericRecord> rec = new ProducerRecord<>(
                row.topic(), row.partitionKey(), avroPayload);
            rec.headers().add("trace_id", row.headers().get("trace_id").getBytes(UTF_8));
            rec.headers().add("event_id", row.eventId().toString().getBytes(UTF_8));

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

### 11.3 Avro schema convention

- File: `shared-contracts/avro/analytics/analytics.irt.calibrated.v1.avsc`
- Namespace: `vn.smartquiz.analytics.v1`
- Rule BACKWARD compat: **chỉ được add field với default**, **không** remove/rename field, **không** đổi type. Breaking change → topic `.v2` mới.
- CI gate: `./gradlew :shared-contracts:avroCompatCheck`.

### 11.4 Producer/Relayer config

| Setting | Value | Lý do |
| ------- | ----- | ----- |
| `acks` | `all` | Đợi ISR replicate — không mất event |
| `enable.idempotence` | `true` | Chống duplicate do retry |
| `retries` | `Integer.MAX_VALUE` | **Required** bởi `enable.idempotence=true` (Kafka ≥ 3.x enforce). Bounded bởi `delivery.timeout.ms` nên không retry vô hạn thực tế. |
| `max.in.flight.requests.per.connection` | `5` | Giữ order per partition (idempotent producer guarantee với ≤5) |
| `compression.type` | `zstd` | Giảm network cost |
| `delivery.timeout.ms` | `30000` | Broker slow → fail fast, relayer mark last_error, pick rows khác |
| `request.timeout.ms` | `5000` | Mỗi request không chờ quá 5s |
| `linger.ms` | `20` | Micro-batch để tăng throughput, RPO 5s chịu được |
| Poll interval relayer | `100ms` fixedDelay | RPO 5s (ADR-001) |
| Batch budget relayer | `3000ms` wall-clock | Leave rest cho poll tiếp |
| Batch size relayer | `500 rows / poll` max | `claimPending` cap |
| `spring.lifecycle.timeout-per-shutdown-phase` | `30s` | SIGTERM → drain inflight + `kafka.flush()` |

### 11.5 Metric outbox bắt buộc

| Metric | Alert |
| ------ | ----- |
| `analytics_outbox_pending_size` (gauge) | warning > 1k, critical > 10k |
| `analytics_outbox_publish_lag_seconds` (histogram) | p99 > 5s = critical (vi phạm ADR-001 RPO) |
| `analytics_outbox_publish_failed_total{reason}` (counter) | spike > 10/min → page |
| `analytics_consumer_dedup_total{result=duplicate\|new}` | bất thường > 50% duplicate → replay bug |

---

## XII. RATE LIMITING & QUERY GOVERNANCE

### 12.1 ClickHouse query governance

- **Max query time**: 30s; `max_execution_time = 30` setting. Kill tự động.
- **Max memory**: 10GB per query (`max_memory_usage = 10_000_000_000`).
- **Quota per user**: 1000 queries/hour/user (CH native quota).
- **Log slow queries > 5s** → dedicated table `query_log` — review để optimize hoặc thêm MV.

### 12.2 API rate limit (Redis sliding window — align auth §9.1)

| Endpoint group | Key | Giới hạn | Hành động |
| -------------- | --- | -------- | --------- |
| Dashboard GET | `rate:dash:{user_id}` | 1000/hour | 429 |
| Export POST | `rate:export:{user_id}` | 10/hour | 429 |
| Report generate | `rate:report:{org_id}` | 20/day | 429 |
| Experiment create | `rate:exp:{user_id}` | 50/day | 429 (platform_admin) |
| gRPC internal | — (service account, không limit) | — | — |

Implementation: Lua script atomic trên Redis (giống auth §9.1).

### 12.3 Caching layers

```
Client → API service (Redis cache ~1 min)
         ↓
         ClickHouse query cache (internal, 1 hour)
         ↓
         Materialized view (precomputed, realtime refresh)
         ↓
         Base tables
```

---

## XIII. IRT CALIBRATION (deep dive)

### 13.1 Mô hình 3PL

```
P(correct | θ, a, b, c) = c + (1 - c) * 1/(1 + exp(-a*(θ - b)))
```

- `a` (discrimination): 0.5-2.0 thông thường, outlier > 3 rare.
- `b` (difficulty): -3 → 3 thang.
- `c` (guessing): 0 với essay, ~0.25 với MC 4 option.

### 13.2 Estimation — Marginal MLE với EM

1. Khởi tạo `a=1, b=0, c=0.25` cho câu mới.
2. E-step: với θ estimate của students đã trả lời, compute expected responses.
3. M-step: maximize log-likelihood với Newton-Raphson → update `a, b, c`.
4. Iterate đến converge.

**Constraint:**
- `a > 0` (nếu < 0 → reverse-scored bug).
- `-4 ≤ b ≤ 4`.
- `0 ≤ c ≤ 0.5`.

### 13.3 Cold start

Câu hỏi mới chưa đủ 30 responses:
- Dùng `difficulty_assigned` (1-5) từ giáo viên → map sang thang `b`.
- `a = 1.0` default.
- `c = 0.25` MC, `0` khác.
- Flag `calibrated=false` trên question.

### 13.4 Publication criteria (publish qua outbox)

Chỉ publish `analytics.irt.calibrated.v1` nếu:
- Responses ≥ 30.
- Convergence đạt.
- Không outlier (|a| < 5, |b| < 4).
- Point-biserial correlation > 0.15.

Câu không đạt → flag `low_quality` trong `question_irt_params.converged=0`, **không** publish event.

### 13.5 Bayesian shrinkage (prior)

Tránh overfit câu hỏi mới với ít data:
```
a_posterior = (n/(n+k)) * a_mle + (k/(n+k)) * a_prior
```
Với `k = 30` — prior = global mean.

---

## XIV. OBSERVABILITY

Tuân thủ stack CLAUDE.md §2: **Micrometer → Prometheus**, **OpenTelemetry OTLP**, **Loki** log push.

### 14.1 Metrics (Prometheus, expose `/actuator/prometheus`)

| Metric | Type | Label |
| ------ | ---- | ----- |
| `analytics_ingest_events_total` | counter | `topic` |
| `analytics_ingest_lag_seconds` | gauge | `topic` |
| `analytics_consumer_dedup_total` | counter | `topic, result=new\|duplicate` |
| `analytics_dashboard_query_duration_seconds` | histogram | `endpoint` |
| `analytics_ch_query_duration_seconds` | histogram | `query_name` |
| `analytics_cache_hit_ratio` | gauge | `endpoint` |
| `analytics_flink_job_up` | gauge | `job_name` |
| `analytics_flink_checkpoint_duration_seconds` | histogram | `job_name` |
| `analytics_flink_backpressure_ratio` | gauge | `job_name` |
| `analytics_spark_job_duration_seconds` | histogram | `job_name` |
| `analytics_spark_job_status_total` | counter | `job_name, status=success\|failed` |
| `analytics_mv_refresh_lag_seconds` | gauge | `view_name` |
| `analytics_irt_calibration_questions_updated_total` | counter | — |
| `analytics_dif_flags_detected_total` | counter | `demographic` |
| `analytics_outbox_pending_size` | gauge | — |
| `analytics_outbox_publish_lag_seconds` | histogram | — |
| `analytics_outbox_publish_failed_total` | counter | `reason` |
| `analytics_export_job_duration_seconds` | histogram | `format` |

### 14.2 Tracing (OpenTelemetry)

- Instrument qua `io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter`.
- Export OTLP gRPC → `otel-collector:4317`. Collector fanout Tempo/Jaeger.
- Span attribute: `analytics.user_id`, `analytics.org_id`, `analytics.query_name`, `analytics.cache` (HIT/MISS).
- **Cấm** set raw email, IP làm attribute → dùng hash.
- Trace propagation gateway: header `traceparent` (W3C). Filter MDC set `trace_id` = span trace id.

### 14.3 SLO (reference ADR-001 §1)

| SLI | Target | Ghi chú |
| --- | ------ | ------- |
| Dashboard API p95 | < 500ms | Materialized view + Redis cache |
| Dashboard API p99 | < 2s | Ad-hoc ClickHouse |
| gRPC `GetQuestionIrt` p99 | < 50ms | Hot path Question Service |
| Flink streaming lag p95 | < 10s | — |
| Spark daily job success rate | > 99% | Weekly p.o.v. |
| Data freshness (event → ClickHouse) p95 | < 5 phút | Path A Kafka Engine |
| Outbox publish lag p99 | < 5s | ADR-001 RPO |
| Availability (dashboard) | 99.9% | Error budget 43.8 min/tháng |

Error budget burn alert: > 2%/hour fast burn → page.

### 14.4 Logs — structured JSON + MDC

Logback encoder `net.logstash.logback:logstash-logback-encoder`. `MdcFilter` set: `trace_id`, `span_id`, `user_id`, `org_id`, `request_id`, `exam_id` (nếu có), `client_ip`.

```json
{
  "ts": "2026-04-22T10:05:22.123Z",
  "level": "INFO",
  "service": "analytics-service",
  "trace_id": "7c9e6f8b2a3d4e5f...",
  "span_id": "1a2b3c4d...",
  "request_id": "req-abc123",
  "user_id": "a0000000-...-000004",
  "org_id": "11111111-...-111111",
  "event": "dashboard.exam.overview.query",
  "exam_id": "7c9e6f8b-...",
  "duration_ms": 342,
  "cache_status": "HIT",
  "query_name": "exam_overview"
}
```

**Masking filter bắt buộc** (Logback `MaskingPatternLayout`):
- Regex mask: `access_token`, `refresh_token`, `password` → `***REDACTED***`.
- Email → `h***@hust.edu.vn` (giữ domain).
- IP → `/24` mask (keep `203.0.113.*`).
- Log raw body chứa key trên → filter chặn tại encoder.

Log ship qua promtail → Loki (retention 14 ngày). Index theo `service`, `level`, `event`, `user_id`.

### 14.5 Alerts

| Alert | Condition | Severity |
| ----- | --------- | -------- |
| `AnalyticsServiceDown` | up == 0 trong 2 phút | CRITICAL |
| `FlinkJobDown` | any job not running | CRITICAL |
| `FlinkBackpressure` | > 80% for 5 min | WARNING |
| `ClickHouseSlowQuery` | query > 30s | WARNING |
| `IcebergIngestLag` | lag > 15 phút | WARNING |
| `IrtCalibrationFailed` | spark job failed 2 days in row | WARNING |
| `DashboardLatencyHigh` | p99 > 5s | WARNING |
| `OutboxBacklog` | `analytics_outbox_pending_size` > 10k | CRITICAL (ADR-001 RPO violation) |
| `OutboxPublishLag` | `analytics_outbox_publish_lag_seconds` p99 > 5s | CRITICAL |
| `ConsumerDedupRatioHigh` | duplicate > 50% cho 10 phút | WARNING (upstream bug) |
| `DataLakeSizeGrowthAnomaly` | day-over-day growth > 50% | INFO |
| `SLOBurnRate` | Error budget > 2%/hour | CRITICAL |

### 14.6 Data quality monitoring

- **Great Expectations** / Soda Core check hằng ngày:
  - `exam_facts.user_id` không NULL.
  - `percentage_score` in [0, 100].
  - Count match với Postgres source (±1%).
  - No duplicate `attempt_id`.
- Failed check → Slack alert + block downstream jobs (Airflow dependency).

---

## XV. ERROR HANDLING

### 15.1 Format lỗi chuẩn (RFC 7807 Problem Details)

```json
{
  "type": "https://smartquiz.vn/errors/query-timeout",
  "title": "Query vượt quá thời gian cho phép",
  "status": 504,
  "code": "ANALYTICS_QUERY_TIMEOUT",
  "trace_id": "abc123",
  "timestamp": "2026-04-22T10:05:22Z"
}
```

### 15.2 Bảng mã lỗi

| Code | HTTP | Ý nghĩa | Khi nào dùng |
| ---- | ---- | ------- | ------------ |
| `ANALYTICS_MALFORMED_REQUEST` | 400 | JSON parse fail, content-type sai | — |
| `ANALYTICS_VALIDATION_FAILED` | 422 | Semantic validation fail | Body có `errors[]` field-level — §10.0.5 |
| `ANALYTICS_FORBIDDEN` | 403 | Thiếu permission hoặc wrong org | — |
| `AUTH_TOKEN_INVALID` | 401 | JWT sai/thiếu | — |
| `AUTH_TOKEN_EXPIRED` | 401 | JWT hết hạn | — |
| `ANALYTICS_QUERY_TIMEOUT` | 504 | Query vượt 30s | Gợi ý export hoặc giảm scope |
| `ANALYTICS_RESULT_TOO_LARGE` | 413 | > 10M rows | Gợi ý export async |
| `ANALYTICS_DATA_NOT_READY` | 503 | Materialized view chưa refresh | Retry-After: 60 |
| `ANALYTICS_RATE_LIMIT` | 429 | Vượt quota | Header `Retry-After`, `X-RateLimit-*` |
| `IDEMPOTENCY_KEY_REUSE_DIFFERENT_BODY` | 409 | Idempotency-Key reuse body khác | §10.0.6 |
| `EXPERIMENT_NOT_FOUND` | 404 | `experiment_id` không tồn tại | — |
| `EXPERIMENT_ALREADY_RUNNING` | 409 | Tạo mới trong khi đang running | — |
| `EXPERIMENT_ALREADY_CONCLUDED` | 409 | Stop experiment đã concluded | — |
| `EXPORT_JOB_NOT_FOUND` | 404 | `job_id` không tồn tại | — |
| `EXPORT_FORMAT_UNSUPPORTED` | 422 | Format ngoài `[csv, xlsx, parquet]` | — |
| `EXAM_NOT_FOUND` | 404 | `exam_id` không tồn tại | Dashboard endpoint |
| `CLICKHOUSE_UNAVAILABLE` | 503 | CH cluster down | Retry-After: 30 |
| `ANALYTICS_INTERNAL` | 500 | Unhandled | Log chi tiết, trace_id |

---

## XVI. SECURITY & PRIVACY

### 16.1 PII handling

- Export data **mặc định** xoá `email`, `ip_address`; tham số `include_pii=true` chỉ cho `admin` (enforce ở §10.4).
- DIF analysis chỉ lưu `demographic_group` aggregated, **không** per-user.
- **GDPR right-to-be-forgotten**: consume event `auth.user.deleted.v1` → anonymize `user_id` thành hash deterministic (SHA256(salt + user_id)) trong tất cả fact tables. Irreversible.
- Activity log `user_activity_facts` TTL 13-25 tháng (GDPR minimize).

### 16.2 Row-level security ClickHouse

Đã mô tả §9.4. Bổ sung:
- Role `role_instructor` chỉ thấy exam user mình tạo (check qua dict).
- Role `role_platform_admin` bypass filter.
- Session variable set từ JWT claim trước khi execute query.

### 16.3 TLS & network

- TLS 1.3 only API Gateway.
- Service-to-service: mTLS qua Istio.
- HSTS: `max-age=31536000; includeSubDomains; preload`.
- CORS whitelist origin trong ConfigMap (align auth §13.2).

### 16.4 Header an toàn (mặc định)

```
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Referrer-Policy: strict-origin-when-cross-origin
Permissions-Policy: geolocation=(), camera=()
Strict-Transport-Security: max-age=31536000; includeSubDomains; preload
Cache-Control: no-store   (mặc định; dashboard cached override max-age=60)
```

### 16.5 Secrets

| Secret | Nguồn MVP | Nguồn Phase 2 |
| ------ | --------- | ------------- |
| ClickHouse password | K8s Secret | Vault dynamic secret (5 phút TTL) |
| S3 access key | K8s Secret | Vault AWS engine (1h TTL) |
| Kafka SASL | K8s Secret | Vault |
| JWKS URI | ConfigMap | ConfigMap |

### 16.6 Data retention

| Data | Retention | Justification |
| ---- | --------- | ------------- |
| `exam_facts`, `answer_analytics`, `cheat_analytics` | 36 tháng CH, 7 năm S3 | Analytics + compliance |
| `user_activity_facts` | 25 tháng | GDPR minimize |
| `experiment_exposures` | 6 tháng sau experiment end | A/B testing retention |
| `question_irt_params` | vĩnh viễn (ReplacingMergeTree giữ bản mới nhất) | Model lineage |
| `question_dif_flags` | 36 tháng | Audit trail |
| Aggregated daily stats (MV) | vĩnh viễn | Không PII |
| `processed_events` | 7 ngày | Dedup window (= Kafka retention max) |
| `outbox` (published) | 30 ngày | Debug/replay |

---

## XVII. TESTING STRATEGY

### 17.1 Pyramid + coverage gate (JaCoCo, align auth §17.1)

```
          E2E (10%)         ← dashboard flow đầy đủ qua API
       Integration (30%)     ← Testcontainers: CH + Kafka + PG + Apicurio + MinIO
   Unit tests (60%)           ← domain logic, query builder, IRT math
```

| Layer | JaCoCo gate |
| ----- | ----------- |
| `domain/*` (pure logic) | ≥ **80%** |
| `application/*` (UseCase) | ≥ **70%** |
| `infrastructure/*` | best-effort (integration test phủ) |
| Global | ≥ **75%** |

CI fail nếu coverage regress > 2% so với baseline `main`.

### 17.2 Test tools

| Layer | Tool |
| ----- | ---- |
| Unit | JUnit 5, AssertJ, Mockito |
| Integration | **Testcontainers** (ClickHouse 23, Kafka Confluent, Postgres 16, Apicurio, MinIO S3), **WireMock** (JWKS stub) |
| Contract | **Spring Cloud Contract** (producer-side stub cho Question/Notification) + Avro compat check qua Apicurio |
| Statistical | synthetic data generator (known IRT params) → EM converge check |
| Security | OWASP ZAP baseline CI; `@security-engineer` review trước merge |
| Load | k6 (target 500 RPS dashboard, 50 export/h) |

### 17.3 Integration test bắt buộc — outbox + consumer dedup (ADR-001)

```java
@SpringBootTest
@Testcontainers
class IrtCalibrationOutboxIntegrationTest {
    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
    @Container static KafkaContainer kafka = new KafkaContainer(...);
    @Container static ClickHouseContainer ch = new ClickHouseContainer("clickhouse/clickhouse-server:23");
    @Container static GenericContainer<?> apicurio = ...;

    @Test
    void irt_calibration_publishes_event_even_if_relayer_crashes_mid_flight() {
        // given: Spark job populate question_irt_params + outbox row
        irtJobRunner.run(/* fixture: 30 responses cho question Q */);

        // when: simulate relayer crash before publish
        relayerTestHook.pauseBefore(PublishStage.KAFKA_SEND);
        Thread.sleep(500);
        relayerTestHook.resume();

        // then: event eventually trên Kafka exactly once
        await().atMost(10, SECONDS).untilAsserted(() -> {
            List<ConsumerRecord<String, GenericRecord>> records =
                kafkaConsumer.poll("analytics.irt.calibrated.v1");
            assertThat(records).hasSize(1);
            assertThat(records.get(0).key()).isEqualTo("Q");
        });
        assertThat(outboxRepo.pendingCount()).isZero();
    }

    @Test
    void consumer_dedupes_replay() {
        UUID eventId = UUID.randomUUID();
        // given: attempt submitted event published
        publishToKafka("exam.attempt.submitted.v1", eventId, fixturePayload());
        await().until(() -> examFactsRepo.countByAttemptId(fixtureAttemptId()) == 1);

        // when: replay same event 3 times
        for (int i = 0; i < 3; i++) publishToKafka("exam.attempt.submitted.v1", eventId, fixturePayload());

        // then: still 1 row in ClickHouse, dedup metric registered
        assertThat(examFactsRepo.countByAttemptId(fixtureAttemptId())).isEqualTo(1);
        assertThat(meterRegistry.counter("analytics_consumer_dedup_total",
            "result", "duplicate", "topic", "exam.attempt.submitted.v1").count())
            .isGreaterThanOrEqualTo(3);
    }
}
```

### 17.4 Spark job testing

```scala
class IrtCalibrationJobSpec extends AnyFunSuite with SharedSparkContext {
    test("calibration converges on synthetic 2PL data") {
        val synthetic = generateSyntheticResponses(n_students=500, n_questions=10, a=1.5, b=0.0)
        val result = IrtCalibrationJob.calibrate(spark, synthetic)
        assert(abs(result.head.a - 1.5) < 0.1)
        assert(abs(result.head.b - 0.0) < 0.1)
    }
}
```

### 17.5 Flink job testing

`MiniClusterWithClientResource` để chạy job locally với test source/sink.

### 17.6 Security test cases bắt buộc

- [ ] JWT với `alg:none` / `alg:HS256` (confused deputy) → reject.
- [ ] JWT signed bằng public key → reject.
- [ ] Permission bypass: user có `analytics.self` gọi `/analytics/orgs/{id}/*` → 403.
- [ ] Cross-org data leak: instructor org A gọi `/analytics/exams/{id}/overview` với exam org B → 403 (check `@examAccessPolicy.isSameOrg`).
- [ ] Row-level security: manually set ClickHouse session var `app.current_org_id=A`, query `exam_facts` → chỉ org A rows.
- [ ] PII leak export: non-admin user `include_pii=true` → 422 hoặc silently ignored (chọn 422 để rõ).
- [ ] SQL injection qua query param `?subject-code='; DROP TABLE exam_facts;--` → sanitize/parameterize.
- [ ] Rate limit bypass: 1001st request trong giờ → 429.
- [ ] Idempotency replay cùng key body khác → 409.
- [ ] Outbox poisoning: payload Avro-incompatible → relayer mark failure, không block queue.
- [ ] Consumer idempotency: re-deliver cùng event 3 lần → `processed_events` dedup, không double-count.

---

## XVIII. ROADMAP & OPEN QUESTIONS

### 18.1 Gate trước khi scaffold (CLAUDE.md §9)

**Phải xong trước khi bắt tay code UseCase đầu tiên:**

- [x] ClickHouse schema (`database/clickhouse/schema.sql` §1-8) — merged 2026-04-18
- [x] PostgreSQL outbox/processed_events (`database/postgresql/schema.sql` §13) — shared với Auth/Exam
- [x] ADR-001 (SLA + RPO + outbox)
- [x] ADR-002 (Analytics ≠ Cheating, giữ split)
- [ ] **Schema delta bảng mới** (§18.6): `experiment_definitions`, `question_irt_params`, `question_dif_flags`
- [ ] **OpenAPI 3.1 spec** (`api/src/main/resources/static/openapi.yaml`) đủ endpoint §10.1-10.5 MVP
- [ ] Avro schema MVP trong `shared-contracts/avro/analytics/`: `analytics.irt.calibrated.v1`, `analytics.dif.flagged.v1`, `analytics.experiment.concluded.v1`, `analytics.report.ready.v1`
- [ ] Register Avro schema lên Apicurio dev instance (BACKWARD compat)
- [ ] `shared-outbox-starter` Gradle plugin (chung với Auth — ADR-001 §consequences; reuse)
- [ ] ClickHouse migration tool `clickhouse-migrations` tích hợp vào `./gradlew :api:bootRun` profile=dev
- [ ] JWKS endpoint Auth Service **đã live** (dependency — auth-service §18.2)
- [ ] `ops/gen-jwt-keypair.sh` sinh RSA 4096 local dev (dùng chung Auth — CLAUDE.md §9.5)

### 18.2 MVP (Q2/2026)

- [ ] Consumer Kafka path A (Kafka Engine CH) cho `exam.attempt.submitted.v1`, `exam.answer.submitted.v1` + consumer dedup qua `processed_events`
- [ ] Materialized view `mv_exam_daily_stats`, `mv_question_stats` (đã có DDL; verify populate đúng)
- [ ] Student progress + Teacher exam overview dashboard API (`/analytics/me/progress`, `/analytics/exams/{id}/overview`)
- [ ] Simple export CSV exam results (sync small; async job cho > 100k rows)
- [ ] Cache layer Redis + invalidation qua event consumer
- [ ] JWT verification JWKS offline (depend on Auth MVP)
- [ ] Metric + tracing + JSON log ship Loki

### 18.3 Phase 2 (Q3/2026)

- [ ] Flink streaming jobs (realtime leaderboard, session metrics)
- [ ] Spark IRT calibration daily + publish `analytics.irt.calibrated.v1` qua outbox
- [ ] Data lake Iceberg + Iceberg sink từ Kafka
- [ ] Cohort segmentation + publish `analytics.cohort.assigned.v1`
- [ ] Admin BI dashboards + Metabase embed
- [ ] Export Parquet/XLSX async + S3 pre-signed URL + `analytics.report.ready.v1`

### 18.4 Phase 3 (Q4/2026)

- [ ] DIF detection + publish `analytics.dif.flagged.v1`
- [ ] A/B testing framework + `analytics.experiment.concluded.v1`
- [ ] Personalized recommendations
- [ ] Trino ad-hoc query layer
- [ ] Feature store (Feast) cho ML online
- [ ] Natural language query (LLM → SQL với guardrail)

### 18.5 Open questions

1. **Self-hosted ClickHouse vs Cloud (Altinity/ClickHouse Cloud)?** → MVP self-host K8s; scale > 50TB cân nhắc cloud.
2. **Metabase vs build custom BI?** → Embed Metabase cho admin; build UI custom (React 19) cho student/teacher.
3. **Data mesh hay monolithic lake?** → Monolithic trước; 3-5 năm sau mesh theo domain nếu tổ chức lớn.
4. **Natural Language → SQL (LLM)?** → Phase 3 POC; rủi ro hallucinate SQL, cần guardrail + query allowlist.
5. **Học sinh có thấy raw peer data?** → Không, chỉ aggregated percentile (enforce ở §10.1 + row-level policy).
6. **Consolidate fact tables khi schema thay đổi?** → Iceberg schema evolution + dbt-style transforms (Phase 3).
7. **Real-time IRT (online learning) thay vì batch daily?** → Phase 3 khi volume đủ lớn; cần stability analysis (không drift mỗi phút).
8. **Outbox relayer dùng chung pod với API hay tách?** → MVP dùng chung (scheduled thread trong API pod, advisory lock leader). Tách khi API CPU > 60% ổn định — Phase 2.
9. **Cross-org benchmark cho platform_admin?** → Chỉ mở ad-hoc qua Trino; không expose REST endpoint (tránh rò rỉ).

### 18.6 Schema delta v1.2 — chưa merge vào `clickhouse/schema.sql` / `postgresql/schema.sql`

> Đây là **single source** cho mọi schema change thuộc Analytics v1.1 + v1.2. Khi có thêm thay đổi, APPEND vào block này, đừng rải rác ở các section khác của doc.

1. **`experiment_definitions`** (§4.3) — A/B experiment metadata:
   - DDL: `ReplacingMergeTree(concluded_at) ORDER BY (experiment_id)`
   - Cần migration `V{epoch}__analytics_experiment_definitions.sql`.

2. **`question_irt_params`** (§4.3) — IRT calibration history + latest:
   - DDL: `ReplacingMergeTree(calibrated_at) ORDER BY (org_id, question_ref_id)`
   - Consumer Question Service subscribe `analytics.irt.calibrated.v1` để cập nhật MongoDB; CH giữ history.
   - Cần migration `V{epoch}__analytics_question_irt_params.sql`.

3. **`question_dif_flags`** (§4.3) — DIF detection result:
   - DDL: `MergeTree() PARTITION BY toYYYYMM(flagged_at) ORDER BY (org_id, question_ref_id, flagged_at)`
   - Cần migration `V{epoch}__analytics_question_dif_flags.sql`.

4. **Row policies** (§9.4, §16.2):
   - `CREATE ROW POLICY org_filter ON exam_facts USING org_id = currentSettingFromRole('app.current_org_id') TO role_instructor, role_admin;`
   - Tương tự cho `answer_analytics`, `cheat_analytics`, `user_activity_facts`.
   - Cần migration `V{epoch}__analytics_row_policies.sql`.

5. **Dictionaries `dict_users`, `dict_orgs`, `dict_subjects`, `dict_roles`** (§4.4):
   - DDL LIFETIME 300-600s, SOURCE PostgreSQL replica.
   - Cần migration `V{epoch}__analytics_dictionaries.sql` + credential clickhouse_reader trong PG.

6. **`exam_facts` flow — NOT DDL change** (§5.5):
   - Quyết định MVP: Analytics consumer chỉ ghi `exam_facts` khi nhận `exam.attempt.graded.v1` (skip `exam.attempt.submitted.v1` cho fact table).
   - Dashboard "in_progress/submitted count" lấy từ Flink streaming → Redis `rt:exam:{id}:stats` (Job §6.1), không từ `exam_facts`.
   - **KHÔNG** đổi engine `exam_facts` sang `ReplacingMergeTree` ở MVP. Phase 2 revisit nếu cần persist submitted state trong CH.
   - Document-only change; không có Flyway/CH migration.

7. **Row policy `question_dif_flags.reviewed` mutation** (§4.3):
   - Table là `MergeTree()` append-only. Review flow dùng `ALTER TABLE question_dif_flags UPDATE reviewed=1, reviewer_user_id=? WHERE ...` (CH mutation — async, eventual).
   - Hoặc MVP lưu reviewed state ở PG bảng riêng `dif_reviews(question_ref_id, flagged_at, reviewer_user_id, reviewed_at)` — đơn giản hơn, CH giữ nguyên log.
   - Chọn: **PG bảng `dif_reviews`** — append-only, review qua REST API admin. Cần migration Flyway `V{epoch}__dif_reviews.sql`.

Khi prod đã migrate lần đầu, các item delta trên phải biến thành migration file **immutable** — KHÔNG sửa trong block này nữa, thay vào đó append item mới cho v1.2.

---

_Tài liệu thuộc Hệ Thống Thi Trực Tuyến Thông Minh — Analytics Service Design v1.2 — Tháng 4/2026._
