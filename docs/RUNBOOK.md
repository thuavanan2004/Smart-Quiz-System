# SmartQuizSystem — RUNBOOK

Hướng dẫn chạy dự án từ A-Z: infra, databases, services, observability, K8s,
troubleshooting. Mục tiêu là **onboard developer mới trong < 30 phút**.

> Mọi đường dẫn trong file này là tương đối từ **repo root** (`D:/SmartQuizSystem`
> trên máy dev chính). Lệnh chạy trên shell bash (Git Bash / WSL / Linux / macOS).
> Trên PowerShell gốc, đổi `bash script.sh` → `bash.exe script.sh` và dùng `\` cho path nếu cần.

---

## 1. Prerequisites

Cài một lần trên máy dev:

| Công cụ | Phiên bản tối thiểu | Ghi chú |
|---|---|---|
| **Docker Desktop** | 24.x | Bật WSL2 backend trên Windows |
| **Git** | 2.40+ | Có Git Bash |
| **Java JDK** | 21 LTS (Temurin khuyến nghị) | `JAVA_HOME` phải set |
| **Node.js** | 20 LTS hoặc 22 LTS | Qua nvm-windows hoặc volta |
| **pnpm** | 9.x | `npm i -g pnpm` hoặc `corepack enable` |
| **Python** | 3.12.x | Qua pyenv / winget / python.org |
| **uv** | 0.4+ | `pip install uv` hoặc `curl -LsSf https://astral.sh/uv/install.sh \| sh` |

Kiểm tra nhanh:
```bash
docker --version && java -version && node -v && pnpm -v && python --version && uv --version
```

**Không cần cài Gradle** — dự án dùng Gradle Wrapper (`./gradlew`), tự tải đúng phiên bản 8.10.2 khi chạy lần đầu.

---

## 2. First-time setup

```bash
# 1) Clone
git clone <repo-url> D:/SmartQuizSystem
cd D:/SmartQuizSystem

# 2) Env file (copy mẫu)
cp .env.example .env
# Sửa nếu cần: password DB, key JWT, Kafka brokers…
# Default trong .env.example đã đủ để chạy local.

# 3) Sinh JWT keypair (chỉ chạy 1 lần)
bash ops/gen-jwt-keypair.sh
# Kết quả: ops/keys/jwt.private.pem + jwt.public.pem
# Lưu ý: 2 file này đã trong .gitignore — không commit.
```

---

## 3. Infrastructure (databases + Kafka + MinIO + MailHog)

### 3.1 Bật stack

```bash
docker compose -f infra/docker-compose.dev.yml up -d
```

Lần đầu sẽ pull ~3GB image, mất 3-5 phút. Các lần sau < 30s.

### 3.2 Verify

```bash
docker ps --format 'table {{.Names}}\t{{.Status}}\t{{.Ports}}'
```

Kỳ vọng **11 container** ở trạng thái `healthy` / `Up`:

| Container | Cổng host | Dùng cho |
|---|---|---|
| `sq_postgres` | 5432 | OLTP (user, exam, answer...) |
| `sq_mongodb` | 27017 | Ngân hàng câu hỏi |
| `sq_redis_hot` | 6379 | Session, lock, counter |
| `sq_redis_cache` | 6380 | Cache tách biệt (tránh evict nhầm session) |
| `sq_clickhouse` | 8123 (HTTP), 9000 (native) | OLAP analytics |
| `sq_elasticsearch` | 9200 | Search + KNN vector |
| `sq_kibana` | 5601 | UI cho ES |
| `sq_kafka` | 9092 (in-docker), 29092 (host) | Event bus (KRaft, no Zookeeper) |
| `sq_schema_registry` | 8081 | Apicurio Schema Registry (Avro) |
| `sq_kafka_ui` | 8080 | UI xem topic, message |
| `sq_minio` + init job | 9001 (S3 API), 9002 (console) | Object storage (proctoring video, PDF) |
| `sq_mailhog` | 1025 (SMTP), 8025 (UI) | Mail dev — bắt email thay vì gửi thật |

