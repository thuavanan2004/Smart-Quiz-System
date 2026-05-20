# ARCHITECTURE.md — SmartQuizSystem (DATN)

> **Mục đích**: file context-priming nạp 1 lần đầu mỗi session AI code-gen. Tóm
> tắt **mọi rằng buộc liên-service** AI phải tuân thủ. Khi sinh code, đọc file
> này TRƯỚC, sau đó mới đọc service design tương ứng (`docs/{auth,core,ai,proctoring}-service-design.md`).
>
> File này KHÔNG thay thế design doc — design doc mô tả nội-service, file này
> mô tả **liên-service + cross-cutting**.

---

## 0. Tham chiếu nhanh

| Doc                                           | Khi nào đọc                                         |
| --------------------------------------------- | --------------------------------------------------- |
| `docs/design.md`                              | Cần sơ đồ tổng / luồng nghiệp vụ                    |
| `docs/auth-service-design.md`                 | Đụng Auth (login, JWT, JWKS, refresh)               |
| `docs/core-service-design.md`                 | Đụng Core (exam, question, attempt, outbox, WS)     |
| `docs/ai-service-design.md`                   | Đụng AI (grading, generation, tutor, detector)      |
| `docs/proctoring-service-design.md`           | Đụng Proctoring (cheat L1–L3, rule engine)          |
| `database/postgresql/schema.sql`              | Cần đúng tên cột / index / FK / ENUM                |
| `database/postgresql/README.md`               | Schema ownership + role grants + migration workflow |
| `shared-contracts/events/*.schema.json`       | Payload JSON của 10 Kafka topic (sẽ tạo khi scaffold) |
| `shared-contracts/openapi/*.yaml`             | REST inter-service contract (sẽ tạo khi scaffold)   |
| `CLAUDE.md`                                   | Interaction rules tổng quát                         |

---

## 1. Service map (lock)

| #   | Service     | Stack                   | Port | DB schema      | Role                                                                                                                     |
| --- | ----------- | ----------------------- | ---- | -------------- | ------------------------------------------------------------------------------------------------------------------------ |
| 1   | Auth        | Java 21 + SB 3.3        | 8101 | `auth`         | IDP — register/login/refresh, JWT RS256 issuer, JWKS endpoint                                                            |
| 2   | Core        | Java 21 + SB 3.3        | 8102 | `core`         | Exam/Question CRUD, attempt lifecycle, grading orch., document upload, AI gen orch., outbox relayer, WS session, analytics views |
| 3   | AI          | Python 3.12 + FastAPI   | 8103 | — (stateless)  | Grade essay, gen Q, tutor explain, AI essay detect, embedding endpoint                                                   |
| 4   | Proctoring  | Java 21 + SB 3.3        | 8104 | `proctoring`   | Consume `cheat.event.raw.v1`, rule engine L1–L3, alert aggregator                                                        |
| 5   | Web         | Next.js 15 + React 19   | 3000 | —              | UI student / teacher / admin                                                                                             |

**Lock cứng**:
- 1 PostgreSQL instance (image `pgvector/pgvector:pg16`), 3 schema cô lập.
- Inter-service: REST (không gRPC), JSON event qua Kafka (không Avro).
- Single-tenant — **không có `org_id`** ở bảng/event/JWT claim.
- 4 service backend này là **scope đóng**. Không scaffold service mới khi chưa thảo luận.

---

## 2. Stack version locks

| Layer            | Version                                                                              |
| ---------------- | ------------------------------------------------------------------------------------ |
| JDK              | **21 LTS** (Temurin)                                                                 |
| Spring Boot      | **3.3.x**                                                                            |
| Gradle           | Wrapper pinned trong repo, multi-project                                             |
| Python           | **3.12**                                                                             |
| FastAPI / Pydantic | FastAPI 0.115+ / Pydantic v2                                                       |
| Node             | LTS 20+                                                                              |
| Next.js          | **15** App Router · React 19 · TypeScript strict                                     |
| PostgreSQL       | **16** với `pgvector`                                                                |
| Redis            | 7                                                                                    |
| Kafka            | Confluent Platform / Bitnami image, JSON payload (KafkaAvroSerializer ❌)            |
| Build Java       | Gradle + **Spotless + Checkstyle + JaCoCo**                                          |
| Build TS         | **pnpm + ESLint + Prettier + tsc strict**                                            |
| Build Python     | **uv (lock) + ruff + black + mypy**                                                  |
| Test             | JUnit 5 + Testcontainers · pytest + testcontainers-python · Vitest + Playwright      |
| Migration        | **Flyway** per Java service (không dùng Liquibase)                                   |

