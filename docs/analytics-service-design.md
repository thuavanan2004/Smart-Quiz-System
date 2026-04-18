# ANALYTICS SERVICE - THIẾT KẾ CHI TIẾT

**Hệ Thống Thi Trực Tuyến Thông Minh** | Version 1.0 | Tháng 4/2026

Tài liệu này mở rộng mục "Analytics Service" trong `design.md`, ở mức đủ để triển khai code production.

---

## I. TỔNG QUAN

### 1.1 Vai trò & phạm vi

Analytics Service là **cỗ máy insight** của hệ thống — biến luồng sự kiện thô thành báo cáo có giá trị cho 3 đối tượng: học sinh (self-insight), giáo viên (dashboard kỳ thi), admin (điều hành & BI). Đồng thời đóng vai trò **ML platform** cho các tác vụ calibration (IRT), cohort, DIF.

**Nguyên tắc thiết kế:**
- **Tách biệt hoàn toàn khỏi hot path** — Analytics down, bài thi vẫn chạy
- **Eventual consistency là OK** — dashboard trễ 5 phút chấp nhận được
- **Precomputed > on-the-fly** — materialized view cho mọi dashboard thường dùng
- **Query phân tích dùng ClickHouse**, không bao giờ chạm PostgreSQL source of truth
- **Data lake S3 là truth cuối cùng** — có thể rebuild ClickHouse từ đây nếu mất
- **Batch + stream hybrid** (Kappa-ish) — cùng event stream feed cả 2

| Trách nhiệm | Không thuộc phạm vi |
| ----------- | ------------------- |
| Consume events Kafka → ClickHouse (CDC + streaming) | Phát event (các service domain tự publish) |
| Maintain materialized views cho dashboard | OLTP queries (service khác tự truy vấn PG) |
| Real-time dashboards (Flink) | Real-time bảng xếp hạng đang thi (Redis + Exam Service) |
| Batch reports hàng tuần / tháng (Spark) | Chấm điểm (Exam Service + AI Service) |
| **IRT calibration** (EM algorithm) | Dùng IRT khi chọn câu (Exam Service) |
| Cohort analysis, DIF detection | Phát hiện gian lận (Cheating Service) |
| A/B testing framework | Feature flags (LaunchDarkly) |
| Data export (CSV, Excel, Parquet) cho tổ chức | Import data (Question Service) |
| Data lake S3/Iceberg + query engine (Trino) | Video lưu trữ (Media Service) |
| Student learning curve, recommendation | Personalization khi làm bài (tương lai) |
| Admin BI dashboards | BI tool thương mại (Metabase/Looker — tuỳ chọn) |

### 1.2 Stack công nghệ

| Thành phần | Chọn | Lý do |
| ---------- | ---- | ----- |
| API service | Java 21 + Spring Boot 3 | Thống nhất; serving dashboard queries |
| Stream processor | Apache Flink 1.18 | Exactly-once, complex CEP, stateful |
| Batch processor | Apache Spark 3.5 | Large-scale ML, joins khổng lồ |
| OLAP store | ClickHouse 23.x | 100x PostgreSQL với 100M+ rows |
| Data lake format | Apache Iceberg trên S3 | Schema evolution, time-travel, ACID |
| Query engine over lake | Trino (Starburst OSS) | Federated SQL qua Iceberg + ClickHouse |
| Kafka | Confluent Kafka 3.6 + Schema Registry Avro | Schema evolution |
| CDC | Debezium 2.x | PostgreSQL WAL → Kafka |
| Workflow orchestration | Apache Airflow 2.8 | Batch job scheduling |
| Feature store (tương lai) | Feast | Serving features cho ML online |
| Notebook / exploration | JupyterHub + Trino | Data analyst workflow |
| BI UI | Custom React dashboard + embedded Metabase | Giáo viên dùng; admin deep-dive Metabase |
| ML | scikit-learn, XGBoost, statsmodels (IRT) | Standard |
| Observability | Micrometer + OpenTelemetry | |

### 1.3 Port & giao thức

| Giao thức | Port | Mục đích |
| --------- | ---- | -------- |
| HTTP REST | `3006` | Dashboard API, report download |
| gRPC | `4006` | Internal — Question Service pull IRT calibrated params |
| Actuator | `9006` | Prometheus |
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
| Data retention ClickHouse | 25 tháng (partition theo tháng) |
| Data retention S3 data lake | 7 năm (compliance) |
| IRT calibration cycle | Daily batch (2 AM) |
| Availability | 99.9% (dashboard), 99.5% (ad-hoc) |

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
                 │         ┌─────▼─────┐
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
                  ClickHouse updates
                  (IRT params → Question Service)
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

---

## III. MODULE INTERNAL (Spring Boot API)

### 3.1 Sơ đồ lớp

