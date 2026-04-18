-- =============================================================================
-- V1776521023__add_outbox_and_fencing.sql
-- Source: ADR-001 (SLA 99.9% + RPO ≤ 5s + transactional outbox) và CLAUDE.md §9.
--
-- Mục đích:
--   1. Thêm `state_version BIGINT` làm fencing token cho exam_attempts,
--      chống race khi suspend/resume/submit cạnh tranh giữa Exam Service
--      và Cheating Detection (auto-suspend).
--   2. Tạo bảng `outbox` cho transactional outbox pattern.
--   3. Tạo bảng `processed_events` cho consumer idempotency
--      (at-least-once + dedupe bằng event_id).
--
-- Không sửa schema.sql đã merge. Nếu cần rollback: xem khối DOWN ở cuối file.
-- =============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- 1. FENCING TOKEN cho exam_attempts
-- ---------------------------------------------------------------------------
-- state_version tăng 1 mỗi lần UPDATE exam_attempts trong application (optimistic
-- locking qua JPA @Version hoặc WHERE state_version = ? ở native SQL).
-- Giải quyết race giữa:
--   - Client gọi POST /attempts/{id}/submit
--   - Cheating Detection consumer đồng thời suspend do risk_score >= 60
-- Service thua race phải retry với read mới (read-modify-write chuẩn).
-- ---------------------------------------------------------------------------
ALTER TABLE exam_attempts
    ADD COLUMN state_version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN exam_attempts.state_version IS
    'Fencing token cho state transition. Tăng 1 mỗi UPDATE (optimistic lock).';

-- ---------------------------------------------------------------------------
-- 2. OUTBOX TABLE
-- ---------------------------------------------------------------------------
-- Mỗi service ghi state change DOMAIN + INSERT INTO outbox trong 1 transaction.
-- Relayer process poll table, publish Kafka, mark published_at.
-- Thiết kế partition-friendly: created_at có index + có thể convert sang
-- PARTITION BY RANGE (created_at) khi volume lớn (>10M row).
-- ---------------------------------------------------------------------------
CREATE TABLE outbox (
    event_id        UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type  VARCHAR(50) NOT NULL,        -- "exam_attempt", "user", ...
    aggregate_id    VARCHAR(64) NOT NULL,        -- UUID hoặc composite key dạng string
    topic           VARCHAR(100) NOT NULL,       -- Kafka topic đích, ví dụ "exam.answer.submitted.v1"
    event_type      VARCHAR(100) NOT NULL,       -- Logical event type (trùng topic phần lớn thời gian)
    payload         JSONB       NOT NULL,        -- Avro-compatible JSON payload
    headers         JSONB       DEFAULT '{}'::jsonb, -- trace_id, span_id, schema_version
    partition_key   VARCHAR(128),                -- key cho Kafka partitioning (null = random)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at    TIMESTAMPTZ,                 -- NULL = pending; != NULL = đã publish
    publish_attempts SMALLINT   NOT NULL DEFAULT 0,
    last_error      TEXT
);

-- Index cho relayer claim rows pending (FOR UPDATE SKIP LOCKED):
CREATE INDEX idx_outbox_pending
    ON outbox (created_at)
    WHERE published_at IS NULL;

-- Index cho cleanup job tìm row đã publish cũ:
CREATE INDEX idx_outbox_published
    ON outbox (published_at)
    WHERE published_at IS NOT NULL;

COMMENT ON TABLE outbox IS
    'Transactional outbox — cùng transaction với domain state change, relayer publish Kafka sau đó.';

-- ---------------------------------------------------------------------------
-- 3. PROCESSED_EVENTS TABLE (per-consumer dedupe)
-- ---------------------------------------------------------------------------
-- MỖI SERVICE tự có bảng này (prefix theo service nếu cần):
--   - Auth không consume nhiều → 1 bảng chung cũng được.
--   - Exam / Analytics / Cheating consume nhiều topic → dùng partition theo
--     consumer_group hoặc tách schema (auth.processed_events, exam.processed_events).
-- MVP: dùng 1 bảng chung với cột consumer_group để tránh phức tạp hoá schema.
-- Phase 2: tách theo database schema khi mỗi service có DB riêng.
-- ---------------------------------------------------------------------------
CREATE TABLE processed_events (
    event_id        UUID        NOT NULL,
    consumer_group  VARCHAR(100) NOT NULL,    -- ví dụ "exam-service-grading-consumer"
    topic           VARCHAR(100) NOT NULL,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (event_id, consumer_group)
);

-- Index hỗ trợ cleanup job giữ N ngày:
CREATE INDEX idx_processed_events_processed_at
    ON processed_events (processed_at);

COMMENT ON TABLE processed_events IS
    'Dedupe at-least-once Kafka consumer. INSERT ... ON CONFLICT DO NOTHING; nếu 0 row → đã xử lý.';

COMMIT;

-- ---------------------------------------------------------------------------
-- ROLLBACK (chạy thủ công nếu cần revert):
-- ---------------------------------------------------------------------------
-- BEGIN;
-- DROP TABLE IF EXISTS processed_events;
-- DROP TABLE IF EXISTS outbox;
-- ALTER TABLE exam_attempts DROP COLUMN IF EXISTS state_version;
-- COMMIT;