**Đừng tự bump version** khi không được yêu cầu — pin đã thoả thuận.

---

## 3. NFR đã lock

| Quy tắc                              | Cách đạt                                                                          |
| ------------------------------------ | --------------------------------------------------------------------------------- |
| **RPO đáp án ≤ 30s**                 | **Transactional outbox** trong Core: ghi `attempt_answers` + `outbox` cùng 1 TX; relayer poll 5s publish Kafka |
| **Fencing token** transition attempt | Cột `exam_attempts.state_version BIGINT`. Mọi UPDATE phải `WHERE state_version = $expected` |
| **At-least-once + idempotent**       | Mọi consumer dedupe `event_id` qua bảng `processed_events` (per schema)            |
| **JWT RS256**                        | Auth là IDP, các service verify qua JWKS cache 1h                                  |
| **JSON event**                       | Schema lưu `shared-contracts/events/*.schema.json`; version qua tên topic `.v1`    |
| **REST inter-service**               | OpenAPI 3.1 spec ở `shared-contracts/openapi/`                                     |
| **Single-tenant**                    | KHÔNG `org_id` trong bảng / event / JWT claim                                      |

**Nếu code AI sinh ra vi phạm 1 trong 7 dòng trên → stop, sửa, rồi mới tiếp.**

---

## 4. Cross-cutting code patterns

### 4.1. JWT verify (mọi service Java trừ Auth)

```java
// JwksClient: cache TTL 1h, fetch http://localhost:8101/.well-known/jwks.json
// Spring Security cấu hình:
@Bean
SecurityFilterChain api(HttpSecurity http, JwtDecoder decoder) throws Exception {
    return http
        .csrf(c -> c.disable())                                    // Bearer token, stateless
        .sessionManagement(s -> s.sessionCreationPolicy(STATELESS))
        .authorizeHttpRequests(a -> a
            .requestMatchers("/actuator/health", "/actuator/prometheus").permitAll()
            .anyRequest().authenticated())
        .oauth2ResourceServer(o -> o.jwt(j -> j.decoder(decoder)
            .jwtAuthenticationConverter(rolesConverter())))         // map claim "role" → ROLE_<X>
        .build();
}

@Bean
JwtDecoder jwtDecoder(@Value("${smartquiz.auth.jwks-url}") String jwksUrl) {
    return NimbusJwtDecoder.withJwkSetUri(jwksUrl)
        .cache(Duration.ofHours(1))
        .build();
}
```

- Claim cần map: `sub` → userId UUID, `role` → `ROLE_STUDENT|ROLE_TEACHER|ROLE_ADMIN`, `email`, `name`.
- `@PreAuthorize("hasRole('TEACHER')")` ở controller method (KHÔNG check role thủ công trong service).

### 4.2. Transactional outbox (Core + Proctoring)

Bảng `outbox` per schema (`core.outbox`, `proctoring.outbox`):

```
id BIGSERIAL PK,
event_id UUID UNIQUE NOT NULL,                  -- producer set, dùng làm dedupe key
topic TEXT NOT NULL,                             -- vd 'exam.answer.submitted.v1'
partition_key TEXT NOT NULL,                     -- attempt_id hoặc request_id
payload JSONB NOT NULL,
headers JSONB,                                   -- {schema_version, occurred_at}
created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
published_at TIMESTAMPTZ,                        -- NULL = chưa publish
attempts INT NOT NULL DEFAULT 0
```

**Ghi** (always trong cùng TX với business write):

```java
@Transactional
public void submitAnswer(...) {
    attemptAnswerRepo.upsert(...);
    outbox.enqueue("exam.answer.submitted.v1", attemptId.toString(), payload);
}
```

**Relayer** (1 instance per service, leader election Redis):