```
┌──────────────────────────────────────────────────────────────┐
│  API Layer                                                   │
│  ─ StudentAnalyticsController  ─ TeacherDashboardController  │
│  ─ AdminReportController       ─ ExportController            │
│  ─ ExperimentController (A/B)  ─ AnalyticsGrpcService        │
├──────────────────────────────────────────────────────────────┤
│  Application Services                                        │
│  ─ GetStudentProgressUseCase                                 │
│  ─ GetExamDashboardUseCase, GetQuestionStatsUseCase          │
│  ─ BuildCohortReportUseCase, GetDIFReportUseCase             │
│  ─ ExportAnalyticsUseCase, ScheduleReportUseCase             │
│  ─ CreateExperimentUseCase, StopExperimentUseCase            │
├──────────────────────────────────────────────────────────────┤
│  Domain / Analytics Queries                                  │
│  ─ DashboardQueryBuilder                                     │
│  ─ IrtParams (value), CohortDefinition                       │
│  ─ ExperimentDefinition, StatisticalTester                   │
├──────────────────────────────────────────────────────────────┤
│  Infrastructure                                              │
│  ─ ClickHouseTemplate, TrinoJdbcClient                       │
│  ─ KafkaEventPublisher (ít dùng — mostly consume)            │
│  ─ S3Client (download exports)                               │
│  ─ AirflowRestClient (trigger ad-hoc batch)                  │
│  ─ FlinkRestClient (monitor jobs)                            │
└──────────────────────────────────────────────────────────────┘
```

### 3.2 Cấu trúc repo (monorepo)

```
analytics-service/
├── api/                            # Spring Boot API (port 3006)
│   ├── build.gradle.kts
│   └── src/main/java/vn/smartquiz/analytics/
│       ├── web/
│       ├── application/
│       ├── domain/
│       └── infrastructure/
├── flink-jobs/                     # Flink streaming jobs
│   ├── build.gradle.kts
│   └── src/main/java/vn/smartquiz/analytics/flink/
│       ├── ExamSessionMetricsJob.java
│       ├── RealtimeLeaderboardJob.java
│       └── CheatRiskAggregatorJob.java
├── spark-jobs/                     # Spark batch jobs
│   ├── build.gradle.kts
│   └── src/main/scala/vn/smartquiz/analytics/spark/
│       ├── IrtCalibrationJob.scala
│       ├── DifDetectionJob.scala
│       ├── CohortSegmentationJob.scala
│       └── WeeklyReportJob.scala
├── airflow/
│   └── dags/                       # Python DAG definitions
├── clickhouse/
│   └── migrations/                 # Schema migrations (dbmate style)
└── sql/
    ├── materialized_views/
    └── dashboard_queries/
```

---

## IV. DATA INGESTION PIPELINE

### 4.1 Two paths vào ClickHouse

**Path A — Kafka Engine trong ClickHouse (cho events domain nhỏ volume):**

```sql
CREATE TABLE exam_facts_kafka (
    attempt_id UUID, exam_id UUID, user_id UUID, ...
) ENGINE = Kafka
SETTINGS kafka_broker_list = 'kafka-0:9092,kafka-1:9092,kafka-2:9092',
         kafka_topic_list = 'attempt.graded',
         kafka_group_name = 'clickhouse_exam_facts',
         kafka_format = 'AvroConfluent',
         kafka_num_consumers = 3;

CREATE MATERIALIZED VIEW mv_exam_facts_writer TO exam_facts
AS SELECT ... FROM exam_facts_kafka;
```

**Path B — Flink CDC cho PostgreSQL source (nguồn sự thật bất biến):**

```
PostgreSQL WAL
    ↓ Debezium Kafka Connect
Kafka topic: quiz.public.exam_attempts (CDC format)
    ↓ Flink SQL
Transform + deduplicate + exactly-once
    ↓
ClickHouse sink connector
```

Path B dùng khi cần **strong consistency** với PG (e.g., score chính thức).

### 4.2 Schema Registry

Tất cả events qua Kafka đều đăng ký Avro schema ở Confluent Schema Registry:
- Backward compatible only (không break consumer cũ)
- Schema evolution review qua PR

### 4.3 Bảng ClickHouse chính (đã có ở database.md)

- `exam_facts` — fact table lượt thi, partition theo tháng, ORDER BY (org_id, exam_id, started_at)
- `answer_analytics` — fact table từng câu trả lời
- `cheat_analytics` — fact table sự kiện gian lận

> **Mapping với schema thực tế:**
> - 3 bảng trên đã có trong `database/clickhouse/schema.sql`
> - `user_activity_facts`, `question_feedback_facts`, `experiment_exposures` đã có trong `database/clickhouse/schema.sql` (mục 7)
> - `mv_exam_daily_stats`, `mv_question_stats`, `mv_cheat_weekly`, `mv_student_progress`, `mv_user_first_attempt` đã có trong cùng file
> - Redis key `rt:exam:*` đã có trong `database/redis/schema.md` (Nhóm 5)

### 4.4 Bảng bổ sung (thiết kế chi tiết Analytics)

