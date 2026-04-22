# SmartQuizSystem — Database (DATN)

> Scope: **1 PostgreSQL** image `pgvector/pgvector:pg16`, **4 schema** tương ứng 4 service.
> Redis (cache + session + WS pubsub), Kafka (broker + topic) là supporting infra.
> Không có MongoDB / ClickHouse / Elasticsearch trong DATN (xem archive).

## 1. PG — cấu hình chung

- Image: `pgvector/pgvector:pg16`
- Extensions cần enable:
  ```sql
  CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
  CREATE EXTENSION IF NOT EXISTS pgcrypto;
  CREATE EXTENSION IF NOT EXISTS vector;
  ```
- Encoding: UTF-8; timezone: UTC.
- Migration: **Flyway** per service (mỗi service có `db/migration/V<ts>__<desc>.sql`).
- Naming: snake_case, singular noun cho entity chính (`user`, `exam`), plural cho bảng link (`exam_questions`).

## 2. Schema ownership

| Schema       | Owner service | Truy cập từ service khác                                |
| ------------ | ------------- | ------------------------------------------------------- |
| `auth`       | Auth          | Không — service khác verify JWT qua JWKS, không đọc DB  |
| `core`       | Core          | AI đọc `documents.text_content`, `questions.embedding` (read-only role) |
| `proctoring` | Proctoring    | Core query read-only `cheat_alerts` cho teacher UI      |
| `public`     | —             | Shared extension, không có bảng                         |

**DB role**:
- `auth_app` — full trên `auth.*`
- `core_app` — full trên `core.*`
- `proctoring_app` — full trên `proctoring.*`
- `ai_reader` — SELECT trên `core.documents`, `core.questions`, `core.student_writing_profiles`; INSERT/UPDATE `core.ai_cache`; UPDATE `core.student_writing_profiles`.

## 3. Schema `auth`

```sql
CREATE TABLE auth.users (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    email           CITEXT UNIQUE NOT NULL,
    password_hash   TEXT NOT NULL,           -- BCrypt cost 12
    full_name       TEXT NOT NULL,
    role            VARCHAR(16) NOT NULL CHECK (role IN ('STUDENT','TEACHER','ADMIN')),
    is_active       BOOLEAN NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE auth.refresh_tokens (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    user_id         UUID NOT NULL REFERENCES auth.users(id) ON DELETE CASCADE,
    token_hash      TEXT NOT NULL,           -- sha256(raw token)
    issued_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at      TIMESTAMPTZ NOT NULL,
    revoked_at      TIMESTAMPTZ,
    replaced_by     UUID REFERENCES auth.refresh_tokens(id),
    user_agent      TEXT,
    ip              INET
);
CREATE INDEX ix_refresh_tokens_user ON auth.refresh_tokens(user_id) WHERE revoked_at IS NULL;
CREATE UNIQUE INDEX ux_refresh_tokens_hash ON auth.refresh_tokens(token_hash);
```

**Ghi chú**:
- Không có `org_id` (single-tenant).
- Không có bảng `roles` / `permissions` riêng — role là enum string trong `users`.
- JWKS keypair đọc từ file `ops/jwt/private.pem` + `public.pem`, không lưu DB.

## 4. Schema `core`

### 4.1. Exam + Question

```sql
CREATE TYPE core.question_type AS ENUM ('MCQ_SINGLE','MCQ_MULTI','TRUE_FALSE','ESSAY');
CREATE TYPE core.difficulty   AS ENUM ('EASY','MEDIUM','HARD');

CREATE TABLE core.questions (
    id                  UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    type                core.question_type NOT NULL,
    difficulty          core.difficulty NOT NULL DEFAULT 'MEDIUM',
    content             JSONB NOT NULL,          -- {stem, options[], correct_answer, rubric?}
    metadata            JSONB NOT NULL DEFAULT '{}',  -- {topic, tags[], bloom_level}
    version             INT NOT NULL DEFAULT 1,
    ai_generated        BOOLEAN NOT NULL DEFAULT false,
    source_document_id  UUID REFERENCES core.documents(id),
    embedding           vector(384),             -- sentence-transformers MiniLM
    created_by          UUID NOT NULL,           -- users.id
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_questions_type      ON core.questions(type);
CREATE INDEX ix_questions_created   ON core.questions(created_by);
CREATE INDEX ix_questions_embedding ON core.questions USING hnsw (embedding vector_cosine_ops);

CREATE TYPE core.exam_status AS ENUM ('DRAFT','PUBLISHED','ARCHIVED');

CREATE TABLE core.exams (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    title           TEXT NOT NULL,
    description     TEXT,
    duration_min    INT NOT NULL CHECK (duration_min > 0),
    total_points    NUMERIC(6,2) NOT NULL DEFAULT 100,
    pass_score      NUMERIC(6,2),
    status          core.exam_status NOT NULL DEFAULT 'DRAFT',
    open_at         TIMESTAMPTZ,
    close_at        TIMESTAMPTZ,
    shuffle_questions BOOLEAN NOT NULL DEFAULT false,
    shuffle_options BOOLEAN NOT NULL DEFAULT false,
    created_by      UUID NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ
);
CREATE INDEX ix_exams_status ON core.exams(status);

-- Snapshot câu hỏi tại thời điểm publish exam
CREATE TABLE core.exam_questions (
    exam_id         UUID NOT NULL REFERENCES core.exams(id) ON DELETE CASCADE,
    position        INT NOT NULL,
    question_id     UUID NOT NULL REFERENCES core.questions(id),
    question_version INT NOT NULL,
    points          NUMERIC(6,2) NOT NULL DEFAULT 1.0,
    snapshot        JSONB NOT NULL,              -- chốt content tại publish time
    PRIMARY KEY (exam_id, position)
);
CREATE INDEX ix_exam_questions_q ON core.exam_questions(question_id);

-- Gán exam cho student
CREATE TABLE core.exam_assignments (
    exam_id         UUID NOT NULL REFERENCES core.exams(id) ON DELETE CASCADE,
    student_id      UUID NOT NULL,               -- users.id
    assigned_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (exam_id, student_id)
);
```