```
Redis: SET lock:outbox:{service} <node-id> NX EX 30 (renew 10s)
PG  : SELECT * FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT 500 FOR UPDATE SKIP LOCKED
Kafka: send batch với key=partition_key, headers={event_id, schema_version}
PG  : UPDATE outbox SET published_at=now() WHERE id IN (...)
Cleanup: DELETE outbox WHERE published_at < now() - interval '7 days'
```

**Cấm**:
- Publish Kafka không qua outbox (trừ `cheat.event.raw.v1` từ Core WS handler — exception đã document).
- Xoá outbox row trước khi publish thành công.

### 4.3. Idempotent consumer

Bảng `processed_events` per schema:

```
event_id UUID PRIMARY KEY,
topic TEXT NOT NULL,
processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
```

```java
@KafkaListener(topics = "grading.result.v1")
@Transactional
public void onGradingResult(GradingResultEvent e) {
    if (processedEventsRepo.existsById(e.eventId())) return;        // dedupe
    // ... apply business write ...
    processedEventsRepo.insert(e.eventId(), "grading.result.v1");   // mark in SAME TX
}
```

Cleanup: cron xoá `processed_events` cũ > 30 ngày.

### 4.4. Fencing token (Core attempt transitions)

```sql
UPDATE core.exam_attempts
SET status = 'SUBMITTED',
    state_version = state_version + 1,
    submitted_at = now()
WHERE id = $1
  AND status = 'IN_PROGRESS'
  AND state_version = $expectedVersion;
-- 0 rows updated → throw 409 Conflict
```

Client gửi `state_version` hiện tại trong body. Mọi state-changing endpoint trên attempt **bắt buộc** check fencing.

### 4.5. JSON event envelope (mọi Kafka topic)

```json
{
  "event_id": "uuid-v4",                     // dedupe key, producer sinh
  "event_type": "exam.answer.submitted.v1",  // = topic name
  "occurred_at": "2026-05-20T10:23:45.123Z", // ISO-8601 UTC
  "producer": "core",                         // service name
  "schema_version": 1,
  "payload": { ... }                          // shape lock theo schema JSON
}
```

- `event_id` đi vào header Kafka `event_id` để consumer dedupe trước khi parse payload.
- `partition_key` chọn theo §6.

### 4.6. HTTP error envelope (mọi service REST)

```json
{
  "error": {
    "code": "ATTEMPT_ALREADY_SUBMITTED",
    "message": "Attempt is no longer in progress",
    "trace_id": "req-7f3a..."
  }
}
```

- HTTP status code chuẩn (200/201/204/400/401/403/404/409/422/429/500).
- `code` SCREAMING_SNAKE_CASE, ổn định cho client switch.
- Không leak stack trace ra client.

### 4.7. Logging — JSON structured

- SLF4J + Logback (Java) / `structlog` hoặc `logging` cấu hình JSON (Python).
- MDC bắt buộc: `requestId` (từ header `X-Request-Id`, sinh UUID nếu vắng), `userId` (sau verify JWT), `attemptId` (nếu có).
- Format: `{ "ts": "...", "level": "INFO", "logger": "...", "msg": "...", "requestId": "...", "userId": "...", "attemptId": "..." }`.

### 4.8. Embedding (Core gọi AI service)

- Endpoint: `POST http://ai:8103/embed`, header `X-Internal-Auth: ${INTERNAL_AUTH_TOKEN}`.
- Batch input, cache hash `sha256(text)` trong Redis `cache:embed:{hash}` TTL 1 ngày.
- Vector dim **= 384** (`sentence-transformers/all-MiniLM-L6-v2`). Cột PG: `embedding vector(384)`.

---

## 5. Inter-service REST

| Caller     | Callee      | Endpoint                            | Auth                              |
| ---------- | ----------- | ----------------------------------- | --------------------------------- |
| Core       | AI          | `POST /embed`                       | Header `X-Internal-Auth: <token>` |
| Core       | AI          | `POST /grade-short-answer-semantic` | Header `X-Internal-Auth: <token>` |
| Core       | Proctoring  | `GET /api/v1/attempts/{id}/summary` (fallback Kafka)  | JWT Bearer (forward user token)   |
| Mọi service| Auth        | `GET /.well-known/jwks.json`        | Public, cache 1h                  |
| Web        | Auth        | `POST /api/v1/auth/{register,login,refresh,logout}` | None / Bearer        |
| Web        | Core        | `/api/v1/...`                       | Bearer                            |
| Web        | Proctoring  | `/api/v1/alerts...` (teacher)       | Bearer                            |

