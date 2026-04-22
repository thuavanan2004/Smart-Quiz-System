# SmartQuizSystem

Nền tảng thi trắc nghiệm trực tuyến có AI chấm điểm, sinh câu hỏi tự động và
phát hiện gian lận lớp. Kiến trúc microservices, polyglot persistence, hướng
đến 100K MAU với SLA 99.9%.

> **Trạng thái:** *scaffold-complete* — 5 Java service + AI service + web đã
> scaffold, build chạy được, DB migration đã có baseline. Bước tiếp theo: code
> feature (auth flow trước, xem `CLAUDE.md` §9).

## Kiến trúc rút gọn

| Thành phần | Công nghệ | Port local |
|---|---|---:|
| Auth Service | Java 21 + Spring Boot 3.3 | 3001 (HTTP) / 9001 (actuator) |
| Exam Service | Java 21 + Spring Boot 3.3 | 3002 / 9002 |
| Question Service | Java 21 + Spring Boot 3.3 | 3003 / 9003 |
| Cheat-Detection Service | Java 21 + Spring Boot 3.3 | 3005 / 9005 (+ gRPC 4005) |
| Analytics Service | Java 21 + Spring Boot 3.3 | 3006 / 9006 |
| AI Service | Python 3.12 + FastAPI | 8201 |
| Web (student + admin) | Next.js 15 + React 19 | 3000 |
| OLTP | PostgreSQL 16 | 5432 |
| Document store | MongoDB 7 | 27017 |
| Cache / session | Redis 7 (hot + cache tách biệt) | 6379, 6380 |
| OLAP | ClickHouse 23.8 | 8123 |
| Search + vector | Elasticsearch 8.13 | 9200 |
| Event streaming | Kafka 3.x (KRaft) | 29092 |
| Schema registry | Apicurio | 8081 |
| Object storage | MinIO | 9001 (API), 9002 (console) |
| Mail dev | MailHog | 8025 |

## Quick start

> Hướng dẫn đầy đủ (infra, DB, observability, troubleshooting, K8s roadmap):
> **[`docs/RUNBOOK.md`](docs/RUNBOOK.md)**.

```bash
# 1) Chuẩn bị env
cp .env.example .env

# 2) Bật full stack (databases + Kafka + MinIO + MailHog)
docker compose -f infra/docker-compose.dev.yml up -d

# 3) Tạo Kafka topics
bash ops/kafka/create-topics.sh

# 4) Sinh JWT keypair cho Auth Service (chỉ chạy lần đầu)
bash ops/gen-jwt-keypair.sh

# 5) Verify stack
docker ps --format 'table {{.Names}}\t{{.Status}}'
```

UI tiện ích:
- Kafka UI → http://localhost:8080
- MinIO console → http://localhost:9002 (minioadmin / minioadmin)
- Kibana → http://localhost:5601
- MailHog → http://localhost:8025
- Schema Registry → http://localhost:8081

### Chạy 1 service Java (dev loop)

```bash
./gradlew :services:auth:bootRun
# hoặc
./gradlew :services:exam:bootRun
```

Flyway sẽ tự chạy `V0001__baseline_schema.sql` + các migration kế tiếp khi
service boot (với Docker PG, baseline đã được mount vào `initdb.d` nên Flyway
chỉ chạy phần còn lại).

### Chạy AI service (Python)

```bash
cd services/ai
uv sync
uv run uvicorn app.main:app --reload --port 8201
```

### Chạy Web

```bash
cd web
pnpm install
pnpm dev
```

### Tắt stack

```bash
docker compose -f infra/docker-compose.dev.yml down
# Xoá toàn bộ volume (reset data):
docker compose -f infra/docker-compose.dev.yml down -v
```

### Observability (opt-in, tốn ~2GB RAM)

Prometheus + Grafana + Jaeger + Loki tách riêng:
```bash
docker compose -f infra/docker-compose.dev.yml -f infra/docker-compose.obs.yml up -d
```

## Bố cục repo