### 4.2. Attempt + Answer

```sql
CREATE TYPE core.attempt_status AS ENUM ('IN_PROGRESS','SUSPENDED','SUBMITTED','GRADED','CANCELLED');

CREATE TABLE core.exam_attempts (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    exam_id         UUID NOT NULL REFERENCES core.exams(id),
    student_id      UUID NOT NULL,
    status          core.attempt_status NOT NULL DEFAULT 'IN_PROGRESS',
    state_version   BIGINT NOT NULL DEFAULT 0,   -- fencing token
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    submitted_at    TIMESTAMPTZ,
    graded_at       TIMESTAMPTZ,
    deadline_at     TIMESTAMPTZ NOT NULL,        -- started_at + exam.duration_min
    total_score     NUMERIC(6,2),
    suspended_reason TEXT,
    UNIQUE (exam_id, student_id)                 -- DATN: 1 attempt per exam per student
);
CREATE INDEX ix_attempts_student ON core.exam_attempts(student_id, status);
CREATE INDEX ix_attempts_exam    ON core.exam_attempts(exam_id, status);

CREATE TABLE core.attempt_answers (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    attempt_id      UUID NOT NULL REFERENCES core.exam_attempts(id) ON DELETE CASCADE,
    position        INT NOT NULL,
    question_id     UUID NOT NULL REFERENCES core.questions(id),
    answer_data     JSONB NOT NULL DEFAULT '{}', -- {selected: [...]} | {text: "..."}
    score           NUMERIC(6,2),
    feedback        TEXT,
    -- AI tutor explanation (§9.2 scope-datn)
    ai_explanation          TEXT,
    ai_explanation_status   VARCHAR(16),         -- PENDING, READY, FAILED
    -- AI essay detection (§9.3 scope-datn)
    ai_detection_score      NUMERIC(5,4),        -- 0..1
    ai_detection_method     VARCHAR(32),         -- perplexity | stylometry | hybrid
    ai_detection_details    JSONB,
    submitted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    graded_at       TIMESTAMPTZ,
    UNIQUE (attempt_id, position)
);
CREATE INDEX ix_answers_attempt ON core.attempt_answers(attempt_id);
```

### 4.3. Document + AI job (feature upload)

```sql
CREATE TYPE core.document_status AS ENUM ('UPLOADED','EXTRACTING','READY','FAILED');

CREATE TABLE core.documents (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    uploaded_by     UUID NOT NULL,
    filename        TEXT NOT NULL,
    mime_type       VARCHAR(128) NOT NULL,       -- application/pdf, application/vnd...docx
    size_bytes      BIGINT NOT NULL,
    storage_path    TEXT NOT NULL,               -- ./data/uploads/{uuid}.pdf
    text_content    TEXT,                        -- extracted text after Tika
    page_count      INT,
    status          core.document_status NOT NULL DEFAULT 'UPLOADED',
    error_message   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at    TIMESTAMPTZ
);
CREATE INDEX ix_documents_uploader ON core.documents(uploaded_by, created_at DESC);

CREATE TYPE core.gen_job_status AS ENUM ('QUEUED','RUNNING','DONE','FAILED');

CREATE TABLE core.question_generation_jobs (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    document_id     UUID REFERENCES core.documents(id),
    requested_by    UUID NOT NULL,
    config          JSONB NOT NULL,              -- {topic, difficulty, count, type}
    status          core.gen_job_status NOT NULL DEFAULT 'QUEUED',
    result_count    INT DEFAULT 0,
    error_message   TEXT,
    generated_question_ids UUID[] DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ
);
CREATE INDEX ix_gen_jobs_status ON core.question_generation_jobs(status);
```