**Cấm**:
- AI → Core, AI → Proctoring (AI chỉ producer/consumer Kafka, không gọi REST sang service khác).
- Core → Auth (Core không gọi Auth runtime; chỉ verify JWT qua JWKS).
- Proctoring → Core (chỉ publish Kafka).

---

## 6. Kafka topic — danh mục đầy đủ

| #   | Topic                              | Producer    | Consumer     | Partition key      | Outbox? |
| --- | ---------------------------------- | ----------- | ------------ | ------------------ | ------- |
| 1   | `exam.answer.submitted.v1`         | Core        | Core         | `attempt_id`       | ✅      |
| 2   | `exam.attempt.submitted.v1`        | Core        | Core         | `attempt_id`       | ✅      |
| 3   | `grading.request.v1`               | Core        | AI           | `request_id`       | ✅      |
| 4   | `grading.result.v1`                | AI          | Core         | `request_id`       | ❌ (AI stateless) |
| 5   | `cheat.event.raw.v1`               | Core (WS)   | Proctoring   | `attempt_id`       | ❌ (advisory, at-most-once) |
| 6   | `cheat.alert.v1`                   | Proctoring  | Core         | `attempt_id`       | ✅      |
| 7   | `question.generation.request.v1`   | Core        | AI           | `job_id`           | ✅      |
| 8   | `question.generation.result.v1`    | AI          | Core         | `job_id`           | ❌      |
| 9   | `tutor.explanation.request.v1`     | Core        | AI           | `request_id`       | ✅      |
| 10  | `tutor.explanation.result.v1`      | AI          | Core         | `request_id`       | ❌      |

Schema JSON ở `shared-contracts/events/<topic>.schema.json`.

Topic settings:
- Partitions: 3 (DATN dev).
- Replication factor: 1 (DATN dev).
- `cleanup.policy=delete`, `retention.ms=604800000` (7d).

---

## 7. Database — quy tắc khi viết migration / query

- 3 schema cô lập: `auth`, `core`, `proctoring`. **Tuyệt đối không** cross-schema FK / JOIN ghi.
  - AI service đọc `core.documents.text_content`, `core.questions.embedding` qua role `ai_reader` (SELECT only) + UPDATE `core.ai_cache` + `core.student_writing_profiles`.
