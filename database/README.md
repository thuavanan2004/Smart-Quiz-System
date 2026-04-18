# Smart Quiz System - Database Setup

Setup đầy đủ 5 databases theo thiết kế `database.md` để chạy local development.

## Cấu trúc thư mục

```
database/
├── README.md               ← File này (tổng quan)
├── docker-compose.yml      ← Stack all-in-one (5 DBs + Kibana)
├── postgresql/
│   ├── README.md           ← Hướng dẫn riêng PostgreSQL
│   ├── schema.sql          ← Tables, enums, indexes
│   └── seed.sql            ← Dữ liệu mẫu
├── mongodb/
│   ├── README.md
│   ├── schema.js
│   └── seed.js
├── redis/
│   ├── README.md
│   ├── schema.md           ← Key pattern reference
│   └── seed.redis
├── clickhouse/
│   ├── README.md
│   ├── schema.sql
│   └── seed.sql
└── elasticsearch/
    ├── README.md
    ├── schema.json
    └── seed.ndjson
```

## Tổng quan 5 Databases

| DB                | Vai trò                          | Cổng local       | Volume         | Login mặc định |
| ----------------- | -------------------------------- | ---------------- | -------------- | -------------- |
| **PostgreSQL 16** | Source of truth (auth, thi, KQ)  | `5432`           | `pgdata`       | `postgres / postgres` |
| **MongoDB 7**     | Ngân hàng câu hỏi                 | `27017`          | `mongodata`    | `root / root` |
| **Redis 7**       | Session, cache, pub/sub           | `6379`           | `redisdata`    | không mật khẩu |
| **ClickHouse**    | OLAP analytics                    | `8123` / `9000`  | `chdata`       | `default` (rỗng) |
| **Elasticsearch 8**| Tìm kiếm text + vector          | `9200` / `9300`  | `esdata`       | security disabled |
| Kibana (optional) | UI cho Elasticsearch              | `5601`           | -              | - |

---

## Cách 1: Khởi động TOÀN BỘ bằng Docker Compose (KHUYẾN NGHỊ)

### Yêu cầu
- Docker Desktop 4.x trở lên (Windows: WSL2 backend)
- Ít nhất **8GB RAM** free cho Docker (Elasticsearch + ClickHouse khá nặng)

### Bắt đầu

```bash
# Từ thư mục D:\SmartQuizSystem\database\
docker compose up -d

# Xem status tất cả
docker compose ps

# Xem log nếu có lỗi
docker compose logs -f postgres
docker compose logs -f mongodb
```

Các script `schema.sql`, `seed.sql` được mount vào `docker-entrypoint-initdb.d/` và sẽ tự chạy khi container khởi động **lần đầu tiên**.

> **Lưu ý:** Nếu muốn re-seed, phải xoá volume: `docker compose down -v && docker compose up -d`

### Kiểm tra nhanh

```bash
# PostgreSQL
docker exec -it sq_postgres psql -U postgres -d smartquiz -c "SELECT COUNT(*) FROM users;"

# MongoDB
docker exec -it sq_mongodb mongosh "mongodb://root:root@localhost/smartquiz?authSource=admin" --eval "db.questions.countDocuments()"

# Redis
docker exec -it sq_redis redis-cli DBSIZE

# ClickHouse
docker exec -it sq_clickhouse clickhouse-client --query "SELECT count() FROM smartquiz_analytics.exam_facts"

# Elasticsearch (cần seed thủ công - xem elasticsearch/README.md)
curl http://localhost:9200/_cat/indices?v
```

### Tắt / Dọn dẹp

```bash
# Dừng nhưng giữ dữ liệu
docker compose down

# Xoá hoàn toàn kèm volume
docker compose down -v
```

---

## Cách 2: Chạy trực tiếp trên máy local (không Docker)

📖 Xem hướng dẫn **chi tiết toàn tập cho Windows** tại: **[LOCAL_NATIVE_SETUP.md](./LOCAL_NATIVE_SETUP.md)**

Tóm tắt khả năng chạy native:

| DB | Native Windows? | Giải pháp |
| -- | --------------- | --------- |
| PostgreSQL 16 | ✅ | Installer chính thức |
| MongoDB 7     | ✅ | MSI chính thức |
| Elasticsearch 8 | ✅ | Windows ZIP |
| Redis 7       | ⚠️ | **Memurai** (drop-in) hoặc WSL2 |
| ClickHouse    | ❌ | **WSL2** (không có native) |

Hoặc xem README riêng của từng DB:

- [PostgreSQL](./postgresql/README.md)
- [MongoDB](./mongodb/README.md)
- [Redis](./redis/README.md)
- [ClickHouse](./clickhouse/README.md)
- [Elasticsearch](./elasticsearch/README.md)

---

## Thứ tự seed dữ liệu (nếu chạy thủ công)

Dữ liệu mẫu tham chiếu chéo giữa các DB (ví dụ `question_ref_id` trong PostgreSQL = `question_id` trong MongoDB). Nên seed theo thứ tự:

1. **PostgreSQL** (users, orgs, exams) ← nền tảng ID
2. **MongoDB** (câu hỏi - dùng IDs từ PG)
3. **Elasticsearch** (index từ MongoDB)
4. **ClickHouse** (CDC từ PG - ở local thì seed trực tiếp)
5. **Redis** (cache/session runtime)

---

## Khắc phục sự cố phổ biến

| Vấn đề | Giải pháp |
| ------ | --------- |
| Port đã bị chiếm | Đổi mapping trong `docker-compose.yml` (vd `"5433:5432"`) |
| Elasticsearch OOM | Tăng RAM Docker lên ≥ 4GB, hoặc giảm `ES_JAVA_OPTS` xuống `-Xms512m -Xmx512m` |
| ClickHouse `Too many open files` | Đã có `ulimits nofile 262144`. Với Docker Desktop Windows thường OK |
| MongoDB schema validator không chạy | `validationAction: warn` - chỉ cảnh báo, không chặn. Đổi sang `error` để strict |
| Dữ liệu không seed tự động | Docker Entry chỉ chạy initdb **lần đầu**. Phải `down -v` để re-init |

---

## Biến môi trường cho app kết nối

Copy vào `.env` của backend:

```env
# PostgreSQL
POSTGRES_URL=postgresql://postgres:postgres@localhost:5432/smartquiz

# MongoDB
MONGODB_URL=mongodb://root:root@localhost:27017/smartquiz?authSource=admin

# Redis
REDIS_URL=redis://localhost:6379/0

# ClickHouse
CLICKHOUSE_URL=http://default@localhost:8123/smartquiz_analytics

# Elasticsearch
ELASTICSEARCH_URL=http://localhost:9200
```
