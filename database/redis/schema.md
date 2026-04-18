# REDIS 7 - KEY SCHEMA REFERENCE

File tổng hợp toàn bộ key pattern và cấu trúc dữ liệu dùng trong Redis cho Smart Quiz System.

## TOPOLOGY: 2 Cụm Redis Tách Biệt

Hệ thống dùng **2 Redis cluster riêng** để tránh eviction nhầm dữ liệu quan trọng.
Config phải đồng nhất với docker-compose / k8s manifest.

| Cụm         | Tên logical    | Persistence                 | Eviction policy | Nhóm key dùng |
| ----------- | -------------- | --------------------------- | --------------- | ------------- |
| **Hot**     | `redis-hot`    | AOF appendfsync=everysec + RDB snapshot 5m | `noeviction`    | Nhóm 1 (session thi), Nhóm 7 (risk, baseline) |
| **Cache**   | `redis-cache`  | RDB snapshot 5m (no AOF)    | `allkeys-lru`   | Nhóm 2 (cache), Nhóm 3 (rate limit, OTP), Nhóm 6 (AI cache) |

- **Hot** ưu tiên durability — mất đáp án khi đang thi là không chấp nhận. `noeviction` đảm bảo
  Redis trả lỗi OOM thay vì im lặng xoá session. Nhớ monitor memory.
- **Cache** ưu tiên throughput — có thể mất key, ứng dụng fallback đọc nguồn gốc.
- **Pub/Sub** (Nhóm 4) dùng `redis-hot` vì downtime pub/sub = mất alert real-time.
- **Leaderboard** (Nhóm 5) có thể ở `redis-cache` (rebuild từ ClickHouse được), nhưng
  `rt:exam:*:stats` do Flink update phải ở `redis-hot` để không mất giữa stream.

> Lưu ý: RPO kỳ vọng của đáp án thi là ≤ 5s — PostgreSQL + transactional outbox
> là nguồn truth, Redis chỉ là cache write-through. Xem docs/exam-service-design.md §5.3.

## Nhóm 1: Session Bài Thi (Real-Time Critical)  →  `redis-hot`
> **Persistence yêu cầu:** AOF appendfsync=everysec (hy sinh 1s mới nhất đổi lấy 10x throughput so với `always`).
> App phải dùng transactional outbox để đảm bảo RPO tổng ≤ 5s dù Redis mất 1s cuối.

| Key Pattern                 | Kiểu      | Trường (Hash field) / Nội dung                                    | TTL                       | Ghi chú |
| --------------------------- | --------- | ----------------------------------------------------------------- | ------------------------- | ------- |
| `session:{attempt_id}`      | Hash      | `remaining_seconds`, `current_q_idx`, `risk_score`, `status`, `last_heartbeat` | thời gian thi + 5 phút | Nguồn sự thật trạng thái thi |
| `answers:{attempt_id}`      | Hash      | `{question_ref_id}` → JSON đáp án                                 | thời gian thi + 2 giờ     | Buffer trước khi flush PG |
| `q_order:{attempt_id}`      | List      | danh sách UUID câu hỏi đã xáo                                     | thời gian thi + 2 giờ     | Thứ tự riêng mỗi lượt |
| `adaptive:{attempt_id}`     | Hash      | `theta`, `se`, `answered_ids` (CSV), `next_q_pool` (CSV)           | thời gian thi + 1 giờ     | Trạng thái IRT adaptive |
| `user:online:{user_id}`     | String    | `{attempt_id}` (heartbeat)                                        | 30 giây (tự gia hạn)      | Ai đang thi |
| `exam:concurrent:{exam_id}` | Set       | tập `attempt_id` đang thi                                         | hết giờ thi + 30 phút     | Đếm người thi |

## Nhóm 2: Cache Nóng  →  `redis-cache`
> **Persistence:** RDB mỗi 5 phút là đủ. Eviction `allkeys-lru` — app fallback đọc PG/Mongo nếu miss.

| Key Pattern                  | Kiểu    | Nội dung                                  | TTL     | Invalidation |
| ---------------------------- | ------- | ----------------------------------------- | ------- | ------------ |
| `exam:config:{exam_id}`      | String  | JSON cấu hình bài thi                     | 30 phút | Khi sửa bài thi |
| `exam:q_ids:{exam_id}`       | List    | danh sách `question_ref_id`               | 30 phút | Khi thêm/bỏ câu |
| `question:{q_id}:{version}`  | String  | JSON câu hỏi từ MongoDB                    | 2 giờ   | Khi sửa câu |
| `user:profile:{user_id}`     | Hash    | `name`, `email`, `role`, `org_id`          | 15 phút | Khi sửa user |
| `org:settings:{org_id}`      | String  | JSON cấu hình tổ chức                      | 1 giờ   | Khi admin sửa |
| `cert:verify:{hash}`         | String  | JSON thông tin chứng chỉ                   | 24 giờ  | Khi thu hồi |

## Nhóm 3: Rate Limit & Bảo Mật  →  `redis-cache`