- Mọi PK = `UUID DEFAULT uuid_generate_v4()` (trừ outbox dùng `BIGSERIAL`).
- Mọi bảng business có `created_at`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT now()`. Trigger `set_updated_at` ở §7 `schema.sql`.
- ENUM bằng string CHECK constraint (đã có sẵn — không tạo PG `CREATE TYPE` mới khi không thảo luận).
- pgvector: cột `embedding vector(384)`, index `USING ivfflat (embedding vector_cosine_ops) WITH (lists=100)` cho table > 1000 row.
- JSONB cho `content` câu hỏi, `answer_data`, `metadata`. Validate shape ở app layer (Pydantic / Bean Validation).
- Migration mới: file Flyway `V{YYYYMMDDHHMM}__<desc>.sql` trong `services/{auth|core|proctoring}/src/main/resources/db/migration/`. **Không sửa `database/postgresql/schema.sql`** — đó là baseline dev/demo, đã lock.

---

## 8. Convention layout source code

### 8.1. Java service (Auth / Core / Proctoring)

```
services/{name}/
├── build.gradle.kts
├── src/main/java/vn/smartquiz/{name}/
│   ├── {Name}Application.java                  # @SpringBootApplication
│   ├── api/                                    # Controller + DTO request/response
│   │   ├── {Feature}Controller.java
│   │   └── dto/
│   ├── domain/                                 # Entity + value object + enum
│   │   ├── {Aggregate}.java
│   │   └── ...
│   ├── service/                                # Application service (business)
│   │   └── {Feature}Service.java
│   ├── repository/                             # Spring Data JPA + JdbcTemplate
│   │   └── {Aggregate}Repository.java
│   ├── infra/                                  # Outbox relayer, Kafka, Redis, JWKS, external client
│   │   ├── kafka/
│   │   ├── outbox/
│   │   ├── redis/
│   │   └── client/
│   ├── config/                                 # @Configuration beans
│   └── error/                                  # GlobalExceptionHandler + ErrorCode enum
├── src/main/resources/
│   ├── application.yml
│   └── db/migration/V*__*.sql                  # Flyway
└── src/test/java/vn/smartquiz/{name}/...
```

- Package root: `vn.smartquiz.{auth|core|proctoring}`.
- DTO request/response **luôn** là record với Bean Validation (`@NotNull`, `@Size`).
- Repository: ưu tiên Spring Data JPA. JdbcTemplate cho pgvector + complex query.
- Một feature = (Controller + Service + Repository), không "FacadeManagerHelper".

### 8.2. Python service (AI)

```
services/ai/
├── pyproject.toml                              # uv-managed
├── app/
│   ├── main.py                                 # FastAPI factory
│   ├── api/
│   │   ├── grading.py
│   │   ├── generation.py
│   │   ├── tutor.py
│   │   ├── detector.py
│   │   └── embedding.py
│   ├── schemas/                                # Pydantic v2 models
│   ├── services/                               # Business: prompt, retrieval, detector
│   ├── llm/                                    # LlmRouter, Gemini provider, Ollama provider
│   ├── kafka/                                  # aiokafka consumer + producer
│   ├── cache/                                  # PG ai_cache adapter
│   ├── models/                                 # MiniLM, distilgpt2 wrappers
│   ├── config.py                               # pydantic-settings từ env / config.yaml
│   └── observability.py                        # logging JSON + prometheus instrumentator
└── tests/
```

- `ruff` + `black` + `mypy strict-optional`.
- Pydantic v2 cho mọi I/O boundary (REST + Kafka payload).

### 8.3. Web (Next.js)

```
web/
├── package.json
├── app/                                        # App Router
│   ├── (auth)/
│   ├── (student)/
│   ├── (teacher)/
│   └── (admin)/
├── components/
├── lib/
│   ├── api/                                    # SDK gen từ OpenAPI hoặc fetch wrapper
│   ├── auth/                                   # Token storage, refresh logic
│   └── ws/                                     # Attempt WebSocket client
└── tests/
```

- `tsc --strict`. Không `any` không justify.
- Component tách theo composition (xem skill `composition-patterns`), không boolean-prop proliferation.

---

## 9. Per-service codegen checklist

### 9.1. Auth — khi gen feature mới

- [ ] Đọc `docs/auth-service-design.md` §3 (REST) + §4 (JWT spec) + §6 (security).
- [ ] Endpoint nhạy cảm có rate limit Redis (login: 10/min/IP).
- [ ] Password = BCrypt cost 12 (KHÔNG plain / KHÔNG MD5 / SHA1).
- [ ] Refresh token: chỉ lưu `sha256(raw)`, rotation + reuse detection.
- [ ] Response login fail thống nhất (anti-enumeration).
- [ ] Không publish Kafka event (DATN scope).
- [ ] Test: Testcontainers PG + Redis; full flow register→login→refresh→me→logout.

### 9.2. Core — khi gen feature mới

- [ ] Đọc `docs/core-service-design.md` mục liên quan + `database/postgresql/schema.sql` §4.
- [ ] State-change attempt → fencing `state_version` (xem §4.4 file này).
- [ ] Mọi side-effect cần outbox (answer submit, attempt submit, grading.request, tutor.explanation.request, question.generation.request).
- [ ] Consumer Kafka → dedupe `processed_events` (§4.3 file này).
- [ ] Endpoint teacher: `@PreAuthorize("hasRole('TEACHER')")` + check ownership (creator của exam) trừ ADMIN.
- [ ] Endpoint student: check `student_id = #userId` (only own data).
- [ ] WS handler: forward `CHEAT_EVENT` → publish `cheat.event.raw.v1` **không qua outbox** (advisory).
- [ ] Document upload: whitelist MIME, max 20MB, scan magic number.
- [ ] Question CRUD: tính embedding qua AI service `/embed`, cache Redis 1d.
- [ ] **Cấm**: gọi LLM trực tiếp, detect cheat trong Core, verify password.
- [ ] Test: Testcontainers PG + Kafka + Redis; cover race attempt suspend/submit, outbox ordering per partition, idempotent consumer.