```
.claude/                 # Skills + settings cho Claude Code
config/checkstyle/       # Checkstyle config dùng chung cho toàn bộ Java service
database/                # Schema + seed (PG, Mongo, ClickHouse, ES, Redis)
  postgresql/schema.sql  # Single source of truth cho schema PG
docs/                    # Design docs 7 service + database + ADR
  adr/                   # Architecture Decision Records
gradle/, gradlew, *.kts  # Gradle multi-project (Spring Boot services)
infra/                   # docker-compose.dev.yml + obs stack
ops/                     # Scripts vận hành (create-topics.sh, gen-jwt-keypair.sh)
services/
  auth/                  # JWT issuer + RBAC
  exam/                  # Attempt lifecycle + outbox relayer
  question/              # Ngân hàng câu hỏi (Mongo + PG + ES)
  analytics/             # OLAP queries (ClickHouse)
  cheat/                 # Proctor signals → risk score
  ai/                    # FastAPI (AI grading, sinh câu hỏi)
shared-contracts/        # Avro event schemas, OpenAPI, Proto
web/                     # Next.js 15 frontend
CLAUDE.md                # Guide cho Claude Code (convention, NFR, quick commands)
```

## Tài liệu

- **Kiến trúc tổng**: [`docs/design.md`](docs/design.md)
- **Database reference**: [`docs/database.md`](docs/database.md)
- **Service design** (mỗi service 1 file):
  [`auth`](docs/auth-service-design.md) ·
  [`exam`](docs/exam-service-design.md) ·
  [`question`](docs/question-service-design.md) ·
  [`ai`](docs/ai-service-design.md) ·
  [`analytics`](docs/analytics-service-design.md) ·
  [`cheat detection`](docs/cheating-detection-service-design.md)
- **ADRs**:
  [ADR-001 — SLA / RPO / outbox](docs/adr/ADR-001-sla-rpo-outbox.md) ·
  [ADR-002 — Analytics vs Cheat split](docs/adr/ADR-002-analytics-vs-cheating-split.md)
- **Kafka topics**: [`shared-contracts/avro/TOPICS.md`](shared-contracts/avro/TOPICS.md)
- **OpenAPI Auth**: [`shared-contracts/openapi/auth.v1.yaml`](shared-contracts/openapi/auth.v1.yaml)

## NFR đã lock

- **SLA**: 99.9% single-region (≈43 phút downtime/tháng)
- **RPO đáp án thi**: ≤ 5s — đạt qua **transactional outbox pattern** (PG ghi
  đáp án + outbox row trong 1 tx, relayer đẩy sang Kafka). Redis là cache
  write-through, KHÔNG phải nguồn truth. Xem ADR-001.
- **Fencing token**: mọi `UPDATE exam_attempts` check `state_version` — chống
  race giữa client submit và cheat-detection auto-suspend.
- **Consumer at-least-once + idempotent**: dedupe bằng `event_id` lưu trong
  `processed_events` mỗi service.
- **Peak**: 100K WebSocket đồng thời (kỳ thi lớn).
- **p95 latency**: submit answer < 200ms, start exam < 500ms.

## Auth giữa service

- Auth Service là IDP duy nhất — phát hành JWT **RS256**.
- Các service khác verify token qua JWKS endpoint: `GET http://auth:3001/.well-known/jwks.json`
  (pre-wired trong mọi `application.yml`).
- Keypair dev lưu ở `ops/keys/` (không commit). Prod: Vault Transit.

## Convention nhanh

- **Commit**: Conventional Commits (`feat(exam): ...`, `fix(auth): ...`).
- **Branch**: trunk-based, feature branch ≤ 3 ngày, PR < 400 LOC diff.
- **Java**: Spotless + Google Java Format. `./gradlew spotlessApply` trước commit.
- **TS**: ESLint strict + Prettier, named exports, Server Components mặc định.
- **Python**: `ruff --fix` + `black` + `mypy --strict`, Pydantic v2.
- **DB migration**: 1 thay đổi = 1 file mới, **không sửa** file đã merge.
  Tên: `V{epoch}__<snake_case_desc>.sql`.
- **Event schema**: thêm field optional + default (backward-compat). Break
  change = bump major + topic `.v2` song song.

Chi tiết: xem [`CLAUDE.md`](CLAUDE.md).

## License

(Chưa quyết định — mặc định proprietary)