```sql
-- User activity fact (mọi page view, click, session)
CREATE TABLE user_activity_facts (
    event_id UUID,
    user_id UUID,
    session_id UUID,
    org_id UUID,
    activity_type LowCardinality(String),  -- 'page_view', 'exam_start', 'review_answer'
    page_path String,
    referrer String,
    device_type LowCardinality(String),
    country_code FixedString(3),
    ts DateTime64(3),
    date Date MATERIALIZED toDate(ts)
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(date)
ORDER BY (org_id, user_id, ts)
TTL date + INTERVAL 25 MONTH;

-- Question rating / feedback aggregate
CREATE TABLE question_feedback_facts (
    question_ref_id String,
    org_id UUID,
    feedback_type LowCardinality(String),  -- 'rating', 'report'
    value Float32,
    ts DateTime64(3)
) ENGINE = MergeTree()
PARTITION BY toYYYYMM(ts)
ORDER BY (org_id, question_ref_id, ts);

-- Experiment exposures
CREATE TABLE experiment_exposures (
    experiment_id UUID,
    user_id UUID,
    variant LowCardinality(String),
    exposed_at DateTime64(3)
) ENGINE = MergeTree()
ORDER BY (experiment_id, user_id);
```

### 4.5 Materialized views cho dashboard

```sql
-- Tổng quan bài thi theo ngày (đã có ở database.md)
mv_exam_daily_stats

-- Student progress (điểm trung bình theo subject qua thời gian)
CREATE MATERIALIZED VIEW mv_student_progress
ENGINE = AggregatingMergeTree()
PARTITION BY toYYYYMM(date)
ORDER BY (user_id, subject_code, date)
AS SELECT
    user_id, subject_code, toDate(started_at) AS date,
    count() AS attempts,
    avg(percentage_score) AS avg_score,
    max(percentage_score) AS best_score,
    sum(time_spent_sec) AS time_spent_sec
FROM exam_facts GROUP BY user_id, subject_code, date;

-- Question stats chi tiết (đã có)
mv_question_stats

-- Cohort: học sinh cùng tháng đăng ký → tracking progress
CREATE MATERIALIZED VIEW mv_cohort_progress
ENGINE = AggregatingMergeTree()
ORDER BY (cohort_month, weeks_since_join, org_id)
AS SELECT
    toStartOfMonth(u.created_at) AS cohort_month,
    intDivOrZero(dateDiff('day', u.created_at, ef.started_at), 7) AS weeks_since_join,
    u.org_id,
    uniq(ef.user_id) AS active_users,
    avg(ef.percentage_score) AS avg_score
FROM exam_facts ef
JOIN users u ON ef.user_id = u.id  -- u từ PG dictionary
GROUP BY cohort_month, weeks_since_join, org_id;
```

### 4.6 Dictionaries (ClickHouse)

Để tránh join với PostgreSQL mỗi lần, ClickHouse load dictionary từ PG:

```sql
CREATE DICTIONARY dict_users (
    id UUID,
    full_name String,
    email String,
    org_id UUID
) PRIMARY KEY id
SOURCE(POSTGRESQL(
    host 'pg-replica' port 5432 user 'clickhouse_reader'
    password '...' db 'smartquiz' table 'users'))
LIFETIME(MIN 300 MAX 600)
LAYOUT(COMPLEX_KEY_HASHED());
```

Refresh mỗi 5-10 phút. Query:
```sql
SELECT dictGet('dict_users', 'full_name', user_id) AS name, ...
```

---

## V. FLINK STREAMING JOBS

### 5.1 Job 1 — Real-time exam session metrics

**Input:** Kafka topics `exam.attempt.started`, `exam.answer.submitted`, `exam.attempt.submitted`.

**Output:** Redis `rt:exam:{exam_id}:stats` updated mỗi 1 giây.

```java
DataStream<AttemptEvent> events = env.fromSource(kafkaSource, WatermarkStrategy.noWatermarks(), "events");

events
    .keyBy(AttemptEvent::examId)
    .window(SlidingEventTimeWindows.of(Time.minutes(5), Time.seconds(1)))
    .aggregate(new ExamStatsAggregator())    // {active, avg_score, submitted, flagged}
    .addSink(new RedisSink<>("rt:exam:{}:stats"));
```

Exam Service đọc Redis cho "live dashboard".

### 5.2 Job 2 — Real-time leaderboard (elastic)

Cho giáo viên xem live trong kỳ thi lớn. Redis Sorted Set `leaderboard:{exam_id}`.

```java
events.filter(e -> e.type() == "attempt.submitted")
     .keyBy(AttemptEvent::examId)
     .process(new LeaderboardUpdater());
```

### 5.3 Job 3 — Cheat correlation heat-map

Consume `cheat.event.detected`, xuất ra heat-map (user × event_type × hour) → ClickHouse insert.

### 5.4 Checkpoint & state

- Enable Flink checkpointing mỗi 30s, state backend RocksDB on S3
- Exactly-once semantics với Kafka source + idempotent sink
- Savepoints trước mỗi deploy để rollback dễ

### 5.5 Monitoring Flink

- Flink metrics → Prometheus via `metrics.reporter.prom`
- Alert: job down, checkpoint duration > 10s, backpressure > 80%

---

## VI. SPARK BATCH JOBS

### 6.1 Job 1 — IRT Calibration (daily, 2 AM)

**Mục tiêu:** Cập nhật tham số `a, b, c` của mỗi câu hỏi dựa trên responses mới.