### 9.3. AI — khi gen feature mới

- [ ] Đọc `docs/ai-service-design.md` mục liên quan.
- [ ] Mọi LLM call qua `LlmRouter` (retry + cache + metric + tier 1→tier 2→degraded).
- [ ] Cache key = canonical sha256 (xem §4.5 ai-service-design — `sort_keys=True`, bump `PROMPT_VERSION` khi đổi template).
- [ ] Pydantic v2 schema cho request/response + LLM structured output validation (retry 1 lần nếu fail).
- [ ] Embedding = `all-MiniLM-L6-v2` 384d, batch 32.
- [ ] Consumer Kafka aiokafka, manual commit sau write `ai_cache` + send result.
- [ ] **Cấm**: ghi `core.attempt_answers` / `core.questions` trực tiếp (chỉ publish event); cache trong memory process; gọi LLM không qua `LlmRouter`.
- [ ] Internal REST endpoint check header `X-Internal-Auth`.
- [ ] Detector hybrid score: perplexity + burstiness + stylometry (formula §5.2 ai-service-design).
- [ ] Test: VCR cassette cho LLM call; jsonschema validate event payload.

### 9.4. Proctoring — khi gen feature mới

- [ ] Đọc `docs/proctoring-service-design.md`.
- [ ] Rule = class implement `CheatRule.apply(event, window, config) → Optional<Alert>`. Mỗi rule độc lập, unit-testable.
- [ ] Window state Redis key `proctoring:window:{attempt_id}`; nếu miss → rebuild từ `cheat_events` query.
- [ ] Session warm-up 10s đầu attempt → skip mọi alert.
- [ ] Alert debounce 30s cùng rule + dedupe `UNIQUE(attempt_id, rule_name)` qua Redis NX.
- [ ] Publish alert via outbox `cheat.alert.v1`.
- [ ] **Cấm**: update `core.exam_attempts.status=SUSPENDED` (chỉ Core quyết); kill WS; gọi AI để hỏi "có cheat không"; phân tích nội dung essay.
- [ ] Test: feed sequence event → expected alerts; replay không duplicate; window rebuild đúng.

### 9.5. Web — khi gen UI mới

- [ ] React 19 + Next 15 App Router. Server component mặc định, `"use client"` chỉ khi cần.
- [ ] Composition over boolean props (skill `composition-patterns`).
- [ ] Performance: skill `react-best-practices` (dynamic import, cascade useEffect, bundle split).
- [ ] Form: client-side validation + server response error envelope (§4.6).
- [ ] Bearer token storage: in-memory (Zustand / Context) cho access; refresh token httpOnly cookie hoặc localStorage (DATN chấp nhận localStorage, document đã note).
- [ ] WebSocket attempt: 1 connection per attempt, heartbeat 10s, auto-reconnect với exponential backoff.
- [ ] Accessibility: skill `web-design-guidelines`.

---

## 10. Boundary violations (đỏ — push back ngay)

| Vi phạm                                                            | Lý do                                       |
| ------------------------------------------------------------------ | ------------------------------------------- |
| Service X ghi vào schema service Y                                 | Cross-schema write cấm                      |
| AI service gọi LLM ngoài `LlmRouter`                               | Mất cache, mất metric, mất fallback         |
| Core gọi LLM/OpenAI/Gemini trực tiếp                               | LLM cost + concern phải centralized ở AI    |
| Core triển khai thuật toán detect cheat                            | Proctoring là owner                          |
| Auth publish Kafka event                                           | DATN scope: Auth không emit event           |
| Publish Kafka không qua outbox (trừ `cheat.event.raw.v1` từ WS)    | Mất RPO 30s guarantee                       |
| Consumer Kafka không dedupe `processed_events`                     | Duplicate processing                        |
| Verify password / issue JWT ở service ≠ Auth                       | Auth là IDP duy nhất                        |
| Thêm `org_id` vào bảng / event / JWT                               | Single-tenant lock                          |
| Đổi cột schema mà chưa rà cross-service impact                     | Schema shared giữa nhiều consumer           |
| Sửa `docs/*-service-design.md` khi sửa code                        | Design doc là source of truth cho behavior  |
| Tạo file `*.md` mới (plan/summary/decision) không được yêu cầu     | Bám CLAUDE.md interaction rule              |

