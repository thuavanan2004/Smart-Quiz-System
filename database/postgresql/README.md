# PostgreSQL 16 - Setup Local

## Files

| File | Mô tả |
| ---- | ----- |
| `migrations/V0001__baseline_schema.sql` | Baseline ENUM + bảng + FK + index (single source of truth, Flyway-managed) |
| `migrations/V1776521023__add_outbox_and_fencing.sql` | Outbox + fencing token (ADR-001) |
| `seed.sql`   | Dữ liệu mẫu: 3 org, 8 users, 3 exams, 3 attempts, certificates... (KHÔNG qua Flyway — chỉ dùng cho dev local) |

## Tổng hợp Schema

**28 bảng / 3 ENUM types:**

| Nhóm | Bảng |
| ---- | ---- |
| Tổ chức & Người dùng | `organizations`, `users`, `user_organizations`, `oauth_providers`, `refresh_tokens` |
| **RBAC động** | `roles`, `permissions`, `role_permissions` (thay cho enum `user_role` cũ) |
| Bài thi & Cấu hình | `subjects`, `exams`, `exam_sections`, `exam_questions`, `exam_enrollments` |
| Lượt thi | `exam_attempts`, `attempt_answers` |
| Chống gian lận | `cheat_events`, `proctoring_sessions` |
| Kết quả | `grading_rubrics`, `attempt_feedback`, `certificates` |
| **Auth mở rộng** | `password_history`, `mfa_backup_codes`, `email_verification_tokens`, `audit_log_auth` |
| **AI Service** | `ai_jobs`, `ai_cost_ledger`, `ai_budgets` |
| **Cheating mở rộng** | `cheat_review_queue`, `cheat_appeals` |

**ENUM:** `exam_status`, `attempt_status`, `cheat_event_type` (đã remove `user_role` — chuyển sang bảng `roles`)

**Extensions:** `pgcrypto` (UUID), `citext`, `pg_trgm`

---

## Cách 1: Docker (nhanh nhất)

```bash
# Từ thư mục database/
docker compose up -d postgres

# Kiểm tra
docker exec -it sq_postgres psql -U postgres -d smartquiz -c "\dt"
```

`migrations/V0001__baseline_schema.sql` + `seed.sql` đã được mount vào initdb → chạy tự động lần đầu. Các migration sau (V1776521023…) do Flyway chạy khi service boot.

## Cách 2: Cài native trên Windows

### Bước 1 - Download & Install

- Tải installer: https://www.postgresql.org/download/windows/
- Version: **PostgreSQL 16**
- Khi cài: đặt password cho user `postgres`, giữ port `5432`

### Bước 2 - Tạo database

```bash
# Mở PowerShell / CMD
psql -U postgres

# Trong psql shell:
CREATE DATABASE smartquiz;
\c smartquiz
\q
```

### Bước 3 - Chạy schema + seed

```bash
cd D:\SmartQuizSystem\database\postgresql

psql -U postgres -d smartquiz -f migrations/V0001__baseline_schema.sql
psql -U postgres -d smartquiz -f migrations/V1776521023__add_outbox_and_fencing.sql
psql -U postgres -d smartquiz -f seed.sql
```

### Bước 4 - Verify

```bash
psql -U postgres -d smartquiz -c "SELECT name FROM organizations;"
psql -U postgres -d smartquiz -c "SELECT email FROM users;"
```

---

## Tài khoản seed (dùng để test app)

| Email | Role | Org |
| ----- | ---- | --- |
| `admin@smartquiz.vn` | admin | HUST |
| `gv.nguyen@hust.edu.vn` | instructor | HUST |
| `gv.tran@hust.edu.vn` | instructor | HUST |
| `hs.le@hust.edu.vn` | student | HUST |
| `hs.pham@hust.edu.vn` | student | HUST |
| `hs.hoang@hust.edu.vn` | student | HUST |
| `proctor@hust.edu.vn` | proctor | HUST |
| `teacher@global.vn` | instructor | Global English |

> Password hash trong seed là **giả (mock)**. Khi phát triển auth service, hãy UPDATE lại password hash thật bằng Argon2id.

---

## Chuỗi kết nối

```
postgresql://postgres:postgres@localhost:5432/smartquiz
```

## GUI Client khuyến nghị

- **pgAdmin 4** (official)
- **DBeaver** (miễn phí, đa DB)
- **TablePlus** (Windows/Mac, có trial)

## Tham khảo query

```sql
-- Bài thi đang mở
SELECT id, title, status, starts_at FROM exams WHERE status IN ('published', 'active');

-- Lượt thi của 1 user
SELECT e.title, a.status, a.percentage_score, a.passed
FROM exam_attempts a
JOIN exams e ON a.exam_id = e.id
WHERE a.user_id = 'a0000000-0000-0000-0000-000000000004';

-- Leaderboard
SELECT u.full_name, a.percentage_score
FROM exam_attempts a
JOIN users u ON a.user_id = u.id
WHERE a.exam_id = 'c0000000-0000-0000-0000-000000000001'
  AND a.status = 'graded'
ORDER BY a.percentage_score DESC;
```
