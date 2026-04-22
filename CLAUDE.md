# CLAUDE.md — SmartQuizSystem

Hướng dẫn cho Claude Code (và developer con người) khi làm việc trên repo này.
File này được auto-load vào mọi session Claude Code khi cwd = repo root.

## 1. Trạng thái dự án

Dự án ở giai đoạn **pre-code**: thiết kế đầy đủ, schema DB đã chuẩn, stack dev
local đã dựng qua docker-compose. Chưa có service nào được scaffold.

Khi user yêu cầu "build X service", phải **đi theo design doc tương ứng** trong
`docs/` — không được tự ý đổi kiến trúc.

## 2. Stack — không thảo luận lại nếu không cần

| Layer | Công nghệ |
|-------|-----------|
| Backend (Auth / Exam / Question / Analytics / Cheat-Detection) | Java 21 LTS + Spring Boot 3.3+ + Gradle multi-project |
| AI Service | Python 3.12 + FastAPI + Pydantic v2 + aiokafka |
| Frontend | Next.js 15 (App Router) + React 19 + TypeScript strict |
| Build Java | Gradle (wrapper pinned) + Spotless + Checkstyle + JaCoCo |
| Build TS | pnpm + ESLint + Prettier + tsc strict |
| Build Python | uv (lock) + ruff + black + mypy |
| Test | JUnit 5 + Testcontainers · pytest + testcontainers-python · Vitest + Playwright |
| Migration | Flyway (PG) · migrate-mongo · clickhouse-migrations |
| Contracts | Avro qua Apicurio Schema Registry (BACKWARD compat mode) |
| Auth giữa service | JWT RS256 + JWKS (Auth Service là IDP) |
| Observability | Micrometer / prometheus-fastapi-instrumentator · OpenTelemetry (OTLP) · Loki |

## 3. NFR đã lock — không thay đổi tuỳ ý

- **SLA**: 99.9% single-region
- **RPO đáp án thi**: ≤ 5s, đạt qua **transactional outbox pattern** — PG ghi đáp án + outbox row trong 1 transaction, relayer process đẩy sang Kafka. Redis là cache write-through, không phải nguồn truth.
- **Fencing token** cho attempt state transition — thêm `state_version BIGINT` khi touch bảng `exam_attempts` (chưa có, phải bổ sung khi scaffold Exam Service).
- **Consumer at-least-once + idempotent** — dedupe bằng `event_id` lưu `processed_events` per service.

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
- **vision-analysis**: phân tích ảnh (proctoring L5 hoặc review mockup UI).

Skill design (có sẵn từ trước): `design-kickoff`, `design-review`, `threat-modeling`, `tech-selection`, `requirements-discovery`, `adr-writer` — dùng khi mở rộng thiết kế.

### Workflow mẫu khi scaffold service mới

1. `@spring-boot-engineer` — tạo Gradle module, entity, service layer, controller.
2. `@security-engineer` — wire Spring Security + JWT RS256 (bám NFR ở section 3).
3. Viết test (skill `spring-boot` + `jpa-patterns` auto-hỗ trợ).
4. `@code-reviewer` — review trước commit.
5. `@docker-expert` → `@kubernetes-specialist` khi containerize / deploy.

### Ranh giới agents vs CLAUDE.md

Agents chỉ biết **best practice Spring Boot chung**. Các rule **project-specific** sau **không nằm trong agents** — Claude phải chủ động áp khi code:

- NFR lock ở **section 3** (outbox pattern, state_version fencing, JWT RS256 + JWKS, idempotent consumer).
- DB migration & event schema ở các section tương ứng (Flyway naming, Avro backward-compat).

Nếu agent sinh code vi phạm rule project → stop, sửa theo CLAUDE.md, rồi mới tiếp.

## 8. Tài liệu tham chiếu nhanh

- Kiến trúc tổng: `docs/design.md`
- Database layout & rationale: `docs/database.md`
- Mỗi service: `docs/<service>-service-design.md`
- Topic Kafka: `shared-contracts/avro/TOPICS.md`
- Schema DDL: `database/{postgresql,mongodb,clickhouse,elasticsearch,redis}/`

## 9. Điểm cần làm trước khi scaffold service đầu tiên

Các gap đã biết (xem lịch sử review trong commit message):

1. Tạo ADR cho quyết định SLA 99.9% + RPO ≤5s + outbox pattern.
2. Tạo ADR gộp/tách Analytics và Cheating Detection (hiện còn bỏ ngỏ).
3. Viết OpenAPI cho Auth (critical path) trước khi frontend code login.
4. Bổ sung column `state_version BIGINT` vào `exam_attempts` khi migrate Exam schema — fencing token cho suspend race.
5. Sinh JWT keypair: `ops/gen-jwt-keypair.sh` (chưa tạo).
