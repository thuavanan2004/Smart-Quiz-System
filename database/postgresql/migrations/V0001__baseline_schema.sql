-- =============================================================================
-- V0001__baseline_schema.sql — PostgreSQL 16 baseline schema
-- =============================================================================
-- Toàn bộ ENUM, bảng, FK, index của PostgreSQL.
-- Là Flyway baseline: migration đầu tiên khi Flyway chạy trên DB trống.
-- Đồng thời được mount vào Docker initdb để PG container tự seed schema
-- khi volume trống (xem infra/docker-compose.dev.yml + database/docker-compose.yml).
-- =============================================================================

-- Extensions cần thiết
CREATE EXTENSION IF NOT EXISTS "pgcrypto";      -- gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS "citext";        -- case-insensitive text
CREATE EXTENSION IF NOT EXISTS "pg_trgm";       -- trigram search

-- =============================================================================
-- 1. ENUM TYPES
-- =============================================================================
-- NOTE: user_role ENUM đã REMOVED (trước đây hardcode 4 giá trị).
-- Thay bằng bảng roles/permissions/role_permissions (xem mục 2a) để RBAC động.

CREATE TYPE exam_status AS ENUM (
    'draft', 'published', 'scheduled', 'active', 'completed', 'archived'
);

CREATE TYPE attempt_status AS ENUM (
    'in_progress', 'submitted', 'graded', 'suspended', 'expired', 'cancelled'
);

CREATE TYPE cheat_event_type AS ENUM (
    'tab_switch','window_blur','fullscreen_exit','copy_event','paste_event',
    'right_click','devtools_open','keyboard_shortcut','context_menu',
    'ip_change','vpn_detected','multiple_ip','geolocation_change',
    'typing_anomaly','answer_speed_anomaly','idle_too_long','answer_pattern',
    'face_missing','multiple_faces','phone_detected','gaze_off_screen',
    'audio_detected','environment_issue',
    'answer_similarity','sync_submission','score_anomaly'
);

-- =============================================================================
-- 2. NHÓM TỔ CHỨC & NGƯỜI DÙNG
-- =============================================================================

CREATE TABLE organizations (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(300) NOT NULL,
    slug                VARCHAR(100) UNIQUE NOT NULL,
    plan_tier           VARCHAR(30) DEFAULT 'free',
    max_users           INT DEFAULT 100,
    max_exams           INT DEFAULT 50,
    ai_enabled          BOOLEAN DEFAULT false,
    proctoring_enabled  BOOLEAN DEFAULT false,
    settings            JSONB DEFAULT '{}'::jsonb,
    is_active           BOOLEAN DEFAULT true,
    created_at          TIMESTAMPTZ DEFAULT NOW(),
    deleted_at          TIMESTAMPTZ
);

CREATE INDEX idx_orgs_slug   ON organizations(slug);
CREATE INDEX idx_orgs_active ON organizations(is_active) WHERE is_active = true;

CREATE TABLE users (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email               CITEXT UNIQUE NOT NULL,
    username            VARCHAR(80) UNIQUE,
    full_name           VARCHAR(200) NOT NULL,
    avatar_url          VARCHAR(500),
    password_hash       VARCHAR(255),
    mfa_secret          VARCHAR(64),
    mfa_enabled         BOOLEAN DEFAULT false,
    email_verified      BOOLEAN DEFAULT false,
    locale              VARCHAR(10) DEFAULT 'vi-VN',
    timezone            VARCHAR(60) DEFAULT 'Asia/Ho_Chi_Minh',
    last_login_at       TIMESTAMPTZ,
    last_login_ip       INET,
    failed_login_count  SMALLINT DEFAULT 0,
    locked_until        TIMESTAMPTZ,
    is_active           BOOLEAN DEFAULT true,
    created_at          TIMESTAMPTZ DEFAULT NOW(),
    updated_at          TIMESTAMPTZ DEFAULT NOW(),
    deleted_at          TIMESTAMPTZ
);

-- Note: email đã có UNIQUE constraint (btree tự tạo). Partial index bổ sung
-- cho query theo email của tài khoản còn active, kèm pg_trgm cho tìm theo tên.
CREATE INDEX idx_users_email_active ON users(email)       WHERE deleted_at IS NULL;
CREATE INDEX idx_users_active       ON users(is_active)   WHERE is_active = true AND deleted_at IS NULL;
CREATE INDEX idx_users_last_login   ON users(last_login_at DESC);
CREATE INDEX idx_users_fullname_trgm ON users USING GIN (full_name gin_trgm_ops);

