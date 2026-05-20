# Redis — SmartQuizSystem (DATN)

> **Image**: `redis:7-alpine` · **Persistence**: AOF `appendfsync=everysec` + RDB · **1 instance**

Redis là supporting infra cho session, cache, WS pubsub, rate-limit, outbox lock.
**Không phải nguồn truth cho đáp án thi** — PG + transactional outbox là nguồn
truth (RPO ≤30s). Redis chỉ là cache write-through.

DATN scope dùng **1 Redis instance duy nhất** (production tách 2 cluster hot/cache,
xem future work).

## 1. Files

| File          | Mô tả                                                                |
| ------------- | -------------------------------------------------------------------- |
| `schema.md`   | Key pattern reference — mọi key DATN dùng phải có trong file này.     |
| `seed.redis`  | Seed dữ liệu mẫu (1 session thi đang chạy + vài cache key).           |

## 2. Key layout (tóm tắt)

| Key pattern                       | TTL              | Mục đích                                              |
| --------------------------------- | ---------------- | ----------------------------------------------------- |
| `jwks:cache:{service}`            | 1h               | Cache JWKS tại mỗi service consumer                   |
| `session:ws:{attempt_id}`         | thời gian thi    | Mapping attempt → WS session id (push từ consumer)    |
| `attempt:timer:{attempt_id}`      | thời gian thi    | Server-authoritative remaining time                   |
| `rate:login:{ip}`                 | 1m               | Rate limit login (10/min)                             |
| `rate:api:{user_id}`              | 1m               | Rate limit API (100/min)                              |
| `lock:outbox:{service}`           | 30s              | Leader election cho outbox relayer (renew 10s/lần)    |
| `cache:question:{id}:v{version}`  | 10m              | Cache question content (reduce PG read khi đang thi)  |

Chi tiết kiểu dữ liệu + ghi chú trong [`schema.md`](./schema.md).

## 3. Khởi tạo

```bash
docker compose -f infra/docker-compose.dev.yml up -d redis

# Seed (optional, chỉ cần khi muốn test session resume)
docker exec -i sq-redis redis-cli < database/redis/seed.redis

# Verify
docker exec sq-redis redis-cli DBSIZE
```

## 4. Connection string

```
redis://localhost:6379/0
```

## 5. GUI client

- **RedisInsight** (official, free)
- **Another Redis Desktop Manager**

## 6. Debug commands

```bash
redis-cli --scan --pattern 'session:*'
redis-cli TTL attempt:timer:<uuid>
redis-cli HGETALL session:ws:<uuid>
redis-cli MONITOR              # realtime traffic
redis-cli INFO memory
```
