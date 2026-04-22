# ClickHouse 23.x - Setup Local

## Files

| File | Mô tả |
| ---- | ----- |
| `schema.sql` | 6 bảng fact + 5 materialized view |
| `seed.sql`   | 30 ngày dữ liệu mẫu (5 attempts, 14 answers, 6 cheat events) |

## Tổng hợp Schema

**Database:** `smartquiz_analytics`

**6 bảng chính (MergeTree, partition theo tháng):**

| Bảng | Mô tả | ORDER BY |
| ---- | ----- | -------- |
| `exam_facts` | Fact table: mỗi lượt thi hoàn thành | `(org_id, exam_id, started_at)` |
| `answer_analytics` | Chi tiết từng câu trả lời | `(org_id, question_ref_id, date)` |
| `cheat_analytics` | Sự kiện gian lận | `(org_id, exam_id, occurred_at)` |
| `user_activity_facts` | Page view, click, session activity | `(org_id, user_id, ts)` |
| `question_feedback_facts` | Rating/report câu hỏi | `(org_id, question_ref_id, ts)` |
| `experiment_exposures` | A/B experiment exposure tracking | `(experiment_id, user_id)` |

**5 materialized view (auto-aggregate):**

| View | Mục đích |
| ---- | -------- |
| `mv_exam_daily_stats` | Thống kê bài thi theo ngày (dashboard giáo viên) |
| `mv_question_stats` | Tỷ lệ đúng / thời gian / correlation → calibrate IRT |
| `mv_cheat_weekly` | Xu hướng gian lận theo tuần |
| `mv_student_progress` | Tiến độ học tập theo môn (Analytics Service) |
| `mv_user_first_attempt` | Cohort month của user (từ first attempt) |

> **Production:** ClickHouse chỉ nên ghi qua Kafka Engine (CDC từ PostgreSQL qua Debezium). Ở local, seed trực tiếp bằng `INSERT` để test.

---

## Cách 1: Docker

```bash
docker compose up -d clickhouse

# Kiểm tra
docker exec -it sq_clickhouse clickhouse-client --query "SHOW TABLES FROM smartquiz_analytics"

# Shell tương tác
docker exec -it sq_clickhouse clickhouse-client
```

## Cách 2: Native Windows

ClickHouse không hỗ trợ native Windows (chỉ Linux/macOS). Các cách thay thế:

### Option A - WSL2 (KHUYẾN NGHỊ)
```bash
# Trong WSL Ubuntu:
sudo apt-get install -y apt-transport-https ca-certificates dirmngr
sudo apt-key adv --keyserver hkp://keyserver.ubuntu.com:80 --recv E0C56BD4
echo "deb https://packages.clickhouse.com/deb stable main" | sudo tee /etc/apt/sources.list.d/clickhouse.list
sudo apt-get update
sudo apt-get install -y clickhouse-server clickhouse-client

sudo service clickhouse-server start
```

### Option B - Docker Desktop (không cần `docker compose`, chạy container riêng)
```bash
docker run -d --name clickhouse -p 8123:8123 -p 9000:9000 \
  --ulimit nofile=262144:262144 clickhouse/clickhouse-server:23.8
```

### Seed dữ liệu
```bash
cd D:\SmartQuizSystem\database\clickhouse

# Nếu trong Docker:
docker exec -i sq_clickhouse clickhouse-client --multiquery < schema.sql
docker exec -i sq_clickhouse clickhouse-client --multiquery < seed.sql

# Nếu native:
clickhouse-client --multiquery < schema.sql
clickhouse-client --multiquery < seed.sql
```

---

## Kết nối

| Giao thức | URL |
| --------- | --- |
| HTTP      | `http://localhost:8123` |
| Native    | `tcp://default@localhost:9000` |
| JDBC      | `jdbc:clickhouse://localhost:8123/smartquiz_analytics` |

## GUI Client

- **DBeaver** (ClickHouse plugin)
- **Tabix** (web UI): https://tabix.io/
- **ClickHouse Cloud Console** (nếu dùng cloud)

## Query mẫu

```sql
-- Kết nối & chọn DB
USE smartquiz_analytics;

-- Thống kê bài thi theo ngày (từ materialized view)
SELECT date, total_attempts, avg_score, passed_count, flagged_count
FROM mv_exam_daily_stats
WHERE exam_id = 'c0000000-0000-0000-0000-000000000001'
ORDER BY date DESC LIMIT 30;

-- Top câu hỏi khó nhất (correct_rate thấp)
SELECT question_ref_id, total_responses, correct_rate, avg_time_sec
FROM mv_question_stats
WHERE total_responses >= 10
ORDER BY correct_rate ASC LIMIT 20;

-- Gian lận theo giờ trong ngày
SELECT toHour(occurred_at) AS hour, count() AS events
FROM cheat_analytics
GROUP BY hour ORDER BY hour;

-- Tỷ lệ pass theo subject
SELECT subject_code,
       round(avg(passed) * 100, 2) AS pass_rate,
       count() AS attempts
FROM exam_facts
GROUP BY subject_code;
```

## CDC Pipeline (production)

Ở local không có Kafka. Production flow:

```
PostgreSQL WAL
   ↓ Debezium connector
Kafka topic: quiz.public.exam_attempts
   ↓ ClickHouse Kafka Engine table
exam_facts (via materialized view)
```

Schema Kafka Engine đã comment sẵn cuối `schema.sql`. Uncomment khi có Kafka.
