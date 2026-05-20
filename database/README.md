# Database setup — SmartQuizSystem (DATN)

Scope DATN chỉ dùng **1 PostgreSQL** (image `pgvector/pgvector:pg16`) + **Redis**.
MongoDB / ClickHouse / Elasticsearch là future work, không tracked trong repo.

## Cấu trúc

```
database/
├── README.md                 ← file này
├── postgresql/
│   ├── README.md             ← chi tiết init + query mẫu
│   ├── schema.sql            ← single source of truth DDL (3 schema: auth/core/proctoring)
│   └── seed.sql              ← dữ liệu mẫu: 1 admin + 2 teacher + 5 student + 1 exam
└── redis/
    ├── README.md
    ├── schema.md             ← key layout (session, cache, rate limit, locks)
    └── seed.redis            ← optional seed
```

Docker compose dev stack: **`infra/docker-compose.dev.yml`** (PG+pgvector, Redis,
Kafka, Zookeeper). Xem `docs/design.md` §9.

## Khởi tạo nhanh

```bash
# 1. Bật stack infra
docker compose -f infra/docker-compose.dev.yml up -d

# 2. Init schema + seed (1 lần sau khi container PG đã healthy)
docker exec -i smartquiz-postgres \
    psql -U postgres -d smartquiz < database/postgresql/schema.sql
docker exec -i smartquiz-postgres \
    psql -U postgres -d smartquiz < database/postgresql/seed.sql

# 3. Verify
docker exec smartquiz-postgres \
    psql -U postgres -d smartquiz -c "SELECT count(*) FROM auth.users;"
```

## Reset

```bash
docker compose -f infra/docker-compose.dev.yml down -v
docker compose -f infra/docker-compose.dev.yml up -d
# Init lại schema + seed như trên
```

## Migration (khi code service chạy)

Mỗi service Spring Boot có Flyway riêng:

```
services/
├── auth/src/main/resources/db/migration/        V<ts>__*.sql
├── core/src/main/resources/db/migration/
└── proctoring/src/main/resources/db/migration/
```

`schema.sql` + `seed.sql` trong repo này chỉ dùng để init nhanh cho demo/test.
Production flow đi qua Flyway per service.

## Tham chiếu

- `database/postgresql/README.md` — chi tiết schema + role grants + migration workflow.
- `database/postgresql/schema.sql` — DDL (single source of truth).
