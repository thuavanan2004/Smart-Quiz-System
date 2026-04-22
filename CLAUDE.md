# CLAUDE.md — SmartQuizSystem

Hướng dẫn cho Claude Code (và developer con người) khi làm việc trên repo này.
File này được auto-load vào mọi session Claude Code khi cwd = repo root.

## 1. Trạng thái dự án

Dự án ở giai đoạn **pre-code**: thiết kế đầy đủ, schema DB đã chuẩn, stack dev
local đã dựng qua docker-compose. Chưa có service nào được scaffold.

**Phạm vi code đi theo `docs/scope-datn.md`** (ADR-003). Các file
`docs/*-service-design.md` là **tài liệu tham chiếu kiến trúc production** —
đọc để hiểu design choice và thuật toán, nhưng **không implement đầy đủ** trong
DATN. Khi user yêu cầu "build X service", bám `scope-datn.md` trước, doc
service sau.

Tóm tắt scope DATN: **4 service** (Auth, Core, AI, Proctoring), **1 PG duy nhất**
(drop Mongo/ClickHouse/Elasticsearch), **JSON event** (không Avro), **REST**
(không gRPC), **single-tenant** (không `org_id`). Chi tiết ở `docs/scope-datn.md`.

## 2. Stack — scope DATN

| Layer | Công nghệ |
|-------|-----------|
| Backend (Auth / Core / Proctoring) | Java 21 LTS + Spring Boot 3.3+ + Gradle multi-project |
| AI Service | Python 3.12 + FastAPI + Pydantic v2 + aiokafka |
| Frontend | Next.js 15 (App Router) + React 19 + TypeScript strict |
| Database | **1 PostgreSQL** (schema riêng per service) + Redis (cache/session/WS pubsub) |
| Messaging | Kafka (6 topic, JSON payload, version qua tên topic `.v1`) |
| Inter-service RPC | **REST + JSON** (không gRPC trong DATN) |
| Build Java | Gradle (wrapper pinned) + Spotless + Checkstyle + JaCoCo |
| Build TS | pnpm + ESLint + Prettier + tsc strict |
| Build Python | uv (lock) + ruff + black + mypy |
| Test | JUnit 5 + Testcontainers · pytest + testcontainers-python · Vitest + Playwright |
| Migration | Flyway (PG) |
| Contracts | **JSON Schema** ở `shared-contracts/events/` (không Avro/Apicurio) |
| Auth giữa service | JWT RS256 + JWKS (Auth Service là IDP) |
| Observability | Micrometer + Prometheus + log console (OTel/Loki là future work) |

**Future work (giữ trong docs nhưng không code)**: MongoDB, ClickHouse, Elasticsearch, Avro + Apicurio, gRPC, Flink. Xem `docs/scope-datn.md` §3 và §9.

## 3. NFR đã lock — scope DATN (ADR-003)

- **SLA**: best-effort (demo được, có thể restart). Production target 99.9% giữ trong doc gốc.
- **RPO đáp án thi**: ≤ **30s**, đạt qua **transactional outbox pattern** — PG ghi đáp án + outbox row trong 1 transaction, relayer poll mỗi 5s đẩy sang Kafka. Redis là cache write-through, không phải nguồn truth.
- **Fencing token** cho attempt state transition — `state_version BIGINT` trên `exam_attempts` (bắt buộc, rẻ, defend được).
- **Consumer at-least-once + idempotent** — dedupe bằng `event_id` lưu `processed_events` per service.
- **JWT RS256 + JWKS** — Auth Service là IDP, mọi service verify token qua JWKS cache 1h.
- **Single-tenant**: không có `org_id` trong bảng/event.

Nếu user yêu cầu đổi một trong những cái trên → phải **push back rõ ràng** và
đòi quyết định ghi ADR (`docs/adr/ADR-XXX.md`).

## 4. Quick commands

```bash
# Bật stack dev
docker compose -f infra/docker-compose.dev.yml up -d
bash ops/kafka/create-topics.sh

# Reset toàn bộ data
docker compose -f infra/docker-compose.dev.yml down -v

# Observability (opt-in, tốn ~2GB RAM)
docker compose -f infra/docker-compose.dev.yml -f infra/docker-compose.obs.yml up -d

# Khi có services/ — placeholder:
# cd services/auth && ./gradlew bootRun
# cd services/ai   && uv run uvicorn app.main:app --reload --port 8201
# cd web           && pnpm dev
```

## 6. Interaction rules cho Claude

- **KHÔNG** đổi design doc khi sửa code trừ khi user yêu cầu rõ. Design doc là source of truth cho hành vi — nếu phát hiện design sai/thiếu, flag lên, đừng tự sửa.
- **KHÔNG** đổi schema DDL nếu chưa rà cross-service impact. Schema thuộc nhiều service — một thay đổi có thể break service khác.
- **KHÔNG** scaffold feature chưa được yêu cầu. Ví dụ user nói "add login endpoint" → chỉ thêm login, không kèm register/forgot-password.
- **Luôn** viết test đi kèm code (unit tối thiểu, integration cho flow chạm DB/Kafka).
- **Luôn** chạy migration + test trước khi nói "done". Nếu môi trường không cho chạy, ghi rõ trong output.
- **Luôn** prefer reuse — nếu đã có utility chung (trong `shared-*`), dùng lại; không copy-paste.

## 7. Agents & Skills trong `.claude/`

### Agents (`.claude/agents/`) — invoke bằng `@<name>` hoặc để Claude tự chọn