### 3.3 Tạo Kafka topics

```bash
bash ops/kafka/create-topics.sh
```

Script idempotent — chạy lại không lỗi. Danh sách topic khớp `shared-contracts/avro/TOPICS.md`.

Xem topic đã tạo:
```bash
docker exec sq_kafka kafka-topics --bootstrap-server kafka:9092 --list
# Hoặc UI: http://localhost:8080
```

### 3.4 Tắt stack

```bash
# Tắt giữ data (next time chạy tiếp)
docker compose -f infra/docker-compose.dev.yml down

# Reset toàn bộ volume (xoá data — cẩn thận)
docker compose -f infra/docker-compose.dev.yml down -v
```

---

## 4. Database access & tools

### 4.1 PostgreSQL

**Connection string**: `postgresql://postgres:postgres@localhost:5432/smartquiz`

```bash
# CLI
docker exec -it sq_postgres psql -U postgres -d smartquiz

# Chạy migration thủ công (Flyway tự chạy khi service boot, mục này là fallback)
docker exec -i sq_postgres psql -U postgres -d smartquiz \
  < database/postgresql/migrations/V0001__baseline_schema.sql

# Seed dữ liệu mẫu
docker exec -i sq_postgres psql -U postgres -d smartquiz \
  < database/postgresql/seed.sql

# Xem bảng
docker exec sq_postgres psql -U postgres -d smartquiz -c "\dt"
```

**Flyway**: mỗi service Java có `spring.flyway.locations=filesystem:../../database/postgresql/migrations`. Lần boot đầu, Flyway `baseline-on-migrate=true` tạo baseline → các migration tiếp theo (V1776521023…) chạy tự động.

**GUI khuyến nghị**: pgAdmin 4, DBeaver, hoặc TablePlus.

### 4.2 MongoDB

**Connection string**: `mongodb://root:root@localhost:27017/smartquiz?authSource=admin`

```bash
docker exec -it sq_mongodb mongosh -u root -p root
# Trong shell:
use smartquiz
db.questions.countDocuments()
```

### 4.3 Redis

```bash
# Hot (session, lock)
docker exec -it sq_redis_hot redis-cli

# Cache (data cache)
docker exec -it sq_redis_cache redis-cli -p 6379
# (cache ở host cổng 6380)
redis-cli -p 6380  # nếu có redis-cli trên host
```

### 4.4 ClickHouse

```bash
docker exec -it sq_clickhouse clickhouse-client

# Trong shell:
SHOW DATABASES;
USE smartquiz_analytics;
SHOW TABLES;
```

Hoặc HTTP:
```bash
curl 'http://localhost:8123/?query=SELECT+1'
```

### 4.5 Elasticsearch

```bash
# Health
curl http://localhost:9200/_cluster/health?pretty

# Tạo index từ schema
curl -X PUT http://localhost:9200/questions \
     -H 'Content-Type: application/json' \
     -d @database/elasticsearch/schema.json

# UI
open http://localhost:5601   # Kibana
```

---

## 5. Services

### 5.1 Auth Service (Java, port 3001)

**Phải chạy trước các service khác** — các service khác verify JWT qua JWKS của Auth.

```bash
./gradlew :services:auth:bootRun
```

Endpoint sẵn có:
```bash
curl http://localhost:3001/actuator/health
curl http://localhost:3001/.well-known/jwks.json   # Public key cho RS256
curl http://localhost:9001/actuator/prometheus     # Metrics (port tách riêng)
```

### 5.2 Exam Service (Java, port 3002)

```bash
./gradlew :services:exam:bootRun
```

Bao gồm outbox relayer scheduled job (poll 500ms) → Kafka.

### 5.3 Question Service (Java, port 3003)

```bash
./gradlew :services:question:bootRun
```

Cần MongoDB + Elasticsearch + PG (taxonomy `subjects`).