-- =============================================================================
-- 2a. RBAC DỘNG: roles / permissions / role_permissions
-- =============================================================================
-- Thay thế user_role ENUM hardcoded. Hỗ trợ:
--   - 4 role hệ thống (is_system=true): student, instructor, admin, proctor
--   - Role tuỳ biến theo org (is_system=false, org_id != NULL)
--   - Permission-based RBAC (service check hasAuthority('exam.update') thay vì hasRole)

CREATE TABLE roles (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id       UUID REFERENCES organizations(id) ON DELETE CASCADE,  -- NULL = system role
    code         VARCHAR(50) NOT NULL,    -- 'student', 'instructor', 'custom.grading_assistant'...
    name         VARCHAR(200) NOT NULL,
    description  TEXT,
    is_system    BOOLEAN DEFAULT false,   -- true = không được sửa/xoá
    is_active    BOOLEAN DEFAULT true,
    created_at   TIMESTAMPTZ DEFAULT NOW(),
    updated_at   TIMESTAMPTZ DEFAULT NOW()
);
-- System roles có org_id=NULL; code unique trong phạm vi system
CREATE UNIQUE INDEX idx_roles_system_code ON roles(code) WHERE org_id IS NULL;
-- Custom roles unique trong 1 org
CREATE UNIQUE INDEX idx_roles_org_code    ON roles(org_id, code) WHERE org_id IS NOT NULL;
CREATE INDEX idx_roles_org_active ON roles(org_id, is_active);

CREATE TABLE permissions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    code         VARCHAR(100) UNIQUE NOT NULL,   -- 'exam.create', 'question.approve', 'user.impersonate'
    resource     VARCHAR(50) NOT NULL,           -- 'exam', 'question', 'user', 'attempt', 'cheat', 'analytics', 'ai'
    action       VARCHAR(50) NOT NULL,           -- 'create','read','update','delete','grade','approve','impersonate'...
    scope        VARCHAR(20) DEFAULT 'org',      -- 'own'|'org'|'platform'  (mở rộng enforcement)
    description  TEXT,
    is_system    BOOLEAN DEFAULT true            -- false nếu permission do org tạo (Phase 3)
);
CREATE INDEX idx_permissions_resource ON permissions(resource);

CREATE TABLE role_permissions (
    role_id       UUID NOT NULL REFERENCES roles(id) ON DELETE CASCADE,
    permission_id UUID NOT NULL REFERENCES permissions(id) ON DELETE CASCADE,
    granted_at    TIMESTAMPTZ DEFAULT NOW(),
    granted_by    UUID REFERENCES users(id),
    PRIMARY KEY (role_id, permission_id)
);
CREATE INDEX idx_role_perms_role ON role_permissions(role_id);

-- =============================================================================
-- 2b. USER ↔ ORG (role chuyển sang role_id FK)
-- =============================================================================
CREATE TABLE user_organizations (
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    org_id      UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    role_id     UUID NOT NULL REFERENCES roles(id) ON DELETE RESTRICT,
    joined_at   TIMESTAMPTZ DEFAULT NOW(),
    invited_by  UUID REFERENCES users(id),
    is_active   BOOLEAN DEFAULT true,
    PRIMARY KEY (user_id, org_id)
);

CREATE INDEX idx_user_orgs_user ON user_organizations(user_id);
CREATE INDEX idx_user_orgs_org  ON user_organizations(org_id, role_id);
CREATE INDEX idx_user_orgs_role ON user_organizations(role_id);