- **spring-boot-engineer**: scaffold Gradle module, entity, repository, service, controller, REST endpoint cho backend Java.
- **security-engineer**: Spring Security, JWT, OAuth2, threat model, vulnerability review.
- **code-reviewer**: review PR, quality gate trước merge.
- **devops-engineer**: CI/CD pipeline, automation.
- **docker-expert**: Dockerfile multi-stage, image hardening.
- **kubernetes-specialist**: K8s manifest, Helm, deployment pattern.

### Skills (`.claude/skills/`) — auto-load theo context

Backend Java / Spring Boot:
- **spring-boot**: Spring Boot 3.x — REST, JPA, Security, Testing, Cloud-native.
- **code-quality**: clean code, API contract, null safety, exception handling, performance.
- **design-patterns**: Factory, Builder, Strategy, Observer, Decorator...
- **jpa-patterns**: N+1, lazy loading, transaction, fetch strategy.
- **logging-patterns**: SLF4J, structured JSON log, MDC request tracing.

Frontend React / Next.js (Vercel Engineering):
- **react-best-practices**: 45 rule perf optimization React/Next.js (cascading useEffect, bundle split, heavy client import...).
- **composition-patterns**: compound component, render props, context provider; fix boolean prop proliferation. Cập nhật theo React 19.
- **react-view-transitions**: `<ViewTransition>` API — page transition, shared element, list reorder (không cần thư viện 3rd-party).
- **web-design-guidelines**: audit UI compliance (accessibility, UX best practice).

Doc export + vision:
- **minimax-docx / minimax-xlsx / pptx-generator**: export báo cáo, bảng điểm, slide.
- **vision-analysis**: phân tích ảnh (review mockup UI — proctoring video là future work).

Skill design (có sẵn từ trước): `design-kickoff`, `design-review`, `threat-modeling`, `tech-selection`, `requirements-discovery`, `adr-writer` — dùng khi mở rộng thiết kế.

### Workflow mẫu khi scaffold service mới

1. `@spring-boot-engineer` — tạo Gradle module, entity, service layer, controller.
2. `@security-engineer` — wire Spring Security + JWT RS256 (bám NFR ở section 3).
3. Viết test (skill `spring-boot` + `jpa-patterns` auto-hỗ trợ).
4. `@code-reviewer` — review trước commit.
5. `@docker-expert` → `@kubernetes-specialist` khi containerize / deploy.

### Ranh giới agents vs CLAUDE.md

Agents chỉ biết **best practice Spring Boot chung**. Các rule **project-specific** sau **không nằm trong agents** — Claude phải chủ động áp khi code:

- Scope DATN ở `docs/scope-datn.md` (4 service, 1 PG, REST, JSON event, single-tenant).
- NFR lock ở **section 3** (outbox pattern RPO ≤30s, state_version fencing, JWT RS256 + JWKS, idempotent consumer).
- Event JSON schema ở `shared-contracts/events/` (không Avro trong DATN).

Nếu agent sinh code vi phạm rule project → stop, sửa theo CLAUDE.md + scope-datn.md, rồi mới tiếp.

## 8. Tài liệu tham chiếu nhanh

- **Scope DATN (source of truth cho phạm vi code)**: `docs/scope-datn.md`
- **ADR chủ đạo**: `docs/adr/ADR-003-datn-scope.md`
- **Kiến trúc tổng (DATN)**: `docs/design.md`
- **Database (DATN)**: `docs/database.md` — PG + pgvector
- **Service design (DATN)**:
  - `docs/auth-service-design.md`
  - `docs/core-service-design.md` (gộp Exam + Question + Analytics)
  - `docs/ai-service-design.md` (Combo A — upload→gen, tutor, detector)
  - `docs/proctoring-service-design.md` (L1–L3)
- **Topic Kafka + JSON schema**: `shared-contracts/events/` (sẽ tạo khi scaffold)
- **Schema DDL DATN**: `database/postgresql/` (Mongo/ClickHouse/ES là future work)
- **Archive production design**: `docs/archive/production-design/` — chỉ tham khảo khi defend "scale thế nào", KHÔNG implement theo.

## 9. Điểm cần làm trước khi scaffold service đầu tiên

1. ✅ ADR-001 SLA/RPO/outbox — đã có.
2. ✅ ADR-002 Analytics/Cheating — superseded bởi ADR-003 (Analytics gộp vào Core).
3. ✅ ADR-003 DATN scope — đã có.
4. ✅ Docs DATN-scope đã viết (`design.md`, `database.md`, 4 service design).
5. Tạo ADR-004 (drop MongoDB → PG JSONB cho question).
6. Tạo ADR-005 (drop ClickHouse → PG view cho analytics).
7. Tạo ADR-006 (Combo A AI feature: upload→gen, tutor, detector).
8. Tạo ADR-007 (pgvector thay vector DB riêng).
9. Rewrite `database/postgresql/schema.sql` theo `docs/database.md` (drop `org_id`, merge 3 schema).
10. Viết OpenAPI cho Auth (critical path) trước khi frontend code login.
11. Sinh JWT keypair: `ops/gen-jwt-keypair.sh` (chưa tạo).
12. Tạo `shared-contracts/events/` với 10 JSON schema topic DATN.
13. Rà `infra/docker-compose.dev.yml`: đổi PG image sang `pgvector/pgvector:pg16`; disable Mongo, CH, ES, Flink, Apicurio.
14. Tạo `ops/llm-api-keys.example.env` cho AI service.
