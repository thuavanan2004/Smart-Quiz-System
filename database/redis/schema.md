# Redis — Key Schema (DATN)

> Tham chiếu key pattern dùng trong DATN. **1 Redis instance**, no clustering.
> Persistence: AOF `appendfsync=everysec` + RDB. Nguồn truth cho đáp án thi luôn
> là PG + transactional outbox — Redis chỉ là cache write-through.

## 1. Auth / JWT

| Key                                | Kiểu   | TTL | Nội dung                                                  |
| ---------------------------------- | ------ | --- | --------------------------------------------------------- |
| `jwks:cache:{service}`             | String | 1h  | JWKS JSON đã fetch từ Auth (mỗi service cache local)      |
| `token:blacklist:{jti}`            | String | = TTL access token | Marker token bị revoke (logout sớm) |

## 2. Session thi (WebSocket + timer)

| Key                                | Kiểu   | TTL                | Nội dung                                                  |
| ---------------------------------- | ------ | ------------------ | --------------------------------------------------------- |
| `session:ws:{attempt_id}`          | String | thời gian thi      | WS session id để consumer push event tới đúng socket      |
| `attempt:timer:{attempt_id}`       | String | thời gian thi      | Remaining seconds (server-authoritative, push mỗi 30s)    |
| `attempt:heartbeat:{attempt_id}`   | String | 30s (renew)        | Marker client còn online                                  |

> Đáp án thi **không** lưu Redis — ghi thẳng PG + outbox trong cùng transaction
> để đảm bảo RPO ≤30s.

## 3. Rate limit + lock

| Key                                | Kiểu      | TTL   | Nội dung                                                |
| ---------------------------------- | --------- | ----- | ------------------------------------------------------- |
| `rate:login:{ip}`                  | String    | 1m    | Counter login fail (10/min)                             |
| `rate:api:{user_id}`               | String    | 1m    | Counter API (100/min)                                   |
| `lock:outbox:{service}`            | String NX | 30s   | Leader election cho outbox relayer (1 process poll)     |
| `lock:exam_start:{attempt_id}`     | String NX | 10s   | Chống race khi student bấm Start 2 tab cùng lúc         |

## 4. Cache đọc nóng

| Key                                | Kiểu   | TTL   | Nội dung                                                  |
| ---------------------------------- | ------ | ----- | --------------------------------------------------------- |
| `cache:question:{id}:v{version}`   | String | 10m   | JSON câu hỏi (giảm PG read khi đang thi)                  |
| `cache:exam:{id}`                  | String | 30m   | JSON exam config — invalidate khi sửa exam                |
| `cache:user:{id}`                  | String | 15m   | Profile user (cho hiển thị tên ở UI)                      |

## 5. WebSocket pub/sub

| Channel                            | Mục đích                              | Subscriber          |
| ---------------------------------- | ------------------------------------- | ------------------- |
| `ws:exam:{attempt_id}`             | Push event tới WS session             | Core WS handler     |
| `ws:cheat:{attempt_id}`            | Cheat alert từ Proctoring → Core      | Core WS handler     |

## 6. Debug

```bash
redis-cli --scan --pattern 'session:*'
redis-cli TTL attempt:timer:<uuid>
redis-cli HGETALL session:ws:<uuid>
redis-cli SUBSCRIBE 'ws:exam:*'        # quan sát push event
redis-cli MONITOR                      # toàn bộ traffic (chỉ dev)
```