CREATE TABLE oauth_providers (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider            VARCHAR(30) NOT NULL,
    provider_user_id    VARCHAR(255) NOT NULL,
    access_token_enc    TEXT,
    linked_at           TIMESTAMPTZ DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_oauth_provider ON oauth_providers(provider, provider_user_id);

CREATE TABLE refresh_tokens (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    token_hash          BYTEA UNIQUE NOT NULL,       -- SHA-256 raw (32 bytes); tiết kiệm & so sánh nhanh hơn hex VARCHAR(64)
    device_fingerprint  VARCHAR(128),
    user_agent          VARCHAR(512),
    ip_address          INET,
    expires_at          TIMESTAMPTZ NOT NULL,
    revoked             BOOLEAN DEFAULT false,
    revoked_at          TIMESTAMPTZ,
    created_at          TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_refresh_user  ON refresh_tokens(user_id, expires_at);
CREATE INDEX idx_refresh_token ON refresh_tokens(token_hash);

-- =============================================================================
-- 3. NHÓM BÀI THI & CẤU HÌNH
-- =============================================================================

CREATE TABLE subjects (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID REFERENCES organizations(id) ON DELETE CASCADE,
    name        VARCHAR(200) NOT NULL,
    code        VARCHAR(30) UNIQUE NOT NULL,
    parent_id   UUID REFERENCES subjects(id) ON DELETE SET NULL,
    description TEXT,
    is_active   BOOLEAN DEFAULT true
);

CREATE INDEX idx_subjects_org ON subjects(org_id);
CREATE INDEX idx_subjects_code ON subjects(code);

CREATE TABLE exams (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                      UUID NOT NULL REFERENCES organizations(id) ON DELETE RESTRICT,
    subject_id                  UUID REFERENCES subjects(id) ON DELETE SET NULL,
    created_by                  UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    title                       VARCHAR(500) NOT NULL,
    description                 TEXT,
    instructions                TEXT,
    status                      exam_status DEFAULT 'draft',
    exam_type                   VARCHAR(30) DEFAULT 'standard',
    duration_minutes            INT NOT NULL,
    max_attempts                SMALLINT DEFAULT 1,
    passing_score               NUMERIC(5,2),
    total_points                NUMERIC(8,2) NOT NULL,
    shuffle_questions           BOOLEAN DEFAULT false,
    shuffle_options             BOOLEAN DEFAULT false,
    show_result_immediately     BOOLEAN DEFAULT true,
    show_correct_answers        BOOLEAN DEFAULT false,
    allow_review                BOOLEAN DEFAULT false,
    proctoring_level            SMALLINT DEFAULT 0,
    lockdown_browser            BOOLEAN DEFAULT false,
    ip_whitelist                INET[],
    password_protected          BOOLEAN DEFAULT false,
    access_password_hash        VARCHAR(255),
    starts_at                   TIMESTAMPTZ,
    ends_at                     TIMESTAMPTZ,
    grace_period_minutes        SMALLINT DEFAULT 0,
    created_at                  TIMESTAMPTZ DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ DEFAULT NOW(),
    published_at                TIMESTAMPTZ,
    deleted_at                  TIMESTAMPTZ
);

CREATE INDEX idx_exams_org        ON exams(org_id, status) WHERE deleted_at IS NULL;
CREATE INDEX idx_exams_creator    ON exams(created_by)     WHERE deleted_at IS NULL;
CREATE INDEX idx_exams_active     ON exams(status, starts_at, ends_at)
    WHERE status IN ('published','scheduled','active') AND deleted_at IS NULL;
CREATE INDEX idx_exams_open       ON exams(org_id, status, starts_at, ends_at)
    WHERE status IN ('published','active') AND deleted_at IS NULL;
CREATE INDEX idx_exams_title_trgm ON exams USING GIN (title gin_trgm_ops);

CREATE TABLE exam_sections (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    exam_id                 UUID NOT NULL REFERENCES exams(id) ON DELETE CASCADE,
    title                   VARCHAR(200) NOT NULL,
    description             TEXT,
    order_index             SMALLINT NOT NULL,
    time_limit_minutes      SMALLINT,
    question_count          SMALLINT NOT NULL,
    points_per_question     NUMERIC(6,2),
    penalty_per_wrong       NUMERIC(6,2) DEFAULT 0
);

CREATE INDEX idx_sections_exam ON exam_sections(exam_id, order_index);

CREATE TABLE exam_questions (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    exam_id             UUID NOT NULL REFERENCES exams(id) ON DELETE CASCADE,
    section_id          UUID REFERENCES exam_sections(id) ON DELETE SET NULL,
    question_ref_id     VARCHAR(36) NOT NULL,
    question_version    INT DEFAULT 1,
    order_index         SMALLINT NOT NULL,
    points              NUMERIC(6,2) NOT NULL,
    is_required         BOOLEAN DEFAULT true,
    display_mode        VARCHAR(20) DEFAULT 'standard'
);

CREATE INDEX idx_eq_exam    ON exam_questions(exam_id, order_index);
CREATE INDEX idx_eq_section ON exam_questions(section_id);
CREATE UNIQUE INDEX idx_eq_order ON exam_questions(exam_id, section_id, order_index);

CREATE TABLE exam_enrollments (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    exam_id                     UUID NOT NULL REFERENCES exams(id) ON DELETE CASCADE,
    user_id                     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    enrolled_by                 UUID REFERENCES users(id),
    enrolled_at                 TIMESTAMPTZ DEFAULT NOW(),
    custom_duration_minutes     INT,
    custom_starts_at            TIMESTAMPTZ
);

CREATE UNIQUE INDEX idx_enrollment ON exam_enrollments(exam_id, user_id);

-- =============================================================================
-- 4. NHÓM LƯỢT THI & ĐÁP ÁN
-- =============================================================================
-- PARTITIONING NOTE (quan trọng khi chạy production):
--   exam_attempts và attempt_answers sẽ >60M rows/năm ở quy mô 100K MAU.
--   Khi đạt ~10M rows (ước 6 tháng), migrate sang partitioned table:
--     1) Đổi PK: (id, started_at)  -- PG yêu cầu PK chứa partition key
--     2) `PARTITION BY RANGE (started_at)`, dùng pg_partman tạo partition tháng.
--     3) Cập nhật các FK trỏ tới exam_attempts (attempt_answers, cheat_events,
--        proctoring_sessions, certificates, cheat_review_queue, cheat_appeals,
--        attempt_feedback) để reference (id, started_at).
--   Migration phải coordinate downtime + recreate FK — không tự động được.
--   Xem docs/database.md mục "Partitioning Strategy" cho kịch bản chi tiết.