| Key Pattern                       | Kiểu    | Nội dung                   | TTL                         | Ngưỡng |
| --------------------------------- | ------- | -------------------------- | --------------------------- | ------ |
| `rate:login:{ip}`                 | String  | số lần login fail (counter)| 15 phút sliding             | 10 → lock 15 phút |
| `rate:api:{user_id}:{endpoint}`   | String  | số request                 | 1 phút sliding              | 60-200/phút |
| `rate:ai_gen:{org_id}`            | String  | số câu AI sinh hôm nay     | reset midnight              | theo plan |
| `token:blacklist:{jti}`           | String  | `"revoked"`                | = TTL access token          | logout sớm |
| `otp:{user_id}:{purpose}`         | String  | mã OTP                     | 10 phút                     | xác thực |
| `lock:exam_start:{attempt_id}`    | String NX | distributed lock          | 10 giây                     | chống race |

## Nhóm 4: Pub/Sub & Stream  →  `redis-hot`

| Channel / Stream              | Loại       | Mục đích                              | Subscriber |
| ----------------------------- | ---------- | ------------------------------------- | ---------- |
| `ws:exam:{attempt_id}`        | Pub/Sub    | Đẩy tín hiệu → WebSocket              | Exam WS handler |
| `cheat:alert:{attempt_id}`    | Pub/Sub    | Cảnh báo gian lận                     | Exam + Giám thị |
| `exam:events` (stream)        | Stream     | Sự kiện trước khi vào Kafka           | Kafka bridge |
| `notification:{user_id}`      | Pub/Sub    | Thông báo real-time                   | Notification service |

## Nhóm 5: Xếp hạng & Thống kê Real-time  →  hỗn hợp
> `leaderboard:*` và `exam:stats:*` có thể ở `redis-cache` (rebuild từ ClickHouse).
> `rt:exam:*:stats` (do Flink update liên tục) nên ở `redis-hot` để không mất giữa stream.

| Key Pattern                 | Kiểu        | Nội dung                              | TTL                |
| --------------------------- | ----------- | ------------------------------------- | ------------------ |
| `leaderboard:{exam_id}`     | Sorted Set  | `user_id` → `score` (ZADD)            | 7 ngày sau kết thúc |
| `exam:stats:{exam_id}`      | Hash        | `attempt_count`, `avg_score`, `pass_count`, `fail_count` | real-time |
| `active_exams:{org_id}`     | Set         | tập `exam_id` đang mở                  | sync 5 phút |
| `rt:exam:{exam_id}:stats`   | Hash        | Flink streaming update: `active`, `avg_score`, `submitted`, `flagged` | 1s refresh |

## Nhóm 6: AI Service  →  `redis-cache`
> _(từ ai-service-design.md mục IV.3)_ — tất cả là cache không durable.

| Key Pattern                              | Kiểu        | Nội dung                                | TTL     | Dùng cho |
| ---------------------------------------- | ----------- | --------------------------------------- | ------- | -------- |
| `ai:embed:{sha256(text)}:{model}`        | String      | Vector embedding (msgpack-encoded)      | 24h     | Tránh embed lại text giống hệt |
| `ai:mod:{sha256(text)}`                  | String JSON | Kết quả moderation                      | 6h      | Cache moderation |
| `ai:job:{job_id}`                        | String JSON | Status snapshot                          | 1h      | Polling status nhanh |
| `ai:cost:{org_id}:{YYYY-MM}`             | String      | Counter USD used trong tháng            | 35 ngày | Budget tracking nhanh |
| `ai:ratelimit:{org_id}:{feature}`        | String      | Counter rate per minute                  | 60s     | Rate limit |
| `ai:processed:{message_id}`              | String      | Idempotency flag cho Kafka consumer     | 24h     | Chống double-process |

## Nhóm 7: Cheating Detection Service  →  `redis-hot`
> _(từ cheating-detection-service-design.md mục V.3)_ — `risk:{attempt_id}` là nguồn truth
> trong lúc thi nên không được evict. Baseline typing có thể ở cache nếu rebuild được.

| Key Pattern                              | Kiểu   | Nội dung                                             | TTL                  |
| ---------------------------------------- | ------ | ---------------------------------------------------- | -------------------- |
| `risk:{attempt_id}`                      | Hash   | `total`, `events` (JSON array), `last_update`        | duration + 2h        |
| `baseline:typing:{user_id}`              | Hash   | `mean_kpi`, `stddev`, `samples`                      | 30 ngày              |
| `attempt:ips:{id}`                       | Set    | IPs đã thấy trong attempt                            | duration + 1h        |
| `attempt:geo:{id}`                       | Hash   | `country`, `city`, `lat`, `lon`                      | duration + 1h        |
| `proctor:queue:{proctor_id}`             | List   | Attempt ID assigned cho proctor                      | 1h                   |
| `cooldown:{attempt_id}:{event_type}`     | String NX | Anti-spam cùng loại event                         | 2-5s                 |

## Nhóm 8: Cache AI Service (đường embedding query — đọc từ ES)

Không có key riêng, dùng lại `ai:embed:*` ở nhóm 6.

## Lệnh tham khảo hữu ích

```bash
# Xem key theo pattern (local dev, KHÔNG dùng production)
redis-cli KEYS 'session:*'

# Dùng SCAN thay KEYS trên production
redis-cli SCAN 0 MATCH 'session:*' COUNT 1000

# Xem TTL
redis-cli TTL session:f0000000-0000-0000-0000-000000000003

# Xem hash
redis-cli HGETALL session:f0000000-0000-0000-0000-000000000003

# Monitor traffic (debug)
redis-cli MONITOR

# Xem thống kê
redis-cli INFO stats
```