### 5.4 Cheat-Detection Service (Java, port 3005 + gRPC 4005)

```bash
./gradlew :services:cheat:bootRun
```

### 5.5 Analytics Service (Java, port 3006)

```bash
./gradlew :services:analytics:bootRun
```

Cần ClickHouse up.

### 5.6 AI Service (Python, port 8201)

```bash
cd services/ai
uv sync                                  # hoặc uv sync --extra dev cho dev deps
uv run uvicorn app.main:app --reload --port 8201
```

Lint + test:
```bash
uv run ruff check .
uv run black --check .
uv run mypy app
uv run pytest
```

### 5.7 Web (Next.js, port 3000)

```bash
cd web
pnpm install
pnpm dev
# Mở http://localhost:3000
```

Build prod:
```bash
pnpm build && pnpm start
```

### 5.8 Chạy tất cả cùng lúc

Tương đối nặng trên máy dev (~10GB RAM). Khuyến nghị:
- Buổi đầu: chỉ chạy 1-2 service bạn đang code.
- Dùng Docker Compose profile hoặc terminal tiles (Windows Terminal / tmux).

Thứ tự khuyến nghị: **Infra → Auth → service đang code → Web**.

---

## 6. Observability stack (opt-in)

Cần thêm ~2GB RAM. Chỉ bật khi test metric/trace/log.

```bash
docker compose -f infra/docker-compose.dev.yml \
               -f infra/docker-compose.obs.yml up -d
```

| Service | URL | Login |
|---|---|---|
| Grafana | http://localhost:**3030** | admin / admin |
| Prometheus | http://localhost:9090 | — |
| Jaeger UI | http://localhost:16686 | — |
| Loki | http://localhost:3100 | (API only) |

**Lưu ý port Grafana**: 3030 (không phải 3000, vì 3000 dành cho Next.js và 3001 cho Auth).

### 6.1 Dashboard Grafana

Datasource Prometheus, Jaeger, Loki đã auto-provision qua
`infra/observability/grafana/datasources.yml`.

Import dashboard Spring Boot: Grafana UI → Dashboards → Import → ID `11378` (JVM Micrometer).

### 6.2 Prometheus scrape targets

Cấu hình trong `infra/observability/prometheus.yml`. Hiện scrape:
- Các service Java qua `host.docker.internal:9001-9006/actuator/prometheus`.
- AI Service qua `host.docker.internal:8201/metrics`.

Verify: http://localhost:9090/targets — tất cả phải `UP`. Nếu service chưa chạy → `DOWN`, bình thường.

### 6.3 Jaeger tracing

Các service Java cần thêm OpenTelemetry agent khi chạy (chưa tích hợp trong scaffold — sẽ làm ở task observability-hardening).

OTLP endpoint cho manual instrumentation:
- gRPC: `localhost:4317`
- HTTP: `localhost:4318`

### 6.4 Loki logs

Thu log qua Promtail hoặc log driver `loki` của Docker. Hiện **chưa cấu hình** — log vẫn ở stdout container. Roadmap: thêm Promtail vào obs stack.

### 6.5 Tắt obs stack

```bash
docker compose -f infra/docker-compose.dev.yml \
               -f infra/docker-compose.obs.yml down
```

---

## 7. Common operations

### 7.1 Reset DB về trạng thái sạch

```bash
docker compose -f infra/docker-compose.dev.yml down -v
docker compose -f infra/docker-compose.dev.yml up -d
bash ops/kafka/create-topics.sh
# Các service khi boot sẽ tự Flyway migrate lại từ đầu.
```

### 7.2 Xem log container

```bash
docker logs -f sq_postgres
docker logs -f sq_kafka --tail 100
docker compose -f infra/docker-compose.dev.yml logs -f   # log tổng hợp
```

### 7.3 Xem log service Java (khi chạy bootRun)

