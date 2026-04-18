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

## 5. Convention

### Git & commit
- **Conventional Commits**: `feat(exam): add submit endpoint`, `fix(auth): correct JWT exp`.
- Trunk-based, feature branch ngắn (≤ 3 ngày), PR nhỏ (< 400 dòng diff).
- PR phải link design doc hoặc ADR liên quan.

### Code style Java
- Spotless + Google Java Format. Tự chạy `./gradlew spotlessApply` trước commit.
- Không Lombok ngoài `@Slf4j`, `@RequiredArgsConstructor`, `@Value` (Constructor injection).
- Mỗi entity JPA có 1 repository, service layer giữ logic, controller mỏng.
- DTO tách khỏi entity — dùng MapStruct.

### Code style TS
- ESLint recommended + `@typescript-eslint/strict`.
- Prefer named exports; component trong `PascalCase.tsx`.
- Server Components mặc định; `'use client'` chỉ khi cần (form, stateful hook).

### Code style Python
- `ruff check --fix` + `black .` + `mypy --strict`.
- Type hint bắt buộc ở mọi function public.
- Pydantic v2 model cho mọi request/response FastAPI.

### DB migration
- Mỗi thay đổi schema = 1 file migration mới (không sửa file đã merge vào main).
- File name: `V{epoch}__<snake_case_desc>.sql` cho Flyway.
- Test rollback chạy được local.

### Event schema
- Thêm field → optional + default → backward-compat.
- Xoá / đổi type → bump major version, tạo topic `.v2`, migrate song song.
- Xem [`shared-contracts/avro/TOPICS.md`](shared-contracts/avro/TOPICS.md).

## 6. Interaction rules cho Claude

- **KHÔNG** đổi design doc khi sửa code trừ khi user yêu cầu rõ. Design doc là source of truth cho hành vi — nếu phát hiện design sai/thiếu, flag lên, đừng tự sửa.
- **KHÔNG** đổi schema DDL nếu chưa rà cross-service impact. Schema thuộc nhiều service — một thay đổi có thể break service khác.
- **KHÔNG** scaffold feature chưa được yêu cầu. Ví dụ user nói "add login endpoint" → chỉ thêm login, không kèm register/forgot-password.
- **Luôn** viết test đi kèm code (unit tối thiểu, integration cho flow chạm DB/Kafka).
- **Luôn** chạy migration + test trước khi nói "done". Nếu môi trường không cho chạy, ghi rõ trong output.
- **Luôn** prefer reuse — nếu đã có utility chung (trong `shared-*`), dùng lại; không copy-paste.

## 7. Skills có sẵn trong `.claude/skills/`

- **fullstack-dev**: kiến trúc backend Java/Python, REST API, auth flow, production hardening.
- **frontend-dev**: UI/UX, animation, design asset cho Next.js.
- **minimax-docx / minimax-xlsx / pptx-generator**: export báo cáo, bảng điểm, slide.
- **vision-analysis**: phân tích ảnh (có thể hỗ trợ proctoring L5 hoặc review mockup UI).

Skill design (có sẵn từ trước): `design-kickoff`, `design-review`, `threat-modeling`,
`tech-selection`, `requirements-discovery`, `adr-writer` — dùng khi mở rộng thiết kế.

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