### 4.4. Stylometry baseline + LLM cache

```sql
-- Baseline phong cách viết mỗi student (update dần)
CREATE TABLE core.student_writing_profiles (
    user_id         UUID PRIMARY KEY,
    avg_embedding   vector(384) NOT NULL,
    sample_count    INT NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Cache LLM response (giảm token cost)
CREATE TABLE core.ai_cache (
    cache_key       TEXT PRIMARY KEY,            -- sha256(prompt_type + inputs + model)
    response        JSONB NOT NULL,
    model           VARCHAR(64) NOT NULL,
    tokens_prompt   INT,
    tokens_completion INT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_hit_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    hit_count       INT NOT NULL DEFAULT 0
);
CREATE INDEX ix_ai_cache_last_hit ON core.ai_cache(last_hit_at DESC);
```

### 4.5. Outbox + processed events

```sql
CREATE TABLE core.outbox (
    id              BIGSERIAL PRIMARY KEY,
    event_id        UUID NOT NULL UNIQUE DEFAULT uuid_generate_v4(),
    topic           TEXT NOT NULL,
    aggregate_type  VARCHAR(64) NOT NULL,        -- 'Attempt','Exam','Document'...
    aggregate_id    VARCHAR(64) NOT NULL,
    partition_key   TEXT,
    payload         JSONB NOT NULL,
    headers         JSONB NOT NULL DEFAULT '{}',
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
```

### 4.6. Analytics view

Thay cho ClickHouse, dùng view trong PG. Với DATN (< 100k attempts) hiệu năng đủ.

```sql
-- Thống kê mỗi exam
CREATE VIEW core.v_exam_stats AS
SELECT
    e.id                                        AS exam_id,
    e.title,
    COUNT(a.id)                                 AS total_attempts,
    COUNT(a.id) FILTER (WHERE a.status = 'GRADED') AS graded_count,
    AVG(a.total_score)                          AS avg_score,
    percentile_cont(0.5) WITHIN GROUP (ORDER BY a.total_score) AS median_score,
    MIN(a.total_score)                          AS min_score,
    MAX(a.total_score)                          AS max_score
FROM core.exams e
LEFT JOIN core.exam_attempts a ON a.exam_id = e.id AND a.status = 'GRADED'
GROUP BY e.id, e.title;

-- Histogram điểm (bucket 10%)
CREATE VIEW core.v_score_histogram AS
SELECT
    exam_id,
    FLOOR(total_score / 10) * 10 AS bucket_start,
    COUNT(*) AS count
FROM core.exam_attempts
WHERE status = 'GRADED'
GROUP BY exam_id, bucket_start;

-- Question quality (tỉ lệ trả lời đúng)
CREATE VIEW core.v_question_quality AS
SELECT
    q.id AS question_id,
    q.type,
    COUNT(aa.id) AS answer_count,
    AVG(CASE WHEN aa.score > 0 THEN 1 ELSE 0 END)::numeric(5,4) AS pct_correct
FROM core.questions q
LEFT JOIN core.attempt_answers aa ON aa.question_id = q.id
GROUP BY q.id, q.type;
```

Nếu demo cần query nặng hơn → convert sang `MATERIALIZED VIEW` + refresh cron.

## 5. Schema `proctoring`