Log in ra stdout terminal đang chạy Gradle. Pattern cấu hình trong mỗi `application.yml`:
```
%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} - %msg%n
```

Level debug cho package `vn.smartquiz.*`. Đổi trong `application.yml` hoặc override:
```bash
./gradlew :services:auth:bootRun --args='--logging.level.vn.smartquiz=INFO'
```

### 7.4 Format code trước commit

```bash
# Java
./gradlew spotlessApply

# TypeScript (web)
cd web && pnpm lint --fix && pnpm format

# Python (AI)
cd services/ai && uv run ruff check --fix . && uv run black .
```

### 7.5 Xem Kafka message

```bash
# Console consumer
docker exec -it sq_kafka kafka-console-consumer \
    --bootstrap-server kafka:9092 \
    --topic exam.answer.submitted.v1 \
    --from-beginning

# Hoặc UI: http://localhost:8080 → Topics → chọn topic → Messages
```

### 7.6 Publish test event (manual)

```bash
docker exec -it sq_kafka kafka-console-producer \
    --bootstrap-server kafka:9092 \
    --topic exam.answer.submitted.v1
# Gõ JSON, Enter để gửi, Ctrl+C để thoát.
```

---

## 8. Testing

### 8.1 Unit + integration test Java

```bash
./gradlew test                           # tất cả service
./gradlew :services:auth:test            # 1 service
./gradlew :services:auth:jacocoTestReport # coverage report
```

Report: `services/<svc>/build/reports/tests/test/index.html` và
`services/<svc>/build/reports/jacoco/test/html/index.html`.

Integration test dùng **Testcontainers** — tự spawn PG/Mongo/Kafka/ES thật trong container. Cần Docker chạy. Testcontainers **không reuse** container dev (sq_postgres…) mà tạo ephemeral riêng.

### 8.2 AI Service

```bash
cd services/ai
uv run pytest
uv run pytest --cov=app                  # coverage
```

### 8.3 Web

```bash
cd web
pnpm test         # Vitest unit test
pnpm test:e2e     # Playwright E2E (cần stack dev đang chạy)
```

### 8.4 Contract test

Avro schema compatibility: Apicurio tự check khi register schema mới. CI sẽ chạy `mvn schema-registry:check` (chưa tích hợp).

OpenAPI contract: `shared-contracts/openapi/auth.v1.yaml` → dùng `openapi-generator` sinh client TS cho web. (Chưa scaffold pipeline.)

---

## 9. Kubernetes (production deployment)

> **Trạng thái: CHƯA SCAFFOLD.** Roadmap có, manifest chưa có.

Dự kiến cấu trúc khi làm:
```
infra/k8s/
├── base/                # Kustomize base
│   ├── auth/
│   ├── exam/
│   ├── question/
│   ├── analytics/
│   ├── cheat/
│   ├── ai/
│   └── web/
├── overlays/
│   ├── dev/
│   ├── staging/
│   └── prod/
└── helm-values/         # values cho Kong, Kafka (Strimzi), PG (CloudNativePG), v.v.
```

**Dependency ngoài cluster (managed service khuyến nghị)**:
- PostgreSQL → RDS / CloudSQL (backup + multi-AZ).
- Kafka → MSK / Confluent Cloud (giảm ops).
- Redis → ElastiCache / Memorystore.
- ClickHouse → ClickHouse Cloud hoặc Altinity.
- MongoDB → Atlas.

**Gateway trong cluster**: Kong Ingress Controller (theo design.md §2.3).

**Observability**: Prometheus Operator + Grafana Cloud (hoặc self-hosted), Loki, Tempo (thay Jaeger cho tracing).

Khi có thời gian scaffold, xem task "infra-k8s" trong backlog.

---

## 10. Troubleshooting

### 10.1 "Port already in use"

Xem process đang giữ port:
```bash
# Windows
netstat -ano | grep :5432
taskkill /PID <pid> /F

# macOS / Linux
lsof -i :5432
kill -9 <pid>
```

