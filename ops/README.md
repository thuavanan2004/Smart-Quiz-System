# ops/ — scripts vận hành (DATN)

Các script hỗ trợ setup + chạy stack DATN.

## Nội dung

| File                                    | Mục đích                                                      |
| --------------------------------------- | ------------------------------------------------------------- |
| `gen-jwt-keypair.sh`                    | Sinh RSA 2048 keypair cho Auth service (1 lần lúc setup)      |
| `jwt/`                                  | Thư mục chứa keypair sinh ra (`private.pem`, `public.pem`) — gitignored |
| `llm-api-keys.example.env`              | Template env cho AI service (LLM API key, budget, flags)      |
| `llm-api-keys.env`                      | (Bạn tự tạo từ template, chứa key thật) — **KHÔNG commit**    |
| `kafka/create-topics.sh`                | Tạo 10 Kafka topic sau khi broker healthy (idempotent)        |

## Quickstart lần đầu

```bash
# 1. Sinh JWT keypair (1 lần)
bash ops/gen-jwt-keypair.sh

# 2. Copy env template + điền key
cp ops/llm-api-keys.example.env ops/llm-api-keys.env
# mở ops/llm-api-keys.env, điền GEMINI_API_KEY hoặc ANTHROPIC_API_KEY

# 3. Bật infra
docker compose -f infra/docker-compose.dev.yml up -d

# 4. Tạo Kafka topic (đợi ~15s cho Kafka ready)
bash ops/kafka/create-topics.sh
```

Sau đó: init schema/seed PG (xem `database/README.md`), rồi chạy các service.

## Lưu ý bảo mật

- `ops/jwt/private.pem`, `ops/llm-api-keys.env` **không được commit**.
- `.gitignore` trong `ops/jwt/` đã chặn sẵn.
- `ops/llm-api-keys.env` được `.gitignore` root chặn (xem gitignore).
