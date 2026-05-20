# PostgreSQL — SmartQuizSystem (DATN)

> **Image**: `pgvector/pgvector:pg16` · **Encoding**: UTF-8 · **Timezone**: UTC

`schema.sql` là **single source of truth** cho DDL DATN. `seed.sql` là dữ liệu
mẫu để smoke test (1 admin + 2 teacher + 5 student + 1 exam).

## 1. Files

| File         | Mô tả                                                                                       |
| ------------ | ------------------------------------------------------------------------------------------- |
| `schema.sql` | Toàn bộ DDL: extensions, 3 schema (auth/core/proctoring), ENUM, bảng, FK, index, view, outbox. |
| `seed.sql`   | Dữ liệu mẫu cho dev local. Password chung `Password123!` (BCrypt cost 12).                  |

## 2. Schema ownership

| Schema       | Owner service | Service khác truy cập                                                      |
| ------------ | ------------- | -------------------------------------------------------------------------- |
| `auth`       | Auth          | Không — service khác verify JWT qua JWKS, không đọc DB                     |
| `core`       | Core          | AI đọc `documents.text_content`, `questions.embedding` (role `ai_reader`)  |
| `proctoring` | Proctoring    | Core query read-only `cheat_alerts` cho teacher UI                         |

**DB role** (gợi ý — implement khi scaffold service):

- `auth_app` — full trên `auth.*`
- `core_app` — full trên `core.*`
- `proctoring_app` — full trên `proctoring.*`
- `ai_reader` — SELECT `core.documents`, `core.questions`, `core.student_writing_profiles`; INSERT/UPDATE `core.ai_cache`; UPDATE `core.student_writing_profiles`.

## 3. Danh sách bảng

| Schema       | Bảng                            | Rows demo ước tính |
| ------------ | ------------------------------- | ------------------ |
| `auth`       | `users`, `refresh_tokens`       | ~100 / ~500 rolling |
| `core`       | `questions`                     | ~500 (gồm AI-gen)  |
| `core`       | `exams`, `exam_questions`, `exam_assignments` | ~30 / ~600 / ~300 |
| `core`       | `exam_attempts`, `attempt_answers` | ~500 / ~10000   |
| `core`       | `documents`, `question_generation_jobs` | ~20 / ~50  |
| `core`       | `student_writing_profiles`, `ai_cache` | ~80 / ~2000 |
| `core`       | `outbox`, `processed_events`    | rolling            |
| `proctoring` | `cheat_events`, `cheat_alerts`, `proctoring_sessions` | ~20000 / ~300 / ~500 |

Tổng < 100MB DB size cho demo đầy đủ. PG 1 instance dư sức.

## 4. Khởi tạo nhanh (Docker)

```bash
# Bật stack infra
docker compose -f infra/docker-compose.dev.yml up -d

# Init schema + seed (1 lần sau khi PG container healthy)
docker exec -i sq-postgres psql -U postgres -d smartquiz < database/postgresql/schema.sql
docker exec -i sq-postgres psql -U postgres -d smartquiz < database/postgresql/seed.sql

# Verify
docker exec sq-postgres psql -U postgres -d smartquiz -c "SELECT count(*) FROM auth.users;"
```

## 5. Native Postgres (không Docker)

```bash
# Tạo DB
psql -U postgres -c "CREATE DATABASE smartquiz;"

# Init
psql -U postgres -d smartquiz -f database/postgresql/schema.sql
psql -U postgres -d smartquiz -f database/postgresql/seed.sql
```

Yêu cầu PG 16 + pgvector extension. Trên Windows dùng installer chính thức từ
postgresql.org rồi `CREATE EXTENSION vector` (cần build pgvector hoặc dùng image
Docker cho gọn).

## 6. Migration workflow (khi service Spring Boot chạy)

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

Flyway config per service: `spring.flyway.schemas={auth|core|proctoring}`,
`default-schema=${service}`.

`schema.sql` ở repo này là **baseline cho dev/demo**. Khi service thật chạy
Flyway, **không sửa trực tiếp `schema.sql`** — tạo migration mới trong thư mục
service tương ứng.

## 7. Backup (DATN scope)

- Dev/demo: `pg_dump -Fc smartquiz > backup-$(date +%F).dump` thỉnh thoảng.
- Không cần PITR, không cần replica.

## 8. Connection string

```
postgresql://postgres:postgres@localhost:5432/smartquiz
```

## 9. GUI client

- **pgAdmin 4** (official)
- **DBeaver** (miễn phí)
- **TablePlus** (Windows/Mac, có trial)

## 10. Query mẫu

```sql
-- Exam đang publish
SELECT id, title, status, open_at, close_at
FROM core.exams
WHERE status = 'PUBLISHED';

-- Attempt của 1 student
SELECT e.title, a.status, a.total_score
FROM core.exam_attempts a
JOIN core.exams e ON a.exam_id = e.id
WHERE a.student_id = '<uuid>';

-- Top score 1 exam (thay leaderboard)
SELECT u.full_name, a.total_score
FROM core.exam_attempts a
JOIN auth.users u ON a.student_id = u.id
WHERE a.exam_id = '<uuid>' AND a.status = 'GRADED'
ORDER BY a.total_score DESC
LIMIT 10;

-- Câu hỏi tương tự (pgvector)
SELECT id, content->>'stem' AS stem,
       1 - (embedding <=> '<query_vector>') AS similarity
FROM core.questions
ORDER BY embedding <=> '<query_vector>'
LIMIT 5;
```
