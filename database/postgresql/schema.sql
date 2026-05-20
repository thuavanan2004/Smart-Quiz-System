-- =============================================================================
-- schema.sql — PostgreSQL 16 + pgvector | SmartQuizSystem (DATN scope)
-- =============================================================================
-- Single source of truth cho schema PostgreSQL của đồ án tốt nghiệp.
-- Mount vào Docker initdb (xem infra/docker-compose.dev.yml) để container tự
-- khởi tạo schema khi volume trống, hoặc chạy thủ công:
--     psql -U postgres -d smartquiz -f database/postgresql/schema.sql
--
-- Image bắt buộc: pgvector/pgvector:pg16 (vì cột embedding vector(384)).
--
-- Kiến trúc: 3 schema cho 3 service, 1 PG instance chung.
--   - auth       : Auth service
--   - core       : Core service (exam + question + attempt + analytics + AI orch.)
--   - proctoring : Proctoring service (cheat L1-L3)
--
-- Doc tham chiếu:
--   - database/postgresql/README.md (schema ownership + role grants + migration)
--
-- MỤC LỤC
--   1.  Extensions
--   2.  Schemas + roles (optional grants)
--   3.  Schema `auth`  : users, refresh_tokens
--   4.  Schema `core`  : questions, exams, attempts, answers, documents, AI tables, outbox
--   5.  Analytics views (thay ClickHouse)
--   6.  Schema `proctoring` : cheat_events, cheat_alerts, sessions, outbox
--   7.  Triggers (updated_at)
-- =============================================================================


-- =============================================================================
-- 1. EXTENSIONS
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS vector;    -- pgvector


-- =============================================================================
-- 2. SCHEMAS
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS auth;
CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS proctoring;

-- Optional: tạo role per service (bỏ comment khi deploy)
-- CREATE ROLE auth_app       LOGIN PASSWORD 'change-me';
-- CREATE ROLE core_app       LOGIN PASSWORD 'change-me';
-- CREATE ROLE proctoring_app LOGIN PASSWORD 'change-me';
-- CREATE ROLE ai_reader      LOGIN PASSWORD 'change-me';
--
-- GRANT USAGE ON SCHEMA auth       TO auth_app;
-- GRANT USAGE ON SCHEMA core       TO core_app, ai_reader;
-- GRANT USAGE ON SCHEMA proctoring TO proctoring_app;
--
-- GRANT ALL PRIVILEGES ON ALL TABLES    IN SCHEMA auth       TO auth_app;
-- GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA auth       TO auth_app;
-- GRANT ALL PRIVILEGES ON ALL TABLES    IN SCHEMA core       TO core_app;
-- GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA core       TO core_app;
-- GRANT ALL PRIVILEGES ON ALL TABLES    IN SCHEMA proctoring TO proctoring_app;
-- GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA proctoring TO proctoring_app;
--
-- -- AI service đọc document text + question embeddings, update stylometry baseline + ai_cache
-- GRANT SELECT                              ON core.documents, core.questions        TO ai_reader;
-- GRANT SELECT, INSERT, UPDATE              ON core.ai_cache                         TO ai_reader;
-- GRANT SELECT, INSERT                      ON core.student_writing_profiles         TO ai_reader;
-- GRANT UPDATE (avg_embedding, sample_count, updated_at) ON core.student_writing_profiles TO ai_reader;


-- =============================================================================
-- 3. SCHEMA `auth`
-- =============================================================================