CREATE TABLE exam_attempts (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    exam_id                     UUID NOT NULL REFERENCES exams(id) ON DELETE RESTRICT,
    user_id                     UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    attempt_number              SMALLINT DEFAULT 1,
    status                      attempt_status DEFAULT 'in_progress',
    started_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    submitted_at                TIMESTAMPTZ,
    graded_at                   TIMESTAMPTZ,
    expires_at                  TIMESTAMPTZ NOT NULL,
    time_spent_seconds          INT DEFAULT 0,
    raw_score                   NUMERIC(8,2),
    max_score                   NUMERIC(8,2) NOT NULL,
    percentage_score            NUMERIC(5,2),
    passed                      BOOLEAN,
    risk_score                  SMALLINT DEFAULT 0,
    flagged_for_review          BOOLEAN DEFAULT false,
    flagged_reason              TEXT,
    ip_address                  INET,
    user_agent                  VARCHAR(512),
    geo_country                 VARCHAR(3),
    geo_city                    VARCHAR(100),
    current_question_index      SMALLINT DEFAULT 0,
    question_order              UUID[],
    adaptive_theta              FLOAT8,
    adaptive_se                 FLOAT8
);

CREATE INDEX idx_attempts_exam_user   ON exam_attempts(exam_id, user_id);
CREATE INDEX idx_attempts_active      ON exam_attempts(status, expires_at) WHERE status = 'in_progress';
CREATE INDEX idx_attempts_flagged     ON exam_attempts(flagged_for_review) WHERE flagged_for_review = true;
CREATE UNIQUE INDEX idx_one_active_attempt ON exam_attempts(exam_id, user_id) WHERE status = 'in_progress';
CREATE INDEX idx_attempts_exam_perf   ON exam_attempts(exam_id, status, raw_score, percentage_score)
    WHERE status IN ('submitted','graded');
CREATE INDEX idx_attempts_user_recent ON exam_attempts(user_id, started_at DESC);   -- lịch sử thi của 1 user

CREATE TABLE attempt_answers (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attempt_id              UUID NOT NULL REFERENCES exam_attempts(id) ON DELETE CASCADE,
    question_ref_id         VARCHAR(36) NOT NULL,
    exam_question_id        UUID NOT NULL REFERENCES exam_questions(id) ON DELETE CASCADE,
    answer_data             JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_correct              BOOLEAN,
    points_earned           NUMERIC(6,2),
    partial_credit          BOOLEAN DEFAULT false,
    grading_method          VARCHAR(20) DEFAULT 'auto',
    graded_by               UUID REFERENCES users(id),
    grader_comment          TEXT,
    time_spent_seconds      SMALLINT DEFAULT 0,
    answered_at             TIMESTAMPTZ DEFAULT NOW(),
    last_modified_at        TIMESTAMPTZ DEFAULT NOW(),
    submission_id           UUID UNIQUE NOT NULL DEFAULT gen_random_uuid()
);