```scala
val spark = SparkSession.builder.getOrCreate()

// Đọc data lake
val answers = spark.read.format("iceberg").load("answers_iceberg")
                   .filter($"answered_at" >= yesterday)

val candidates = answers.groupBy("question_ref_id")
                        .agg(count("*").as("n"))
                        .filter($"n" >= 30)      // chỉ calibrate khi đủ 30 responses

// Cho mỗi câu: EM algorithm 3PL
val calibrated = candidates.mapPartitions { iter =>
    iter.map { row =>
        val qid = row.getAs[String]("question_ref_id")
        val responses = fetchResponsesWithTheta(qid)
        val (a, b, c) = emThreePL(responses, maxIter = 50, tol = 0.001)
        (qid, a, b, c, responses.size)
    }
}

// Write back — ClickHouse + Question Service
calibrated.write.format("clickhouse").option("table", "question_irt_params").save()

// Gửi qua Kafka để Question Service cập nhật MongoDB
calibrated.foreachPartition { iter =>
    val producer = createKafkaProducer()
    iter.foreach { case (qid, a, b, c, n) =>
        producer.send(new ProducerRecord("analytics.irt.calibrated",
            qid, Json.toJson(IrtUpdate(qid, a, b, c, n)).toString()))
    }
}
```

**EM algorithm (statsmodels-like):**
- E-step: với θ hiện tại của học sinh, estimate expected correct probability
- M-step: maximize likelihood → update `a, b, c`
- Convergence: |Δθ| < 0.001 hoặc 50 iter

### 6.2 Job 2 — DIF Detection (weekly)

Mantel-Haenszel chi-square test: một câu hỏi có bias theo group (giới tính, tuổi, org) không?

```scala
// Group responses by question, matched on total score
val difResults = answers.join(users, "user_id")
    .groupBy("question_ref_id", "demographic_group")
    .agg(/* matched pairs */)
    .withColumn("mh_stat", udf_mantel_haenszel(...))
    .filter($"mh_stat" > critical_value)

// Flag vào question_dif_flags
difResults.write.format("iceberg").save("question_dif_flags")
```

Giáo viên / admin được notify nếu câu hỏi có DIF.

### 6.3 Job 3 — Cohort segmentation (weekly)

K-means trên vector features: `{avg_score, time_per_q, attempts_per_week, correct_rate_by_bloom}`.

Output: `user_cohorts` table với `cohort_id` per user. Dùng cho personalized content recommendation (Phase 3).

### 6.4 Job 4 — Weekly/Monthly reports (aggregate)

Tạo Parquet report cho admin:
- Top 10 bài thi phổ biến
- Top 10 câu hỏi khó nhất
- Cheat rate trend
- User retention cohort table

Trigger bởi Airflow DAG, output S3 `reports/{org_id}/{year}/{week}.pdf` qua PDF renderer.

### 6.5 Spark cluster

- Kubernetes-native (spark-on-k8s) — driver + executor as pods
- On-demand: driver scheduler trigger executor khi có job
- Resources: default 8 executor × 4 core × 8GB; scale theo data size

---

## VII. DATA LAKE (Iceberg)

### 7.1 Table structure

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

### 7.2 Ingestion to lake

Kafka → S3 qua:
- Option A: Flink sink `FileSystem` với Iceberg format
- Option B: Kafka Connect S3 Sink → Spark compaction job

Commit interval: 5 phút (small files → compact hourly).

### 7.3 Schema evolution

Iceberg hỗ trợ native:
- Add column (null default)
- Rename column (keeps data)
- Drop column (data retained, marked removed)

Flow: schema PR → Iceberg `ALTER TABLE` → consumers auto-adapt.

### 7.4 Time-travel queries

```sql
-- Qua Trino
SELECT * FROM iceberg.curated.exam_facts
FOR TIMESTAMP AS OF TIMESTAMP '2026-04-15 00:00:00'
```

Dùng cho:
- Reproduce report historical đúng với state lúc đó
- Debug "số liệu hôm qua khác hôm nay" (dữ liệu backfill)

---

## VIII. DASHBOARD API

> **Ghi chú về Role:** Enforcement thật dùng permission code (`analytics.self`, `analytics.exam`, `analytics.org`, `analytics.export`, `analytics.experiment`). Xem `auth-service-design.md` mục 3.4.


### 8.1 Student-facing

| Method | Path | Output |
| ------ | ---- | ------ |
| GET | `/analytics/me/progress?subject=&from=&to=` | Learning curve |
| GET | `/analytics/me/strengths-weaknesses` | Phân tích bloom level + topic |
| GET | `/analytics/me/attempts/history` | Paginated past attempts |
| GET | `/analytics/me/compared-to-peers?exam_id=` | Percentile + distribution |
| GET | `/analytics/me/recommendations` | Gợi ý câu hỏi nên luyện (dùng IRT) |

### 8.2 Teacher / instructor

| Method | Path | Output |
| ------ | ---- | ------ |
| GET | `/analytics/exams/{id}/overview` | Attempts, avg, pass rate, flagged count |
| GET | `/analytics/exams/{id}/distribution` | Histogram scores |
| GET | `/analytics/exams/{id}/question-stats` | Per-question correct rate, avg time |
| GET | `/analytics/exams/{id}/difficulty-vs-time` | Scatter plot |
| GET | `/analytics/exams/{id}/cheat-summary` | Layer breakdown, suspicious attempts |
| GET | `/analytics/questions/{id}/performance` | Time series stats |
| GET | `/analytics/subjects/{code}/class-performance?class_id=` | |

### 8.3 Admin / org