CREATE TABLE auth.users (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email           CITEXT UNIQUE NOT NULL,
    password_hash   TEXT NOT NULL,                                    -- BCrypt cost 12
    full_name       TEXT NOT NULL,
    role            VARCHAR(16) NOT NULL CHECK (role IN ('STUDENT','TEACHER','ADMIN')),
    is_active       BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_users_role_active ON auth.users(role) WHERE is_active = true;

CREATE TABLE auth.refresh_tokens (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    token_hash      TEXT NOT NULL,                                    -- sha256(raw)
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ,
    replaced_by     UUID REFERENCES auth.refresh_tokens(id),
    user_agent      TEXT,
    ip              INET
);
CREATE UNIQUE INDEX ux_refresh_tokens_hash ON auth.refresh_tokens(token_hash);
CREATE INDEX ix_refresh_tokens_user_active ON auth.refresh_tokens(user_id) WHERE revoked_at IS NULL;


-- =============================================================================
-- 4. SCHEMA `core`
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 4.1. ENUM types
-- -----------------------------------------------------------------------------

CREATE TYPE core.question_type  AS ENUM ('MCQ_SINGLE','MCQ_MULTI','TRUE_FALSE','SHORT_ANSWER','ESSAY');
CREATE TYPE core.difficulty     AS ENUM ('EASY','MEDIUM','HARD');
CREATE TYPE core.exam_status    AS ENUM ('DRAFT','PUBLISHED','ARCHIVED');
CREATE TYPE core.attempt_status AS ENUM ('IN_PROGRESS','SUSPENDED','SUBMITTED','GRADED','CANCELLED');
CREATE TYPE core.document_status AS ENUM ('UPLOADED','EXTRACTING','READY','FAILED');
CREATE TYPE core.gen_job_status  AS ENUM ('QUEUED','RUNNING','DONE','FAILED');

-- -----------------------------------------------------------------------------
-- 4.2. Documents (cho feature upload → sinh đề)
--       Đặt trước `questions` vì `questions.source_document_id` tham chiếu.
-- -----------------------------------------------------------------------------

CREATE TABLE core.documents (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    uploaded_by     UUID NOT NULL,                                    -- logical FK -> auth.users.id (cross-schema, không enforce)
    filename        TEXT NOT NULL,
    mime_type       VARCHAR(128) NOT NULL,
    size_bytes      BIGINT NOT NULL CHECK (size_bytes > 0),
    storage_path    TEXT NOT NULL,                                    -- ./data/uploads/{uuid}.pdf
    text_content    TEXT,                                             -- extract sau khi Tika parse
    page_count      INT,
    status          core.document_status NOT NULL DEFAULT 'UPLOADED',
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ
);
CREATE INDEX ix_documents_uploader ON core.documents(uploaded_by, created_at DESC);
CREATE INDEX ix_documents_status   ON core.documents(status) WHERE status <> 'READY';

-- -----------------------------------------------------------------------------
-- 4.3. Questions (+ embedding 384d cho pgvector)
-- -----------------------------------------------------------------------------

CREATE TABLE core.questions (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    type                core.question_type NOT NULL,
    difficulty          core.difficulty NOT NULL DEFAULT 'MEDIUM',
    content             JSONB NOT NULL,                               -- {stem, options[], correct_answer, rubric?, explanation?}
    metadata            JSONB NOT NULL DEFAULT '{}'::jsonb,           -- {topic, tags[], bloom_level}
    version             INT NOT NULL DEFAULT 1,
    ai_generated        BOOLEAN NOT NULL DEFAULT false,
    source_document_id  UUID REFERENCES core.documents(id) ON DELETE SET NULL,
    embedding           vector(384),                                  -- sentence-transformers/all-MiniLM-L6-v2
    created_by          UUID NOT NULL,                                -- auth.users.id
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_questions_type       ON core.questions(type);
CREATE INDEX ix_questions_created    ON core.questions(created_by, created_at DESC);
CREATE INDEX ix_questions_metadata   ON core.questions USING GIN (metadata jsonb_path_ops);
CREATE INDEX ix_questions_embedding  ON core.questions USING hnsw (embedding vector_cosine_ops);

-- -----------------------------------------------------------------------------
-- 4.4. Question generation jobs (feature upload → sinh đề)
-- -----------------------------------------------------------------------------

CREATE TABLE core.question_generation_jobs (
    id                      UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    document_id             UUID REFERENCES core.documents(id) ON DELETE SET NULL,
    requested_by            UUID NOT NULL,
    config                  JSONB NOT NULL,                           -- {topic, difficulty, count, type}
    status                  core.gen_job_status NOT NULL DEFAULT 'QUEUED',
    result_count            INT NOT NULL DEFAULT 0,
    error_message           TEXT,
    generated_question_ids  UUID[] NOT NULL DEFAULT '{}',
    preview_payload         JSONB,                                    -- full result trước khi commit
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at            TIMESTAMPTZ
);
CREATE INDEX ix_gen_jobs_status    ON core.question_generation_jobs(status);
CREATE INDEX ix_gen_jobs_requester ON core.question_generation_jobs(requested_by, created_at DESC);

-- -----------------------------------------------------------------------------
-- 4.5. Exams
-- -----------------------------------------------------------------------------

CREATE TABLE core.exams (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    title               TEXT NOT NULL,
    description         TEXT,
    duration_min        INT NOT NULL CHECK (duration_min > 0),
    total_points        NUMERIC(6,2) NOT NULL DEFAULT 100,
    pass_score          NUMERIC(6,2),
    status              core.exam_status NOT NULL DEFAULT 'DRAFT',
    open_at             TIMESTAMPTZ,
    close_at            TIMESTAMPTZ,
    shuffle_questions   BOOLEAN NOT NULL DEFAULT false,
    shuffle_options     BOOLEAN NOT NULL DEFAULT false,
    created_by          UUID NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at        TIMESTAMPTZ,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (open_at IS NULL OR close_at IS NULL OR open_at < close_at)
);
CREATE INDEX ix_exams_status  ON core.exams(status);
CREATE INDEX ix_exams_creator ON core.exams(created_by, created_at DESC);

-- Snapshot câu hỏi tại thời điểm publish exam
CREATE TABLE core.exam_questions (
    exam_id             UUID NOT NULL REFERENCES core.exams(id) ON DELETE CASCADE,
    position            INT NOT NULL CHECK (position > 0),
    question_id         UUID NOT NULL REFERENCES core.questions(id) ON DELETE RESTRICT,
    question_version    INT NOT NULL,
    points              NUMERIC(6,2) NOT NULL DEFAULT 1.0 CHECK (points >= 0),
    snapshot            JSONB NOT NULL,                               -- chốt cứng content tại publish time
    PRIMARY KEY (exam_id, position)
);
CREATE INDEX ix_exam_questions_q ON core.exam_questions(question_id);

-- Gán exam cho student
CREATE TABLE core.exam_assignments (
    exam_id         UUID NOT NULL REFERENCES core.exams(id) ON DELETE CASCADE,
    student_id      UUID NOT NULL,
    assigned_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (exam_id, student_id)
);
CREATE INDEX ix_assignments_student ON core.exam_assignments(student_id);

-- -----------------------------------------------------------------------------
-- 4.6. Attempts + Answers (+ state_version fencing)
-- -----------------------------------------------------------------------------

CREATE TABLE core.exam_attempts (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    exam_id             UUID NOT NULL REFERENCES core.exams(id) ON DELETE RESTRICT,
    student_id          UUID NOT NULL,
    status              core.attempt_status NOT NULL DEFAULT 'IN_PROGRESS',
    state_version       BIGINT NOT NULL DEFAULT 0,                    -- fencing token
    started_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at        TIMESTAMPTZ,
    graded_at           TIMESTAMPTZ,
    deadline_at         TIMESTAMPTZ NOT NULL,
    total_score         NUMERIC(6,2),
    suspended_reason    TEXT,
    UNIQUE (exam_id, student_id)                                      -- DATN: 1 attempt per exam per student
);
CREATE INDEX ix_attempts_student ON core.exam_attempts(student_id, status);
CREATE INDEX ix_attempts_exam    ON core.exam_attempts(exam_id, status);
CREATE INDEX ix_attempts_in_progress ON core.exam_attempts(deadline_at)
    WHERE status = 'IN_PROGRESS';

CREATE TABLE core.attempt_answers (
    id                          UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    attempt_id                  UUID NOT NULL REFERENCES core.exam_attempts(id) ON DELETE CASCADE,
    position                    INT NOT NULL CHECK (position > 0),
    question_id                 UUID NOT NULL REFERENCES core.questions(id) ON DELETE RESTRICT,
    answer_data                 JSONB NOT NULL DEFAULT '{}'::jsonb,   -- {selected: [...]} | {text: "..."}
    score                       NUMERIC(6,2),
    feedback                    TEXT,
    -- Metadata chấm điểm
    graded_by                   VARCHAR(16),                          -- RULE | AI | TEACHER (override)
    grading_provider            VARCHAR(16),                          -- gemini | ollama | null (RULE)
    -- AI tutor explanation
    ai_explanation              TEXT,
    ai_explanation_status       VARCHAR(16),                          -- PENDING | READY | FAILED | SKIPPED
    -- AI essay detection
    ai_detection_score          NUMERIC(5,4) CHECK (ai_detection_score IS NULL OR (ai_detection_score BETWEEN 0 AND 1)),
    ai_detection_method         VARCHAR(32),                          -- perplexity | stylometry | hybrid
    ai_detection_details        JSONB,
    -- Teacher override (safety net khi AI fail / sai)
    teacher_override_score      NUMERIC(6,2),
    teacher_override_reason     TEXT,
    teacher_override_by         UUID,                                 -- auth.users.id (role TEACHER/ADMIN)
    teacher_override_at         TIMESTAMPTZ,
    submitted_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    graded_at                   TIMESTAMPTZ,
    UNIQUE (attempt_id, position)
);
CREATE INDEX ix_answers_attempt ON core.attempt_answers(attempt_id);
CREATE INDEX ix_answers_question ON core.attempt_answers(question_id);
CREATE INDEX ix_answers_ai_detection_high ON core.attempt_answers(ai_detection_score DESC)
    WHERE ai_detection_score >= 0.7;
-- Danh sách essay cần teacher chấm tay khi AI degraded
CREATE INDEX ix_answers_needs_human ON core.attempt_answers(graded_at NULLS FIRST)
    WHERE ai_explanation_status = 'FAILED' OR (graded_by IS NULL AND submitted_at IS NOT NULL);

-- -----------------------------------------------------------------------------
-- 4.7. Stylometry baseline + LLM cache (AI Combo A)
-- -----------------------------------------------------------------------------

-- Baseline phong cách viết mỗi student (update dần sau mỗi essay đã confirmed)
CREATE TABLE core.student_writing_profiles (
    user_id         UUID PRIMARY KEY,
    avg_embedding   vector(384) NOT NULL,
    sample_count    INT NOT NULL DEFAULT 0 CHECK (sample_count >= 0),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Cache LLM response (giảm token cost)
CREATE TABLE core.ai_cache (
    cache_key           TEXT PRIMARY KEY,                             -- sha256(prompt_type + inputs + model)
    response            JSONB NOT NULL,
    model               VARCHAR(64) NOT NULL,
    tokens_prompt       INT,
    tokens_completion   INT,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_hit_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    hit_count           INT NOT NULL DEFAULT 0
);
CREATE INDEX ix_ai_cache_last_hit ON core.ai_cache(last_hit_at DESC);
CREATE INDEX ix_ai_cache_model    ON core.ai_cache(model);

-- -----------------------------------------------------------------------------
-- 4.8. Outbox + processed_events (transactional outbox)
-- -----------------------------------------------------------------------------

CREATE TABLE core.outbox (
    id              BIGSERIAL PRIMARY KEY,
    event_id        UUID NOT NULL UNIQUE DEFAULT uuid_generate_v4(),
    topic           TEXT NOT NULL,
    aggregate_type  VARCHAR(64) NOT NULL,                             -- 'Attempt','Exam','Document'...
    aggregate_id    VARCHAR(64) NOT NULL,
    partition_key   TEXT,
    payload         JSONB NOT NULL,
    headers         JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    attempts        INT NOT NULL DEFAULT 0
);
CREATE INDEX ix_outbox_unpublished ON core.outbox(created_at)
    WHERE published_at IS NULL;

CREATE TABLE core.processed_events (
    event_id        UUID PRIMARY KEY,
    topic           TEXT NOT NULL,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_processed_events_topic ON core.processed_events(topic, processed_at DESC);


-- =============================================================================
-- 5. ANALYTICS VIEWS (thay ClickHouse)
-- =============================================================================

-- 5.1. Thống kê mỗi exam (count, avg, median, min/max score)
CREATE OR REPLACE VIEW core.v_exam_stats AS
SELECT
    e.id                                                          AS exam_id,
    e.title,
    COUNT(a.id)                                                   AS total_attempts,
    COUNT(a.id) FILTER (WHERE a.status = 'GRADED')                AS graded_count,
    AVG(a.total_score) FILTER (WHERE a.status = 'GRADED')         AS avg_score,
    percentile_cont(0.5) WITHIN GROUP (ORDER BY a.total_score)
        FILTER (WHERE a.status = 'GRADED')                        AS median_score,
    MIN(a.total_score) FILTER (WHERE a.status = 'GRADED')         AS min_score,
    MAX(a.total_score) FILTER (WHERE a.status = 'GRADED')         AS max_score
FROM core.exams e
LEFT JOIN core.exam_attempts a ON a.exam_id = e.id
GROUP BY e.id, e.title;

-- 5.2. Histogram điểm (bucket 10 điểm) per exam
CREATE OR REPLACE VIEW core.v_score_histogram AS
SELECT
    exam_id,
    FLOOR(total_score / 10) * 10                                  AS bucket_start,
    COUNT(*)                                                      AS count
FROM core.exam_attempts
WHERE status = 'GRADED' AND total_score IS NOT NULL
GROUP BY exam_id, bucket_start
ORDER BY exam_id, bucket_start;

-- 5.3. Chất lượng câu hỏi
--   pct_full_credit: tỉ lệ đạt điểm tối đa (áp dụng cho mọi loại, chuẩn nhất).
--   pct_any_credit : tỉ lệ có điểm > 0 (partial credit cho MCQ_MULTI, SHORT_ANSWER fuzzy).
-- Phải JOIN core.exam_questions để biết max points tại thời điểm exam publish
-- (không dùng core.questions hiện tại vì points thay đổi theo exam).
CREATE OR REPLACE VIEW core.v_question_quality AS
SELECT
    q.id                                                                         AS question_id,
    q.type,
    q.difficulty,
    COUNT(aa.id)                                                                 AS answer_count,
    COALESCE(
        AVG(CASE WHEN aa.score >= eq.points THEN 1 ELSE 0 END)::numeric(5,4),
        0
    )                                                                            AS pct_full_credit,
    COALESCE(
        AVG(CASE WHEN aa.score > 0 THEN 1 ELSE 0 END)::numeric(5,4),
        0
    )                                                                            AS pct_any_credit,
    COALESCE(
        AVG(aa.score / NULLIF(eq.points, 0))::numeric(5,4),
        0
    )                                                                            AS avg_score_ratio
FROM core.questions q
LEFT JOIN core.attempt_answers aa ON aa.question_id = q.id
LEFT JOIN core.exam_questions   eq ON eq.question_id = q.id
                                   AND EXISTS (
                                       SELECT 1 FROM core.exam_attempts ea
                                       WHERE ea.id = aa.attempt_id AND ea.exam_id = eq.exam_id
                                   )
GROUP BY q.id, q.type, q.difficulty;


-- =============================================================================
-- 6. SCHEMA `proctoring`
-- =============================================================================

CREATE TYPE proctoring.event_type AS ENUM (
    'TAB_BLUR',
    'TAB_FOCUS',
    'PASTE',
    'COPY',
    'WINDOW_RESIZE',
    'FULLSCREEN_EXIT',
    'HEARTBEAT_LOST',
    'TIMING_ANOMALY'
);
CREATE TYPE proctoring.severity AS ENUM ('INFO','WARN','HIGH');

-- Raw events (append-only audit log)
-- attempt_id/student_id là logical FK (xem note ở cheat_alerts dưới).
CREATE TABLE proctoring.cheat_events (
    id              BIGSERIAL PRIMARY KEY,
    event_id        UUID NOT NULL UNIQUE,                             -- từ Core WS forward
    attempt_id      UUID NOT NULL,
    student_id      UUID NOT NULL,
    event_type      proctoring.event_type NOT NULL,
    event_data      JSONB NOT NULL DEFAULT '{}'::jsonb,
    occurred_at     TIMESTAMPTZ NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_cheat_events_attempt ON proctoring.cheat_events(attempt_id, occurred_at);
CREATE INDEX ix_cheat_events_type    ON proctoring.cheat_events(event_type, occurred_at DESC);

-- Alert đã aggregate (gửi cho coi thi)
-- attempt_id/student_id là logical FK -> core.exam_attempts/auth.users (cross-schema).
-- Không enforce REFERENCES vì proctoring schema tách riêng; orphan có thể xảy ra nếu
-- attempt bị hard-delete (thực tế core chỉ soft-delete qua status=CANCELLED).
CREATE TABLE proctoring.cheat_alerts (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    attempt_id      UUID NOT NULL,
    student_id      UUID NOT NULL,
    rule_name       VARCHAR(64) NOT NULL,                             -- 'EXCESSIVE_TAB_BLUR','LARGE_PASTE'...
    severity        proctoring.severity NOT NULL,
    evidence        JSONB NOT NULL,                                   -- {count, threshold, window, sample_event_ids[]}
    reviewed_by     UUID,                                             -- teacher id
    reviewed_at     TIMESTAMPTZ,
    review_decision VARCHAR(16),                                      -- ACCEPT | DISMISS
    review_note     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_cheat_alerts_attempt    ON proctoring.cheat_alerts(attempt_id, created_at DESC);
CREATE INDEX ix_cheat_alerts_unreviewed ON proctoring.cheat_alerts(created_at)
    WHERE reviewed_at IS NULL;

-- Session tracking (heartbeat)
CREATE TABLE proctoring.proctoring_sessions (
    attempt_id          UUID PRIMARY KEY,
    started_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_heartbeat_at   TIMESTAMPTZ,
    client_user_agent   TEXT,
    client_ip           INET,
    ended_at            TIMESTAMPTZ
);

-- Outbox + processed_events riêng cho proctoring
CREATE TABLE proctoring.outbox (
    id              BIGSERIAL PRIMARY KEY,
    event_id        UUID NOT NULL UNIQUE DEFAULT uuid_generate_v4(),
    topic           TEXT NOT NULL,
    aggregate_type  VARCHAR(64) NOT NULL,
    aggregate_id    VARCHAR(64) NOT NULL,
    partition_key   TEXT,
    payload         JSONB NOT NULL,
    headers         JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,
    attempts        INT NOT NULL DEFAULT 0
);
CREATE INDEX ix_proctoring_outbox_unpublished ON proctoring.outbox(created_at)
    WHERE published_at IS NULL;

CREATE TABLE proctoring.processed_events (
    event_id        UUID PRIMARY KEY,
    topic           TEXT NOT NULL,
    processed_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_proctoring_processed_events_topic ON proctoring.processed_events(topic, processed_at DESC);


-- =============================================================================
-- 7. TRIGGERS
-- =============================================================================

-- 7.1. Auto-touch updated_at
CREATE OR REPLACE FUNCTION core.fn_touch_updated_at()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_users_updated_at
    BEFORE UPDATE ON auth.users
    FOR EACH ROW EXECUTE FUNCTION core.fn_touch_updated_at();

CREATE TRIGGER trg_questions_updated_at
    BEFORE UPDATE ON core.questions
    FOR EACH ROW EXECUTE FUNCTION core.fn_touch_updated_at();

CREATE TRIGGER trg_exams_updated_at
    BEFORE UPDATE ON core.exams
    FOR EACH ROW EXECUTE FUNCTION core.fn_touch_updated_at();

-- 7.2. Auto-set documents.processed_at khi status chuyển sang READY
CREATE OR REPLACE FUNCTION core.fn_documents_processed_at()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.status = 'READY' AND OLD.status <> 'READY' THEN
        NEW.processed_at := now();
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_documents_processed_at
    BEFORE UPDATE ON core.documents
    FOR EACH ROW EXECUTE FUNCTION core.fn_documents_processed_at();

-- 7.3. Auto-set attempt_answers.graded_at khi score được set lần đầu
CREATE OR REPLACE FUNCTION core.fn_answers_graded_at()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.score IS NOT NULL AND OLD.score IS NULL THEN
        NEW.graded_at := COALESCE(NEW.graded_at, now());
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_answers_graded_at
    BEFORE UPDATE ON core.attempt_answers
    FOR EACH ROW EXECUTE FUNCTION core.fn_answers_graded_at();


-- =============================================================================
-- END
-- =============================================================================