CREATE INDEX idx_answers_attempt     ON attempt_answers(attempt_id, exam_question_id);
CREATE INDEX idx_answers_question    ON attempt_answers(question_ref_id);
CREATE INDEX idx_answers_graded_man  ON attempt_answers(graded_by, answered_at)
    WHERE grading_method = 'manual';                  -- review queue của giáo viên chấm tay

-- =============================================================================
-- 5. NHÓM CHỐNG GIAN LẬN & GIÁM SÁT
-- =============================================================================

CREATE TABLE cheat_events (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attempt_id          UUID NOT NULL REFERENCES exam_attempts(id) ON DELETE CASCADE,
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    event_type          cheat_event_type NOT NULL,
    event_layer         SMALLINT NOT NULL,
    severity            VARCHAR(10) NOT NULL,
    risk_delta          SMALLINT NOT NULL,
    event_data          JSONB DEFAULT '{}'::jsonb,
    client_timestamp    TIMESTAMPTZ NOT NULL,
    server_timestamp    TIMESTAMPTZ DEFAULT NOW(),
    question_index      SMALLINT,
    auto_action         VARCHAR(30),
    reviewed_by         UUID REFERENCES users(id),
    review_decision     VARCHAR(20),
    reviewed_at         TIMESTAMPTZ
);

CREATE INDEX idx_cheat_attempt    ON cheat_events(attempt_id, server_timestamp);
CREATE INDEX idx_cheat_type       ON cheat_events(event_type, severity);
CREATE INDEX idx_cheat_unreviewed ON cheat_events(reviewed_by)
    WHERE reviewed_by IS NULL AND auto_action IS NOT NULL;
CREATE INDEX idx_cheat_recent     ON cheat_events(attempt_id, server_timestamp DESC)
    WHERE reviewed_by IS NULL;
CREATE INDEX idx_cheat_data_gin   ON cheat_events USING GIN (event_data jsonb_path_ops);

CREATE TABLE proctoring_sessions (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attempt_id              UUID UNIQUE NOT NULL REFERENCES exam_attempts(id) ON DELETE CASCADE,
    status                  VARCHAR(20) DEFAULT 'active',
    video_s3_key            VARCHAR(500),
    thumbnail_s3_key        VARCHAR(500),
    total_frames_analyzed   INT DEFAULT 0,
    face_detected_frames    INT DEFAULT 0,
    face_missing_frames     INT DEFAULT 0,
    multi_face_frames       INT DEFAULT 0,
    phone_detected_frames   INT DEFAULT 0,
    ai_risk_summary         JSONB DEFAULT '{}'::jsonb,
    started_at              TIMESTAMPTZ DEFAULT NOW(),
    ended_at                TIMESTAMPTZ
);

-- =============================================================================
-- 6. NHÓM KẾT QUẢ, PHẢN HỒI & CHỨNG CHỈ
-- =============================================================================

CREATE TABLE grading_rubrics (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    exam_id     UUID NOT NULL REFERENCES exams(id) ON DELETE CASCADE,
    name        VARCHAR(200) NOT NULL,
    criteria    JSONB NOT NULL,
    created_by  UUID NOT NULL REFERENCES users(id),
    created_at  TIMESTAMPTZ DEFAULT NOW()
);

CREATE INDEX idx_rubrics_exam ON grading_rubrics(exam_id);

CREATE TABLE attempt_feedback (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attempt_id              UUID UNIQUE NOT NULL REFERENCES exam_attempts(id) ON DELETE CASCADE,
    overall_feedback        TEXT,
    strengths               TEXT[] DEFAULT '{}',
    weaknesses              TEXT[] DEFAULT '{}',
    recommendations         TEXT[] DEFAULT '{}',
    ai_generated            BOOLEAN DEFAULT false,
    created_by              UUID REFERENCES users(id),
    created_at              TIMESTAMPTZ DEFAULT NOW(),
    visible_to_student      BOOLEAN DEFAULT false,
    visible_at              TIMESTAMPTZ
);

CREATE TABLE certificates (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attempt_id          UUID NOT NULL REFERENCES exam_attempts(id) ON DELETE RESTRICT,
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    exam_id             UUID NOT NULL REFERENCES exams(id) ON DELETE RESTRICT,
    certificate_number  VARCHAR(50) UNIQUE NOT NULL,
    issued_at           TIMESTAMPTZ DEFAULT NOW(),
    expires_at          TIMESTAMPTZ,
    score               NUMERIC(5,2) NOT NULL,
    verification_hash   BYTEA UNIQUE NOT NULL,        -- SHA-256 raw
    pdf_s3_key          VARCHAR(500),
    revoked             BOOLEAN DEFAULT false,
    revoked_reason      TEXT
);