Port hay conflict: 3000 (Next.js / Grafana cũ), 5432 (PG local), 9200 (ES).

### 10.2 Service Java fail với "JWKS fetch error"

Nguyên nhân: Auth Service chưa chạy hoặc crash. Các service khác fetch JWKS lúc boot → startup fail.

Fix:
```bash
# Start auth trước
./gradlew :services:auth:bootRun &
# Đợi auth log "Started AuthServiceApplication" rồi start service khác.
```

### 10.3 Flyway migration fail "relation exam_attempts does not exist"

Nguyên nhân: DB trống, V1776521023 (ALTER exam_attempts) chạy trước V0001.

Fix:
```bash
# Baseline bằng tay nếu Flyway không auto-baseline
docker exec -i sq_postgres psql -U postgres -d smartquiz \
  < database/postgresql/migrations/V0001__baseline_schema.sql

# Sau đó restart service — Flyway sẽ tiếp tục từ V1776521023.
```

### 10.4 Docker image pull chậm / lỗi mạng

- Cấu hình registry mirror trong Docker Desktop Settings → Docker Engine:
  ```json
  { "registry-mirrors": ["https://mirror.gcr.io"] }
  ```
- Hoặc VPN nếu bị firewall chặn quay.io.

### 10.5 WSL2 chiếm quá nhiều RAM (Windows)

Tạo `%USERPROFILE%\.wslconfig`:
```
[wsl2]
memory=8GB
processors=4
```
Restart WSL: `wsl --shutdown` rồi mở lại Docker Desktop.

### 10.6 Elasticsearch "max_map_count"

Lỗi khi ES start trên Linux:
```bash
sudo sysctl -w vm.max_map_count=262144
# Giữ sau reboot:
echo "vm.max_map_count=262144" | sudo tee -a /etc/sysctl.conf
```

### 10.7 Kafka "connection refused"

- Confirm container up: `docker ps | grep sq_kafka`.
- Từ **host** (laptop) connect `localhost:29092` (KHÔNG phải 9092).
- Từ **container khác** connect `kafka:9092`.
- Service Java dùng `KAFKA_BROKERS` env var — mặc định `localhost:9092`, **sai cho host**. Override `KAFKA_BROKERS=localhost:29092` khi chạy từ host.

### 10.8 ClickHouse "Connection refused" khi chạy service analytics

Đã fix `AnalyticsServiceApplication` exclude `DataSourceAutoConfiguration`. Nhưng cần tự viết `@Bean DataSource clickhouseDataSource()` khi bắt đầu code feature. Scaffold hiện boot OK nhưng không query được CH.

---

## 11. Quick reference — URL dev local

| Dịch vụ | URL |
|---|---|
| Auth Service | http://localhost:3001 |
| Exam Service | http://localhost:3002 |
| Question Service | http://localhost:3003 |
| Cheat Service | http://localhost:3005 (gRPC 4005) |
| Analytics Service | http://localhost:3006 |
| AI Service | http://localhost:8201 |
| Web | http://localhost:3000 |
| Kafka UI | http://localhost:8080 |
| Schema Registry | http://localhost:8081 |
| MinIO console | http://localhost:9002 |
| Kibana | http://localhost:5601 |
| MailHog | http://localhost:8025 |
| Grafana (obs) | http://localhost:3030 |
| Prometheus (obs) | http://localhost:9090 |
| Jaeger (obs) | http://localhost:16686 |

---

## 12. Khi cần đọc thêm

- **Convention code + NFR**: [`CLAUDE.md`](../CLAUDE.md)
- **Kiến trúc tổng**: [`docs/design.md`](design.md)
- **Database reference**: [`docs/database.md`](database.md)
- **Service design từng service**: `docs/<name>-service-design.md`
- **ADRs**: [`docs/adr/`](adr/)
- **Event schemas + topics**: [`shared-contracts/avro/TOPICS.md`](../shared-contracts/avro/TOPICS.md)