```sql
CREATE TYPE proctoring.event_type AS ENUM (
    'TAB_BLUR','TAB_FOCUS','PASTE','COPY','WINDOW_RESIZE',
    'FULLSCREEN_EXIT','HEARTBEAT_LOST','TIMING_ANOMALY'
);
CREATE TYPE proctoring.severity AS ENUM ('INFO','WARN','HIGH');

-- Raw events từ client (ghi lại để audit + replay)
CREATE TABLE proctoring.cheat_events (
    id              BIGSERIAL PRIMARY KEY,
    event_id        UUID NOT NULL UNIQUE,        -- từ Core WS forward
    attempt_id      UUID NOT NULL,
    student_id      UUID NOT NULL,
    event_type      proctoring.event_type NOT NULL,
    event_data      JSONB NOT NULL DEFAULT '{}',
    occurred_at     TIMESTAMPTZ NOT NULL,
    received_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_cheat_events_attempt ON proctoring.cheat_events(attempt_id, occurred_at);

-- Alert tổng hợp (gửi cho coi thi)
CREATE TABLE proctoring.cheat_alerts (
    id              UUID PRIMARY KEY DEFAULT uuid_generate_v4(),
    attempt_id      UUID NOT NULL,
    student_id      UUID NOT NULL,
    rule_name       VARCHAR(64) NOT NULL,        -- 'EXCESSIVE_TAB_BLUR','PASTE_LARGE'...
    severity        proctoring.severity NOT NULL,
    evidence        JSONB NOT NULL,              -- {count, threshold, window, sample_event_ids[]}
    reviewed_by     UUID,                        -- teacher id
    reviewed_at     TIMESTAMPTZ,
    review_note     TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ix_cheat_alerts_attempt ON proctoring.cheat_alerts(attempt_id, created_at DESC);
CREATE INDEX ix_cheat_alerts_unreviewed ON proctoring.cheat_alerts(created_at)
    WHERE reviewed_at IS NULL;

-- Session metadata (heartbeat tracking)
CREATE TABLE proctoring.proctoring_sessions (
    attempt_id      UUID PRIMARY KEY,
    started_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_heartbeat_at TIMESTAMPTZ,
    client_user_agent TEXT,
    client_ip       INET,
    ended_at        TIMESTAMPTZ
);

-- Outbox + processed events riêng cho proctoring
CREATE TABLE proctoring.outbox            (LIKE core.outbox  INCLUDING ALL);
CREATE TABLE proctoring.processed_events  (LIKE core.processed_events INCLUDING ALL);
```

## 6. Redis key layout

| Key pattern                       | TTL       | Mục đích                                              |
| --------------------------------- | --------- | ----------------------------------------------------- |
| `jwks:cache:{service}`            | 1h        | Cache JWKS tại mỗi service consumer                   |
| `session:ws:{attempt_id}`         | duration  | Mapping attempt → WS session id (cho push từ consumer)|
| `attempt:timer:{attempt_id}`      | duration  | Server-authoritative remaining time                   |
| `rate:login:{ip}`                 | 1m        | Rate limit login (10/min)                             |
| `rate:api:{user_id}`              | 1m        | Rate limit API (100/min)                              |
| `lock:outbox:{service}`           | 30s       | Leader election cho relayer (renew 10s/lần)           |
| `cache:question:{id}:v{version}`  | 10m       | Cache question content (reduce PG read khi đang thi)  |

## 7. Kafka topic config

Tất cả topic: `retention.ms=604800000` (7 ngày), `cleanup.policy=delete`, partitions=3,
replication.factor=1 (DATN single-broker), `min.insync.replicas=1`.

File cấu hình: `ops/kafka/create-topics.sh`.

## 8. Migration workflow

```
services/
├── auth/src/main/resources/db/migration/
│   ├── V20260501__init_users.sql
│   └── V20260502__refresh_tokens.sql
├── core/src/main/resources/db/migration/
│   ├── V20260501__init_core_schema.sql
│   ├── V20260502__questions_and_exams.sql
│   ├── V20260503__attempts.sql
│   ├── V20260504__documents_and_ai.sql
│   ├── V20260505__outbox.sql
│   └── V20260506__analytics_views.sql
└── proctoring/src/main/resources/db/migration/
    ├── V20260501__init_proctoring_schema.sql
    ├── V20260502__cheat_events_and_alerts.sql
    └── V20260503__outbox.sql
```

Flyway config: `spring.flyway.schemas={auth|core|proctoring}`, `default-schema=${service}`.

## 9. Backup (DATN scope)

- Dev/demo: `pg_dump -Fc smartquiz > backup-$(date +%F).dump` thỉnh thoảng.
- Không cần PITR, không cần replica.

## 10. Danh sách bảng (tóm tắt)

| Schema       | Bảng                          | Rows/demo ước tính |
| ------------ | ----------------------------- | ------------------ |
| auth         | users                         | ~100               |
| auth         | refresh_tokens                | ~500 (rolling)     |
| core         | questions                     | ~500 (incl. AI-gen)|
| core         | exams                         | ~30                |
| core         | exam_questions                | ~600               |
| core         | exam_assignments              | ~300               |
| core         | exam_attempts                 | ~500               |
| core         | attempt_answers               | ~10000             |
| core         | documents                     | ~20                |
| core         | question_generation_jobs      | ~50                |
| core         | student_writing_profiles      | ~80                |
| core         | ai_cache                      | ~2000              |
| core         | outbox                        | rolling            |
| core         | processed_events              | rolling            |
| proctoring   | cheat_events                  | ~20000             |
| proctoring   | cheat_alerts                  | ~300               |
| proctoring   | proctoring_sessions           | ~500               |

Tổng ước lượng < 100MB DB size cho demo đầy đủ. PG tay vo 1 instance dư sức.