CREATE INDEX idx_cert_user      ON certificates(user_id);
CREATE INDEX idx_cert_verify    ON certificates(verification_hash);
CREATE INDEX idx_cert_number    ON certificates(certificate_number);
CREATE INDEX idx_cert_exam_user ON certificates(exam_id, user_id);   -- cấp lại chứng chỉ

-- =============================================================================
-- 7. AUTH SERVICE MỞ RỘNG (từ auth-service-design.md mục IV)
-- =============================================================================

-- Chống tái sử dụng mật khẩu (5 lần gần nhất)
CREATE TABLE password_history (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    password_hash   VARCHAR(255) NOT NULL,
    changed_at      TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_pwd_history_user ON password_history(user_id, changed_at DESC);

-- Backup codes MFA (10 code/user, mỗi code dùng 1 lần)
-- Khuyến nghị app: hash bằng argon2id (chậm hơn SHA-256, chống brute-force nếu bảng rò rỉ).
CREATE TABLE mfa_backup_codes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash       BYTEA NOT NULL,                   -- argon2id hoặc SHA-256 raw
    used_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_mfa_backup_user ON mfa_backup_codes(user_id) WHERE used_at IS NULL;

-- Token verify email + reset password
CREATE TABLE email_verification_tokens (
    token_hash      BYTEA PRIMARY KEY,                -- SHA-256 raw
    user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    purpose         VARCHAR(20) NOT NULL,    -- 'verify_email' | 'reset_password'
    expires_at      TIMESTAMPTZ NOT NULL,
    used_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_evt_user ON email_verification_tokens(user_id, purpose);

-- Audit log quan trọng của Auth (partition theo tháng, giữ 12 tháng)
-- PK phải chứa partition key (created_at) theo yêu cầu của PostgreSQL.
CREATE TABLE audit_log_auth (
    id              UUID DEFAULT gen_random_uuid(),
    user_id         UUID,
    actor_id        UUID,                    -- ai gây ra action (admin hoặc user)
    event           VARCHAR(40) NOT NULL,    -- 'login_success','login_failed','password_changed','mfa_enabled','mfa_disabled','role_changed','account_locked'
    ip_address      INET,
    user_agent      VARCHAR(512),
    meta            JSONB DEFAULT '{}'::jsonb,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (id, created_at)
) PARTITION BY RANGE (created_at);

-- Partition ban đầu (mỗi tháng một partition, tự động tạo tiếp bằng pg_partman hoặc cron job).
CREATE TABLE audit_log_auth_y2026m04 PARTITION OF audit_log_auth
    FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
CREATE TABLE audit_log_auth_y2026m05 PARTITION OF audit_log_auth
    FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
CREATE TABLE audit_log_auth_y2026m06 PARTITION OF audit_log_auth
    FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
-- TODO: dùng pg_partman để auto-create partition tháng + DROP partition > 12 tháng.

CREATE INDEX idx_audit_auth_user  ON audit_log_auth(user_id, created_at DESC);
CREATE INDEX idx_audit_auth_event ON audit_log_auth(event, created_at DESC);

-- =============================================================================
-- 8. AI SERVICE (từ ai-service-design.md mục IV)
-- =============================================================================

-- Job tracker (generate, grade, embed, quality)
CREATE TABLE ai_jobs (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    user_id             UUID REFERENCES users(id),
    job_type            VARCHAR(30) NOT NULL,   -- 'generate_q','grade_essay','embed','quality_check',...
    status              VARCHAR(20) NOT NULL DEFAULT 'pending', -- pending|running|completed|failed|cancelled
    input_payload       JSONB NOT NULL,
    output_payload      JSONB,
    error_message       TEXT,
    model_used          VARCHAR(50),
    prompt_version      VARCHAR(20),
    input_tokens        INT,
    output_tokens       INT,
    cost_usd            NUMERIC(10,5),
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_ai_jobs_status    ON ai_jobs(status, created_at);
CREATE INDEX idx_ai_jobs_org       ON ai_jobs(org_id, job_type, created_at DESC);
CREATE INDEX idx_ai_jobs_input_gin ON ai_jobs USING GIN (input_payload jsonb_path_ops);

-- Ledger chi phí AI (chi tiết từng call LLM/embedding)
CREATE TABLE ai_cost_ledger (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    job_id              UUID REFERENCES ai_jobs(id) ON DELETE SET NULL,
    feature             VARCHAR(30) NOT NULL,
    model               VARCHAR(50),
    input_tokens        INT,
    output_tokens       INT,
    cost_usd            NUMERIC(10,5),
    created_at          TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_cost_org_date  ON ai_cost_ledger(org_id, created_at);
CREATE INDEX idx_cost_org_month ON ai_cost_ledger(org_id, date_trunc('month', created_at));  -- budget check

-- Budget tháng cho mỗi org
CREATE TABLE ai_budgets (
    org_id              UUID PRIMARY KEY REFERENCES organizations(id) ON DELETE CASCADE,
    monthly_limit_usd   NUMERIC(10,2) NOT NULL,
    current_month_usd   NUMERIC(10,2) DEFAULT 0,
    current_month       DATE,
    hard_stop           BOOLEAN DEFAULT true,    -- true = block khi vượt; false = cảnh báo
    updated_at          TIMESTAMPTZ DEFAULT NOW()
);

-- =============================================================================
-- 9. CHEATING DETECTION SERVICE (từ cheating-detection-service-design.md mục V)
-- =============================================================================

-- Review queue cho proctor
CREATE TABLE cheat_review_queue (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attempt_id              UUID NOT NULL REFERENCES exam_attempts(id) ON DELETE CASCADE,
    triggered_by_event      UUID REFERENCES cheat_events(id) ON DELETE SET NULL,
    risk_score_at_trigger   SMALLINT NOT NULL,
    severity                VARCHAR(10) NOT NULL,  -- low|medium|high|critical
    assigned_to             UUID REFERENCES users(id),
    status                  VARCHAR(20) DEFAULT 'pending', -- pending|in_review|resolved|escalated
    decision                VARCHAR(20),            -- confirmed|dismissed|escalated
    decision_reason         TEXT,
    reviewed_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_review_pending ON cheat_review_queue(status, severity, created_at)
    WHERE status IN ('pending','in_review');
CREATE INDEX idx_review_assignee ON cheat_review_queue(assigned_to, status)
    WHERE status IN ('pending','in_review');

-- Appeal từ học sinh sau khi bị confirmed cheating
CREATE TABLE cheat_appeals (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    attempt_id          UUID NOT NULL REFERENCES exam_attempts(id) ON DELETE RESTRICT,
    user_id             UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    reason              TEXT NOT NULL,
    evidence_s3_keys    TEXT[] DEFAULT '{}',
    status              VARCHAR(20) DEFAULT 'pending',  -- pending|under_review|resolved
    reviewed_by         UUID REFERENCES users(id),
    decision            VARCHAR(20),    -- upheld|overturned
    decision_reason     TEXT,
    created_at          TIMESTAMPTZ DEFAULT NOW(),
    resolved_at         TIMESTAMPTZ
);
CREATE INDEX idx_appeal_user   ON cheat_appeals(user_id, created_at DESC);
CREATE INDEX idx_appeal_status ON cheat_appeals(status, created_at) WHERE status != 'resolved';

-- =============================================================================
-- 10. TRIGGER CẬP NHẬT updated_at TỰ ĐỘNG
-- =============================================================================
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = NOW();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_updated
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_exams_updated
    BEFORE UPDATE ON exams
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- =============================================================================
-- 11. ROLES & GRANTS (TỐI THIỂU CHO MÔI TRƯỜNG LOCAL)
-- =============================================================================
-- Khi chạy lần đầu, có thể bỏ qua phần này.
-- CREATE ROLE svc_auth      LOGIN PASSWORD 'auth_pass'      CONNECTION LIMIT 50;
-- CREATE ROLE svc_exam      LOGIN PASSWORD 'exam_pass'      CONNECTION LIMIT 200;
-- CREATE ROLE svc_cheat     LOGIN PASSWORD 'cheat_pass'     CONNECTION LIMIT 100;
-- CREATE ROLE svc_analytics LOGIN PASSWORD 'analytics_pass' CONNECTION LIMIT 20;
-- CREATE ROLE svc_readonly  LOGIN PASSWORD 'readonly_pass'  CONNECTION LIMIT 50;

-- =============================================================================
-- END OF SCHEMA
-- =============================================================================