| Method | Path |
| ------ | ---- |
| GET | `/analytics/orgs/{id}/usage?month=` |
| GET | `/analytics/orgs/{id}/users/active-monthly` |
| GET | `/analytics/orgs/{id}/cheat-rate-trend` |
| GET | `/analytics/orgs/{id}/ai-usage-summary` |
| GET | `/admin/reports/available` — list pre-generated reports |
| POST | `/admin/reports/generate` body `{type, params}` → async |
| GET | `/admin/reports/{id}/download` |

### 8.4 Export

| Method | Path | Output |
| ------ | ---- | ------ |
| POST | `/analytics/export/exam-results` body `{exam_id, format}` | CSV/XLSX/Parquet async |
| POST | `/analytics/export/question-bank-stats` | |
| GET | `/analytics/export/{job_id}` | Status |

### 8.5 Experiments (A/B)

| Method | Path |
| ------ | ---- |
| POST | `/experiments` — create (platform_admin) |
| GET | `/experiments/{id}/results` — statistical significance test |
| POST | `/experiments/{id}/stop` |
| GET | `/experiments/{id}/exposures` |

### 8.6 gRPC (internal)

```proto
service AnalyticsService {
    rpc GetQuestionIrt(GetQuestionIrtRequest) returns (IrtParams);
    rpc BatchGetQuestionIrt(BatchIrtRequest) returns (BatchIrtResponse);
    rpc RecordExposure(RecordExposureRequest) returns (Empty);  // A/B exposure logging
    rpc GetUserSegment(UserSegmentRequest) returns (UserSegment);
}
```

### 8.7 Query patterns & caching

API layer cache (Redis):

| Endpoint pattern | TTL |
| ---------------- | --- |
| `/exams/{id}/overview` | 60s |
| `/exams/{id}/question-stats` | 5 phút |
| `/me/progress` | 5 phút |
| `/me/recommendations` | 30 phút (computed từ IRT) |
| `/orgs/{id}/monthly-usage` | 1 giờ |

Cache invalidation: khi event `attempt.graded` đến cho exam → invalidate `exams/{id}/*`.

---

## IX. IRT CALIBRATION (deep dive)

### 9.1 Mô hình 3PL

```
P(correct | θ, a, b, c) = c + (1 - c) * 1/(1 + exp(-a*(θ - b)))
```

- `a` (discrimination): mức độ câu hỏi phân biệt giỏi–dở; kỳ vọng 0.5-2.0, outlier > 3 rare
- `b` (difficulty): vị trí độ khó; -3 → 3 thang
- `c` (guessing): xác suất đoán; 0 với essay, ~0.25 với MC 4 option

### 9.2 Estimation — Marginal MLE với EM

1. Khởi tạo `a=1, b=0, c=0.25` cho câu mới
2. E-step: với θ estimate của mọi students đã trả lời câu đó, compute expected responses
3. M-step: maximize log-likelihood với Newton-Raphson
4. Iterate đến converge

**Constraint:**
- `a > 0` (nếu < 0 → câu hỏi reverse-scored bug)
- `-4 ≤ b ≤ 4`
- `0 ≤ c ≤ 0.5`

### 9.3 Cold start

Câu hỏi mới chưa đủ 30 responses:
- Dùng giá trị `difficulty_assigned` (1-5) từ giáo viên → map sang thang `b`
- `a = 1.0` default
- `c = 0.25` cho MC, `0` cho khác
- Flag `calibrated=false` trên câu hỏi

### 9.4 Publication criteria

Chỉ publish IRT params mới về Question Service nếu:
- Responses ≥ 30
- Convergence đạt
- Không có outlier (|a| < 5, |b| < 4)
- Point-biserial correlation > 0.15 (câu có discriminate được)

Câu không đạt → flag `low_quality` để giáo viên review.

### 9.5 Bayesian shrinkage (prior)

Tránh overfit câu hỏi mới với ít data:
```
a_posterior = (n/n+k) * a_mle + (k/n+k) * a_prior
```
Với `k = 30` — prior = global mean.

---

## X. EVENTS (KAFKA)

### 10.1 Consumed (chủ yếu)

| Topic | Handler |
| ----- | ------- |
| `auth.login.success` | User activity stream |
| `auth.user.registered` | Cohort assignment |
| `exam.attempt.started` | Flink stats + Iceberg sink |
| `exam.answer.submitted` | Question stats aggregator |
| `exam.attempt.submitted` | Fact table insert |
| `attempt.graded` | Cache invalidation (dashboard) |
| `cheat.event.detected` | Flink heat-map + Iceberg |
| `question.created` / `updated` | Question dim update |
| `ai.cost.recorded` | Cost analytics |
| `*` (catch-all) | Data lake sink |

### 10.2 Produced

| Topic | Payload | Consumer |
| ----- | ------- | -------- |
| `analytics.irt.calibrated` | `{question_id, a, b, c, n, calibrated_at}` | Question Service |
| `analytics.dif.flagged` | `{question_id, demographic, mh_stat}` | Question Service |
| `analytics.cohort.assigned` | `{user_id, cohort_id}` | Notification (recommendation) |
| `analytics.report.ready` | `{report_id, s3_url, org_id}` | Notification |
| `analytics.experiment.concluded` | `{experiment_id, winner_variant, p_value}` | Feature flag system |