---

## 11. Observability minimum (DATN scope)

Mỗi service Java bắt buộc:
- `/actuator/health` + `/actuator/prometheus` (Micrometer).
- Log JSON stdout với MDC `requestId`, `userId`, attempt-specific id.
- Metric custom theo design doc service (tên metric đã lock trong từng file `*-service-design.md` mục Observability).

FastAPI:
- `prometheus-fastapi-instrumentator` ở `app/observability.py`.
- Log JSON qua `structlog` / `logging.config.dictConfig`.

Header bắt buộc: forward `X-Request-Id` giữa các call (sinh UUID nếu vắng).

OpenTelemetry / Loki / Tempo là **future work** — đừng tự thêm vào DATN scope.

---

## 12. Config & secret

- File config: `application.yml` (Java) / `config.yaml` + env (Python).
- **Secret** (DB password, LLM API key, internal auth token): chỉ qua env, không hardcode.
  - Dev: `.env` ở `ops/llm-api-keys.env` (gitignored), `ops/db.env`.
  - Prod (DATN): vẫn env, không Vault.
- JWT keypair: `ops/jwt/private.pem` (Auth only), `ops/jwt/public.pem` (phát qua JWKS).

---

## 13. Khi không chắc — checklist quyết định

1. **Có vi phạm 1 trong 7 NFR (§3)?** → Không. Nếu có → push back.
2. **Có vi phạm 1 trong các boundary §10?** → Không. Nếu có → push back.
3. **Service nào sở hữu logic này?** → Map về service map §1. Owner viết, không service khác.
4. **State change quan trọng (attempt status, refresh token, alert)?** → Có cần fencing + outbox + idempotent consumer không?
5. **Đã có test (unit tối thiểu + integration nếu chạm DB/Kafka)?**
6. **Có thay đổi cross-service contract (REST endpoint, Kafka topic, JWT claim, schema cột)?** → Cần update `shared-contracts/` tương ứng, không chỉ code.
7. **Có generated file `*.md` planning/summary không cần thiết?** → Xoá.

---

## 14. Stub khi bắt đầu service mới (Java)

`build.gradle.kts` skeleton:

```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.3.5"
    id("io.spring.dependency-management") version "1.1.6"
    id("com.diffplug.spotless") version "6.25.0"
    checkstyle
    jacoco
}

java { toolchain.languageVersion = JavaLanguageVersion.of(21) }

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.kafka:spring-kafka")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.kafka:spring-kafka-test")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:kafka")
    testImplementation("org.testcontainers:junit-jupiter")
}
```

`application.yml` skeleton:

```yaml
server.port: 81xx                # 8101/8102/8104

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/smartquiz?currentSchema={schema}
    username: ${DB_USER}
    password: ${DB_PASSWORD}
  jpa:
    hibernate.ddl-auto: validate
    properties.hibernate.default_schema: {schema}
  flyway:
    schemas: {schema}
    default-schema: {schema}
  data.redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
  kafka:                                                  # Core + Proctoring; Auth bỏ
    bootstrap-servers: ${KAFKA_BOOTSTRAP:localhost:9092}
    consumer:
      group-id: {service}
      enable-auto-commit: false
      properties.isolation.level: read_committed
    producer:
      acks: all
      properties.enable.idempotence: true

management.endpoints.web.exposure.include: health,prometheus
management.endpoint.health.probes.enabled: true

smartquiz:
  auth.jwks-url: ${AUTH_JWKS_URL:http://localhost:8101/.well-known/jwks.json}
```

---

## 15. Lịch sử

- 2026-05-20 — bản đầu, lock 4 service, 1 PG, 10 topic, NFR §3.
