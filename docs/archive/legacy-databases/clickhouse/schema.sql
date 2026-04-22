-- =============================================================================
-- SMART QUIZ SYSTEM - ClickHouse 23.x SCHEMA
-- =============================================================================
-- File tổng hợp tất cả bảng + materialized view cho OLAP analytics
-- Chạy:
--   clickhouse-client --multiquery < schema.sql
-- Hoặc trên container:
--   docker exec -i clickhouse clickhouse-client --multiquery < schema.sql
-- =============================================================================

-- Tạo database
CREATE DATABASE IF NOT EXISTS smartquiz_analytics;
USE smartquiz_analytics;

-- =============================================================================
-- 1. BẢNG: exam_facts (sự kiện thi chính)
-- =============================================================================
CREATE TABLE IF NOT EXISTS exam_facts
(
    -- Khóa & định danh
    attempt_id      UUID,
    exam_id         UUID,
    user_id         UUID,
    org_id          UUID,
    subject_code    LowCardinality(String),

    -- Thời gian (phân vùng theo tháng)
    started_at      DateTime64(3, 'Asia/Ho_Chi_Minh'),
    submitted_at    DateTime64(3, 'Asia/Ho_Chi_Minh'),
    date            Date MATERIALIZED toDate(started_at),
    month           Date MATERIALIZED toStartOfMonth(started_at),

    -- Kết quả
    raw_score           Float32,
    max_score           Float32,
    percentage_score    Float32,
    passed              UInt8,
    attempt_number      UInt8,
    time_spent_sec      UInt32,

    -- Chống gian lận
    risk_score          UInt16,
    flagged             UInt8,
    cheat_events_count  UInt16,

    -- Thiết bị
    country_code        LowCardinality(FixedString(3)),
    device_type         LowCardinality(String),

    -- Adaptive IRT
    final_theta         Float32,
    final_se            Float32
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(started_at)
ORDER BY (org_id, exam_id, started_at)
TTL toDate(started_at) + INTERVAL 36 MONTH      -- giữ 3 năm, đủ cho phân tích năm học và so sánh cohort
SETTINGS index_granularity = 8192;

-- Skip index: tăng tốc query lọc theo risk / điểm mà không cần đụng tới cả partition.
ALTER TABLE exam_facts ADD INDEX IF NOT EXISTS idx_exam_facts_risk  risk_score       TYPE minmax GRANULARITY 4;
ALTER TABLE exam_facts ADD INDEX IF NOT EXISTS idx_exam_facts_score percentage_score TYPE minmax GRANULARITY 4;

-- Projection cho dashboard "xem tất cả attempt của 1 exam" — tránh scan lại bảng lớn.
ALTER TABLE exam_facts ADD PROJECTION IF NOT EXISTS proj_by_exam (
    SELECT *
    ORDER BY (exam_id, started_at)
);

-- =============================================================================
-- 2. BẢNG: answer_analytics (chi tiết từng câu trả lời)
-- =============================================================================
CREATE TABLE IF NOT EXISTS answer_analytics
(
    attempt_id          UUID,
    question_ref_id     String,
    exam_id             UUID,
    org_id              UUID,
    subject_code        LowCardinality(String),
    date                Date,

    is_correct          UInt8,
    points_earned       Float32,
    max_points          Float32,
    time_spent_sec      UInt16,
    answer_position     UInt8,
    was_skipped         UInt8,
    was_changed         UInt8,

    -- IRT tại thời điểm trả lời
    theta_at_answer     Float32
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(date)
ORDER BY (org_id, question_ref_id, date)
TTL date + INTERVAL 36 MONTH
SETTINGS index_granularity = 8192;

-- =============================================================================
-- 3. BẢNG: cheat_analytics (xu hướng gian lận)
-- =============================================================================
CREATE TABLE IF NOT EXISTS cheat_analytics
(
    attempt_id      UUID,
    user_id         UUID,
    exam_id         UUID,
    org_id          UUID,
    event_type      LowCardinality(String),
    event_layer     UInt8,
    severity        LowCardinality(String),
    risk_delta      Int16,
    occurred_at     DateTime64(3),
    date            Date MATERIALIZED toDate(occurred_at)
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(date)
ORDER BY (org_id, exam_id, occurred_at)
TTL date + INTERVAL 36 MONTH
SETTINGS index_granularity = 8192;

-- =============================================================================
-- 4. MATERIALIZED VIEW: Thống kê bài thi theo ngày
-- =============================================================================
-- AggregatingMergeTree YÊU CẦU các hàm aggregate ở dạng *State combinator.
-- Khi query, reader phải dùng *Merge (ví dụ: avgMerge(avg_score_state)).
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_exam_daily_stats
ENGINE = AggregatingMergeTree()
PARTITION BY toYYYYMM(date)
ORDER BY (org_id, exam_id, date)
POPULATE
AS SELECT
    org_id,
    exam_id,
    toDate(started_at) AS date,
    countState()                                    AS total_attempts_state,
    countIfState(passed = 1)                        AS passed_count_state,
    avgState(percentage_score)                      AS avg_score_state,
    avgState(time_spent_sec)                        AS avg_time_sec_state,
    countIfState(flagged = 1)                       AS flagged_count_state,
    quantileState(0.5)(percentage_score)            AS median_score_state,
    quantileState(0.9)(percentage_score)            AS p90_score_state
FROM exam_facts
GROUP BY org_id, exam_id, date;

-- View đọc "đẹp" để BI dùng (Metabase/Grafana sẽ SELECT từ view này thay vì MV raw).
CREATE VIEW IF NOT EXISTS v_exam_daily_stats AS
SELECT
    org_id, exam_id, date,
    countMerge(total_attempts_state)    AS total_attempts,
    countMerge(passed_count_state)      AS passed_count,
    avgMerge(avg_score_state)           AS avg_score,
    avgMerge(avg_time_sec_state)        AS avg_time_sec,
    countMerge(flagged_count_state)     AS flagged_count,
    quantileMerge(0.5)(median_score_state) AS median_score,
    quantileMerge(0.9)(p90_score_state)    AS p90_score
FROM mv_exam_daily_stats
GROUP BY org_id, exam_id, date;

-- =============================================================================
-- 5. MATERIALIZED VIEW: Thống kê câu hỏi (để calibrate IRT)
-- =============================================================================
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_question_stats
ENGINE = AggregatingMergeTree()
ORDER BY (org_id, question_ref_id)
POPULATE
AS SELECT
    org_id,
    question_ref_id,
    countState()                                AS total_responses_state,
    avgState(is_correct)                        AS correct_rate_state,
    avgState(time_spent_sec)                    AS avg_time_sec_state,
    stddevPopState(theta_at_answer)             AS theta_spread_state,
    corrState(theta_at_answer, is_correct)      AS point_biserial_state
FROM answer_analytics
GROUP BY org_id, question_ref_id;

CREATE VIEW IF NOT EXISTS v_question_stats AS
SELECT
    org_id, question_ref_id,
    countMerge(total_responses_state)   AS total_responses,
    avgMerge(correct_rate_state)        AS correct_rate,
    avgMerge(avg_time_sec_state)        AS avg_time_sec,
    stddevPopMerge(theta_spread_state)  AS theta_spread,
    corrMerge(point_biserial_state)     AS point_biserial
FROM mv_question_stats
GROUP BY org_id, question_ref_id;

-- =============================================================================
-- 6. MATERIALIZED VIEW: Gian lận theo tuần
-- =============================================================================
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_cheat_weekly
ENGINE = AggregatingMergeTree()
ORDER BY (org_id, event_type, week_start)
POPULATE
AS SELECT
    org_id,
    event_type,
    toMonday(toDate(occurred_at)) AS week_start,
    countState()                    AS event_count_state,
    sumState(risk_delta)            AS total_risk_state,
    uniqState(user_id)              AS unique_users_state,
    uniqState(attempt_id)           AS unique_attempts_state
FROM cheat_analytics
GROUP BY org_id, event_type, week_start;

CREATE VIEW IF NOT EXISTS v_cheat_weekly AS
SELECT
    org_id, event_type, week_start,
    countMerge(event_count_state)       AS event_count,
    sumMerge(total_risk_state)          AS total_risk,
    uniqMerge(unique_users_state)       AS unique_users,
    uniqMerge(unique_attempts_state)    AS unique_attempts
FROM mv_cheat_weekly
GROUP BY org_id, event_type, week_start;

-- =============================================================================
-- 7. BẢNG MỞ RỘNG TỪ ANALYTICS SERVICE (analytics-service-design.md mục IV.4)
-- =============================================================================

-- User activity fact: mọi page view, session, click
CREATE TABLE IF NOT EXISTS user_activity_facts
(
    event_id        UUID,
    user_id         UUID,
    session_id      UUID,
    org_id          UUID,
    activity_type   LowCardinality(String),    -- 'page_view','exam_start','review_answer',...
    page_path       String,
    referrer        String,
    device_type     LowCardinality(String),
    country_code    FixedString(3),
    ts              DateTime64(3),
    date            Date MATERIALIZED toDate(ts)
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(date)
ORDER BY (org_id, user_id, ts)
TTL date + INTERVAL 25 MONTH
SETTINGS index_granularity = 8192;

-- Feedback/rating câu hỏi (từ học sinh/giáo viên)
CREATE TABLE IF NOT EXISTS question_feedback_facts
(
    question_ref_id String,
    org_id          UUID,
    feedback_type   LowCardinality(String),    -- 'rating','report'
    value           Float32,
    ts              DateTime64(3),
    date            Date MATERIALIZED toDate(ts)
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(date)
ORDER BY (org_id, question_ref_id, ts)
SETTINGS index_granularity = 8192;

-- Experiment exposures (A/B testing)
CREATE TABLE IF NOT EXISTS experiment_exposures
(
    experiment_id   UUID,
    user_id         UUID,
    variant         LowCardinality(String),
    exposed_at      DateTime64(3),
    date            Date MATERIALIZED toDate(exposed_at)
)
ENGINE = MergeTree()
PARTITION BY toYYYYMM(date)
ORDER BY (experiment_id, user_id)
SETTINGS index_granularity = 8192;

-- =============================================================================
-- 8. MATERIALIZED VIEW BỔ SUNG (analytics-service-design.md)
-- =============================================================================

-- Tiến độ học của từng học sinh theo môn (daily aggregate)
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_student_progress
ENGINE = AggregatingMergeTree()
PARTITION BY toYYYYMM(date)
ORDER BY (user_id, subject_code, date)
POPULATE
AS SELECT
    user_id,
    subject_code,
    toDate(started_at) AS date,
    countState()                    AS attempts_state,
    avgState(percentage_score)      AS avg_score_state,
    maxState(percentage_score)      AS best_score_state,
    sumState(time_spent_sec)        AS time_spent_sec_state
FROM exam_facts
GROUP BY user_id, subject_code, date;

CREATE VIEW IF NOT EXISTS v_student_progress AS
SELECT
    user_id, subject_code, date,
    countMerge(attempts_state)      AS attempts,
    avgMerge(avg_score_state)       AS avg_score,
    maxMerge(best_score_state)      AS best_score,
    sumMerge(time_spent_sec_state)  AS time_spent_sec
FROM mv_student_progress
GROUP BY user_id, subject_code, date;

-- Cohort progress: tracking học sinh theo tháng đăng ký
-- NOTE: view này cần dictionary dict_users để join user → cohort_month.
-- Để đơn giản local, tạo view dựa trên metadata đã có trong exam_facts (org_id, user_id)
-- và tính cohort_month từ first attempt của user đó.
-- Engine SimpleAggregatingMergeTree phù hợp hơn với min(): state = giá trị cuối cùng,
-- tương thích với AggregatingMergeTree + minSimpleState không có; dùng minState thay thế.
CREATE MATERIALIZED VIEW IF NOT EXISTS mv_user_first_attempt
ENGINE = AggregatingMergeTree()
ORDER BY (user_id)
POPULATE
AS SELECT
    user_id,
    minState(started_at) AS first_attempt_at_state
FROM exam_facts
GROUP BY user_id;

CREATE VIEW IF NOT EXISTS v_user_first_attempt AS
SELECT
    user_id,
    minMerge(first_attempt_at_state) AS first_attempt_at,
    toStartOfMonth(minMerge(first_attempt_at_state)) AS cohort_month
FROM mv_user_first_attempt
GROUP BY user_id;

-- =============================================================================
-- 9. KAFKA ENGINE TABLES (chỉ tạo khi có Kafka) - COMMENT ra nếu chưa có
-- =============================================================================
-- CREATE TABLE exam_facts_queue
-- (
--     attempt_id UUID, exam_id UUID, user_id UUID, org_id UUID,
--     subject_code String, started_at DateTime64(3),
--     submitted_at DateTime64(3), raw_score Float32, max_score Float32,
--     percentage_score Float32, passed UInt8, attempt_number UInt8,
--     time_spent_sec UInt32, risk_score UInt16, flagged UInt8,
--     cheat_events_count UInt16, country_code String,
--     device_type String, final_theta Float32, final_se Float32
-- ) ENGINE = Kafka
-- SETTINGS kafka_broker_list = 'kafka:9092',
--          kafka_topic_list  = 'quiz.public.exam_attempts',
--          kafka_group_name  = 'clickhouse_exam_facts',
--          kafka_format      = 'JSONEachRow',
--          kafka_num_consumers = 2;
--
-- CREATE MATERIALIZED VIEW mv_kafka_to_exam_facts TO exam_facts
-- AS SELECT * FROM exam_facts_queue;