---

## XI. A/B TESTING FRAMEWORK

### 11.1 Định nghĩa experiment

```json
{
  "id": "exp_question_order_2026_04",
  "name": "Question order: difficulty_asc vs random",
  "status": "running",
  "variants": [
    { "key": "control", "traffic": 0.5, "config": { "order_strategy": "as_defined" } },
    { "key": "treatment_asc", "traffic": 0.25, "config": { "order_strategy": "easy_first" } },
    { "key": "treatment_rand", "traffic": 0.25, "config": { "order_strategy": "random" } }
  ],
  "eligibility": { "role_code": ["student"], "org_plan": ["pro","enterprise"] },   // role_code động — reference code, không hardcode enum
  "metrics": {
    "primary":   ["percentage_score"],
    "secondary": ["time_spent_sec", "abandon_rate"]
  },
  "min_samples_per_variant": 500,
  "statistical_test": "welch_t_test",
  "significance_level": 0.05,
  "started_at": "...",
  "ends_at": "..."
}
```

### 11.2 Assignment

- User → variant bằng `hash(user_id + experiment_id) mod 100` (sticky, deterministic)
- Record exposure qua `/analytics/experiments/{id}/expose` (hoặc via gRPC từ Exam Service)

### 11.3 Significance testing

Sau khi đủ sample:
- Primary metric: Welch's t-test (unequal variance)
- Correction cho multiple metrics: Bonferroni
- Output: p-value, effect size (Cohen's d), confidence interval

Auto-stop khi:
- Sample đạt & p < 0.01 (strong signal)
- Guardrail metric degrade > 5% (e.g., time_spent tăng ≥ 5%)

### 11.4 Reporting

```
GET /experiments/exp_01/results
{
  "variants": [
    { "key": "control",       "n": 512, "primary_mean": 72.3, "primary_ci": [70.1, 74.5] },
    { "key": "treatment_asc", "n": 496, "primary_mean": 75.8, "primary_ci": [73.5, 78.1], "lift": "+4.8%", "p_value": 0.002 }
  ],
  "recommendation": "treatment_asc shows significant positive effect on score",
  "guardrails": { "abandon_rate": { "treatment": 0.02, "control": 0.021, "delta": "+0.1% (OK)" } }
}
```

---

## XII. COHORT & LEARNING ANALYTICS

### 12.1 Student learning curve

Công thức simple: rolling 10-attempt moving average of `percentage_score` by subject.

```sql
SELECT user_id, subject_code, started_at,
       avg(percentage_score) OVER (
         PARTITION BY user_id, subject_code
         ORDER BY started_at
         ROWS BETWEEN 9 PRECEDING AND CURRENT ROW
       ) AS rolling_avg
FROM exam_facts
WHERE user_id = :uid AND started_at > now() - INTERVAL 90 DAY
```

### 12.2 Bloom level mastery

Phân tích correct rate theo `bloom_level` để chỉ ra student yếu ở mức nào (knowledge vs analysis vs synthesis).

Dashboard hiển thị radar chart 6 dimensions.

### 12.3 Recommendation (simple)

Spark job nightly:
- Với mỗi student, estimate θ
- Query Question Service tìm câu hỏi có `b ≈ θ ± 0.3, calibrated=true, chưa từng làm` → suggest 10 câu

Publish `analytics.recommendation.ready` → student nhận thông báo "Hôm nay có 10 câu phù hợp với bạn".

### 12.4 Class insights (teacher)

| Insight | Compute |
| ------- | ------- |
| "3 câu hỏi có correct_rate < 30%" | `mv_question_stats` WHERE correct_rate < 0.3 |
| "Students cần ôn topic X" | Avg score by topic < 60% cho ≥ 50% class |
| "Top 10% students" | `leaderboard` query |
| "Time outliers" | Student time > 3σ mean |

---

## XIII. QUERY PERFORMANCE

### 13.1 ClickHouse best practices

- **Sắp xếp**: `ORDER BY (org_id, exam_id, ts)` — nhất quán với WHERE pattern
- **Partition**: theo tháng → prune nhanh
- **LowCardinality(String)** cho enum-like fields (subject_code, device_type)
- **PROJECTION** cho query pattern khác:
  ```sql
  ALTER TABLE exam_facts ADD PROJECTION by_user (
      SELECT * ORDER BY user_id, started_at
  );
  ```

### 13.2 Query governance

- Max query time 30s; kill tự động (`max_execution_time`)
- Max memory 10GB per query
- Quota: 1000 queries/hour/user
- Log slow queries > 5s → review để optimize hoặc thêm MV

### 13.3 Caching layers

```
Client → API service (Redis cache ~1 min)
         ↓
         ClickHouse query cache (internal, 1 hour)
         ↓
         Materialized view (precomputed, 5 min refresh)
         ↓
         Base tables
```

---

## XIV. SECURITY & PRIVACY

### 14.1 RBAC

| Query type | student | instructor | admin | platform_admin |
| ---------- | ------- | ---------- | ----- | -------------- |
| Own progress | ✔ | — | — | ✔ |
| Exam stats mình tạo | — | ✔ | ✔ | ✔ |
| Org aggregate | — | — | ✔ | ✔ |
| Cross-org analytics | — | — | — | ✔ |
| Export raw data | — | ✔ (own exam) | ✔ | ✔ |
| A/B experiment config | — | — | — | ✔ |

### 14.2 PII handling

- Export data xoá `email`, `ip_address` cho dataset public
- DIF analysis chỉ lưu `demographic_group` aggregated, không per-user
- GDPR right-to-be-forgotten: nhận event `user.deleted` → anonymize `user_id` thành hash trong tất cả fact tables (irreversible)

### 14.3 Row-level security ClickHouse

```sql
CREATE ROW POLICY org_filter ON exam_facts
    USING org_id = currentSettingFromRole('app.current_org_id')
    TO instructor_role;
```

Set session variable từ JWT claim ở API layer.

### 14.4 Data retention

| Data | Retention | Justification |
| ---- | --------- | ------------- |
| `exam_facts`, `answer_analytics` | 25 tháng ClickHouse, 7 năm S3 | Analytics tương đối gần; compliance lưu lâu |
| `user_activity_facts` | 13 tháng | GDPR minimize |
| `experiment_exposures` | 6 tháng sau experiment end | |
| Aggregated daily stats | vĩnh viễn | Không PII |

---

## XV. OBSERVABILITY

### 15.1 Metrics

| Metric | Type | Label |
| ------ | ---- | ----- |
| `analytics_ingest_events_total` | counter | `topic` |
| `analytics_ingest_lag_seconds` | gauge | `topic` |
| `analytics_dashboard_query_duration_seconds` | histogram | `endpoint` |
| `analytics_ch_query_duration_seconds` | histogram | `query_name` |
| `analytics_flink_job_up` | gauge | `job_name` |
| `analytics_flink_checkpoint_duration_seconds` | histogram | `job_name` |
| `analytics_flink_backpressure_ratio` | gauge | `job_name` |
| `analytics_spark_job_duration_seconds` | histogram | `job_name` |
| `analytics_spark_job_status` | counter | `job_name`, `status=success\|failed` |
| `analytics_mv_refresh_lag_seconds` | gauge | `view_name` |
| `analytics_irt_calibration_questions_updated` | counter | — |
| `analytics_dif_flags_detected_total` | counter | `demographic` |

### 15.2 SLO

| SLI | Target |
| --- | ------ |
| Dashboard API p95 | < 500ms |
| ClickHouse query p99 | < 2s |
| Flink streaming lag | < 10s p95 |
| Spark daily job success rate | > 99% |
| Data freshness (event → ClickHouse) | < 5 phút p95 |

### 15.3 Alerts

| Alert | Condition | Severity |
| ----- | --------- | -------- |
| `FlinkJobDown` | any job not running | CRITICAL |
| `FlinkBackpressure` | > 80% for 5 min | WARNING |
| `ClickHouseSlowQuery` | query > 30s | WARNING |
| `IcebergIngestLag` | lag > 15 phút | WARNING |
| `IrtCalibrationFailed` | spark job failed 2 days in row | WARNING |
| `DashboardLatencyHigh` | p99 > 5s | WARNING |
| `DataLakeSizeGrowthAnomaly` | day-over-day growth > 50% | INFO |

### 15.4 Data quality monitoring

- **Great Expectations** / Soda Core check hằng ngày:
  - `exam_facts.user_id` không NULL
  - `percentage_score` in [0, 100]
  - Count match với Postgres source (±1%)
  - No duplicate `attempt_id`
- Failed check → Slack alert + block downstream jobs

---

## XVI. PERFORMANCE & SCALE

### 16.1 Scale bottlenecks

| Bottleneck | Scale strategy |
| ---------- | -------------- |
| Kafka ingest → ClickHouse | Tăng `kafka_num_consumers`, thêm CH shard |
| Flink job backpressure | Thêm parallelism, bigger TaskManager |
| Spark job long runtime | Thêm executor, tune partitioning |
| API query concurrent | HPA trên API service |
| ClickHouse disk | Add shard (distributed table) |

### 16.2 Capacity planning

Với 100.000 MAU:
- Events: 500k/phút peak → 720M/ngày → 26GB/ngày (30 bytes/row compressed)
- 25 tháng retention: ~20 TB ClickHouse (chưa compressed), ~6 TB sau compress 10:1

Cluster target:
- ClickHouse: 3 node × 16 vCPU × 128GB RAM × 8TB SSD (replication factor 2)
- Flink: 3 TaskManager × 8 core × 16GB
- Spark: dynamic on-demand, peak 10 executor × 8 core × 32GB
- Trino: 3 worker nodes on-demand

### 16.3 Cost estimation

| Component | Est. monthly |
| --------- | ------------ |
| ClickHouse (self-hosted on K8s) | ~$1500 (compute + storage) |
| Flink cluster | ~$800 |
| Spark on-demand | ~$500 (job hours) |
| S3 data lake storage | ~$200 (20TB × $0.023/GB × compression) |
| Trino on-demand | ~$200 |
| **Total infra** | **~$3200/month** @ 100k MAU |

---

## XVII. ERROR HANDLING

| Code | HTTP | Ý nghĩa |
| ---- | ---- | ------- |
| `ANALYTICS_QUERY_TIMEOUT` | 504 | Query vượt 30s |
| `ANALYTICS_RESULT_TOO_LARGE` | 413 | > 10M rows (dùng export thay vì API) |
| `ANALYTICS_DATA_NOT_READY` | 503 | Material view chưa refresh |
| `EXPERIMENT_NOT_FOUND` | 404 | |
| `EXPERIMENT_ALREADY_RUNNING` | 409 | |
| `EXPORT_JOB_NOT_FOUND` | 404 | |

---

## XVIII. TESTING

### 18.1 Unit

- Test query builder generates đúng SQL
- Test statistical test functions (t-test, chi-square) với known fixtures
- Test IRT EM convergence với synthetic data (generate responses theo model → fit lại → so sánh)

### 18.2 Integration

- Testcontainers: ClickHouse + Kafka + MinIO (S3) + PostgreSQL
- Flow: publish events → verify ClickHouse rows appear → verify MV aggregates correct

### 18.3 Data quality gates

- Trước deploy: run `great_expectations` suite lên staging data, fail deploy nếu regression

### 18.4 Spark job testing

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

### 18.5 Flink job testing

`MiniClusterWithClientResource` để chạy job locally với test source/sink.

---

## XIX. DEPLOYMENT

### 19.1 API service (Spring Boot)

Standard K8s deployment, HPA on CPU + query QPS.

### 19.2 Flink on K8s

```yaml
apiVersion: flink.apache.org/v1beta1
kind: FlinkDeployment
metadata: { name: exam-session-metrics-job }
spec:
  image: flink:1.18
  flinkVersion: v1_18
  flinkConfiguration:
    taskmanager.numberOfTaskSlots: "4"
    state.backend: "rocksdb"
    state.checkpoints.dir: "s3://flink-checkpoints/exam-session/"
    execution.checkpointing.interval: "30000"
  jobManager: { resource: { memory: "2048m", cpu: 1 } }
  taskManager: { replicas: 3, resource: { memory: "8192m", cpu: 4 } }
  job:
    jarURI: "s3://flink-jars/exam-session-metrics-1.0.0.jar"
    parallelism: 12
    upgradeMode: savepoint
```

### 19.3 Spark on K8s

Spark-operator submit job qua Airflow DAG:

```python
spark_op = SparkKubernetesOperator(
    task_id="irt_calibration",
    namespace="analytics",
    application_file="spark-irt-calibration.yaml",
    do_xcom_push=True
)
```

### 19.4 Airflow DAGs

```python
with DAG("daily_analytics", schedule="0 2 * * *", catchup=False) as dag:
    quality_check = PythonOperator(task_id="quality_check", ...)
    irt_job        = SparkKubernetesOperator(...)
    dif_job        = SparkKubernetesOperator(...)
    cohort_job     = SparkKubernetesOperator(...)
    weekly_report  = SparkKubernetesOperator(...)  # only on Monday

    quality_check >> [irt_job, dif_job, cohort_job] >> weekly_report
```

### 19.5 Data lake housekeeping

- Compaction job hourly: gộp small files → 128MB target
- Snapshot expiration daily: giữ 30 ngày snapshot, clean old metadata
- Orphan file cleanup weekly

---

## XX. ROADMAP

### 20.1 MVP (Q2/2026)

- [ ] Kafka → ClickHouse basic ingestion (path A)
- [ ] `mv_exam_daily_stats` + `mv_question_stats`
- [ ] Student progress + Teacher exam overview dashboard API
- [ ] Simple export (CSV exam results)
- [ ] Cache layer Redis

### 20.2 Phase 2 (Q3/2026)

- [ ] Flink streaming jobs (realtime leaderboard, session metrics)
- [ ] Spark IRT calibration daily
- [ ] Data lake Iceberg
- [ ] Cohort segmentation
- [ ] Admin BI dashboards

### 20.3 Phase 3 (Q4/2026)

- [ ] DIF detection
- [ ] A/B testing framework
- [ ] Personalized recommendations
- [ ] Trino ad-hoc query layer
- [ ] Feature store (Feast) cho ML online
- [ ] Natural language query (GPT convert tiếng Việt → SQL)

### 20.4 Open questions

1. **Self-hosted ClickHouse vs Cloud (Altinity/ClickHouse Cloud)?** → MVP self-host; scale > 50TB cân nhắc cloud
2. **Metabase vs build custom BI?** → Embed Metabase cho admin; build UI custom cho student/teacher
3. **Data mesh hay monolithic lake?** → Monolithic trước; 3-5 năm sau mesh theo domain nếu tổ chức lớn
4. **Natural Language → SQL (LLM)?** → Phase 3 POC; rủi ro hallucinate SQL, cần guardrail
5. **Học sinh có thể thấy bao nhiêu info về bản thân?** → Không thấy raw data peer; chỉ aggregated percentile
6. **Cách consolidate fact tables khi schema thay đổi?** → Iceberg schema evolution + dbt-style transforms
7. **Real-time ML (online learning cho IRT) thay vì batch daily?** → Phase 3 khi volume đủ lớn

---

_Tài liệu thuộc Hệ Thống Thi Trực Tuyến Thông Minh — Analytics Service Design v1.0 — Tháng 4/2026._
