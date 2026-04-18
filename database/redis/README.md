# Redis 7 - Setup Local

## Files

| File | Mô tả |
| ---- | ----- |
| `schema.md`   | **Tài liệu key pattern** (không phải SQL schema) - reference cho dev |
| `seed.redis`  | Script seed dữ liệu mẫu (session, cache, leaderboard...) |

## Tổng hợp Key Pattern

Redis không có "schema" như SQL. Hệ thống dùng 5 nhóm key:

| Nhóm | Mục đích | Kiểu dữ liệu chính | Persistence |
| ---- | -------- | ------------------ | ----------- |
| 1. Session thi | `session:*`, `answers:*`, `q_order:*`, `adaptive:*` | Hash, List | **AOF fsync=always** (bắt buộc) |
| 2. Cache nóng | `exam:config:*`, `question:*`, `user:profile:*` | String, Hash | RDB 5 phút đủ |
| 3. Rate limit | `rate:login:*`, `rate:api:*`, `otp:*` | String counter | RDB |
| 4. Pub/Sub | `ws:exam:*`, `cheat:alert:*`, `notification:*` | Channel / Stream | không persist |
| 5. Leaderboard | `leaderboard:*`, `exam:stats:*` | Sorted Set, Hash | RDB |

Đọc chi tiết trong [`schema.md`](./schema.md).

---

## Cách 1: Docker

```bash
docker compose up -d redis

# Seed
docker exec -i sq_redis redis-cli < seed.redis

# Test
docker exec -it sq_redis redis-cli DBSIZE
docker exec -it sq_redis redis-cli HGETALL session:f0000000-0000-0000-0000-000000000003
```

## Cách 2: Native Windows

Redis không có build chính thức cho Windows. Có 3 options:

### Option A - Dùng WSL2 (KHUYẾN NGHỊ)
```bash
# Trong WSL Ubuntu:
sudo apt update && sudo apt install redis-server
sudo service redis-server start
```

### Option B - Memurai (Redis-compatible cho Windows)
- Download: https://www.memurai.com/
- Cài như service, port 6379 mặc định

### Option C - Microsoft's unmaintained Redis port
- https://github.com/microsoftarchive/redis/releases (outdated, không nên dùng production)

### Seed dữ liệu
```bash
cd D:\SmartQuizSystem\database\redis
redis-cli -h localhost -p 6379 < seed.redis
```

---

## Chuỗi kết nối

```
redis://localhost:6379/0
```

## GUI Client

- **RedisInsight** (official, free): https://redis.com/redis-enterprise/redis-insight/
- **Another Redis Desktop Manager**: https://github.com/qishibo/AnotherRedisDesktopManager

## Lệnh debug hữu ích

```bash
# Monitor realtime traffic
redis-cli MONITOR

# Scan key theo pattern (an toàn hơn KEYS)
redis-cli --scan --pattern 'session:*'

# Xem memory usage
redis-cli INFO memory

# Xem TTL của key
redis-cli TTL session:abc123

# Pub/Sub subscribe
redis-cli SUBSCRIBE notification:user123
```

## Cấu hình persistence production

Trong `docker-compose.yml` hiện đang dùng `appendfsync everysec` (compromise dev). Production cho Nhóm 1 (session):

```conf
appendonly yes
appendfsync always         # BẮT BUỘC cho session data
auto-aof-rewrite-percentage 100
auto-aof-rewrite-min-size 64mb
```

Nếu tách 2 instance (session vs cache), chỉ instance session mới cần `fsync=always`.
