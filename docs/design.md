# SmartQuizSystem — Kiến trúc tổng (DATN)

> **Scope**: Tài liệu này mô tả kiến trúc thực tế được implement trong khuôn khổ đồ án tốt nghiệp.
> Phạm vi tính năng đã chốt ở `docs/scope-datn.md`, quyết định kiến trúc ở `docs/adr/ADR-003-datn-scope.md`.
> Thiết kế production đầy đủ được lưu trong `docs/archive/production-design/` để tham chiếu.

## 1. Mục tiêu hệ thống

Nền tảng thi trắc nghiệm + tự luận trực tuyến cho lớp học, có:

1. Tạo đề, làm bài, chấm tự động (MCQ đồng bộ, Essay async qua AI).
2. Phát hiện gian lận thời gian thực (L1–L3: tab switch, paste, timing).
3. Ba tính năng AI nổi bật:
   - Upload tài liệu → AI sinh đề thi.
   - AI tutor giải thích sau câu trả lời sai.
   - AI-generated essay detector (chống ChatGPT).

## 2. Sơ đồ kiến trúc

```
                          ┌─────────────┐
                          │  Next.js 15 │
                          │  (Web)      │
                          └──────┬──────┘
                                 │ HTTPS + WebSocket
                     ┌───────────┴────────────┐
                     ▼                         ▼
              ┌─────────────┐          ┌──────────────────┐
              │  Auth       │          │  Core            │
              │  :8101      │          │  :8102           │
              │  (Java/SB)  │  JWT RS256│ (Java/SB)        │
              │  IDP        │◄─verify──┤  Exam/Question/  │
              │  JWKS       │  via JWKS│  Attempt/        │
              └─────────────┘          │  Analytics/      │
                                       │  WS session      │
                                       └──────┬───────────┘
                                              │
                                    Kafka (JSON events)
                         ┌────────────────────┴────────────────────┐
                         ▼                                          ▼
                 ┌────────────────┐                        ┌────────────────┐
                 │  AI            │                        │  Proctoring    │
                 │  :8103         │                        │  :8104         │
                 │  (Python/      │                        │  (Java/SB)     │
                 │  FastAPI)      │                        │  Cheat L1–L3   │
                 │  • Grade essay │                        │  + alert       │
                 │  • Gen Q       │                        │    aggregator  │
                 │  • Tutor       │                        └────────────────┘
                 │  • AI detect   │
                 └────────────────┘

            Datastores:  PostgreSQL (pgvector) · Redis · Kafka
            Storage:     Local disk mount ./data/uploads/
```

## 3. Service map

| Service     | Ngôn ngữ                | Port | DB schema                    | Trách nhiệm                                                          |
| ----------- | ----------------------- | ---- | ---------------------------- | -------------------------------------------------------------------- |
| Auth        | Java 21 / Spring Boot   | 8101 | `auth`                       | Register, login, JWT RS256 issuer, JWKS, refresh token rotation      |
| Core        | Java 21 / Spring Boot   | 8102 | `core`                       | Exam/Question CRUD, Attempt lifecycle, grading orchestration, analytics view, WebSocket phiên thi, outbox relayer, document upload |
| AI          | Python 3.12 / FastAPI   | 8103 | — (stateless, dùng pgvector chung) | Chấm essay, sinh câu hỏi, tutor explain, AI essay detection, embedding |
| Proctoring  | Java 21 / Spring Boot   | 8104 | `proctoring`                 | Consumer cheat event, detector L1/L2/L3, alert aggregator            |
| Web         | Next.js 15 / React 19   | 3000 | —                            | UI student + teacher + admin                                         |

Cả 4 service dùng **1 PG instance chung** (image `pgvector/pgvector:pg16`),
schema isolation để đạt tinh thần database-per-service mà không phải chạy 4 PG.

## 4. Nguyên tắc kiến trúc đã lock

| Nguyên tắc                            | Áp dụng                                                         |
| ------------------------------------- | --------------------------------------------------------------- |
| **Single-tenant**                     | Không có `org_id`. Một hệ cho một trường/lớp.                   |
| **JWT RS256 + JWKS**                  | Auth là IDP. Các service khác verify token qua JWKS cache 1h.   |
| **Transactional outbox**              | Core ghi đáp án + outbox row trong 1 TX PG. Relayer poll 5s.    |
| **State version fencing**             | `exam_attempts.state_version BIGINT` chống race suspend/submit. |
| **Idempotent Kafka consumer**         | Dedupe `event_id` qua bảng `processed_events` per schema.       |
| **JSON event** (không Avro)           | Schema lưu ở `shared-contracts/events/*.schema.json`.           |
| **REST inter-service** (không gRPC)   | Mỗi service có OpenAPI 3.1 spec trong `shared-contracts/openapi/`. |
| **RPO đáp án ≤ 30s**                  | Đạt bởi outbox + relayer poll 5s.                               |

## 5. Luồng nghiệp vụ chính

### 5.1. Flow làm bài thi (critical path)

```
1. Student đăng nhập         → Auth issue access_token (15m) + refresh (7d rotate)
2. Load list exam            → Core GET /api/v1/exams?assigned=me
3. Start attempt             → Core POST /api/v1/exams/{id}/start
                               → tạo exam_attempts (state=IN_PROGRESS, state_version=0)
                               → open WebSocket /ws/attempts/{attempt_id}
                               → trả về câu hỏi 1 + server time
4. Submit answer (mỗi câu)   → Core POST /api/v1/attempts/{id}/answers
                               → TX: UPDATE attempt_answers + INSERT outbox(exam.answer.submitted.v1)
                               → commit
                               → relayer publish Kafka
                               → analytics view refresh async
5. Cheat signal (WS client)  → Core WS handler forward Kafka cheat.event.raw.v1
                               → Proctoring detect → publish cheat.alert.v1 nếu vượt ngưỡng
                               → Core consume → WS push cho coi thi + student warning
6. Submit attempt            → Core POST /api/v1/attempts/{id}/submit
                               → TX: state=SUBMITTED, state_version++, INSERT outbox(exam.attempt.submitted.v1)
                               → chấm MCQ đồng bộ (so sánh correct_answer)
                               → publish grading.request.v1 cho mỗi essay answer (need_ai_detection=true)
                               → publish tutor.explanation.request.v1 cho mỗi wrong answer
7. AI service xử lý          → chấm essay + detect AI → grading.result.v1
                               → giải thích từng câu sai → tutor.explanation.result.v1
8. Core consume kết quả      → UPDATE attempt_answers.score/ai_explanation/ai_detection_*
                               → WS push "result ready" cho student
9. Student xem kết quả       → Core GET /api/v1/attempts/{id}/result
                               → hiển thị điểm + explanation inline + detector badge (giáo viên view)
```

### 5.2. Flow teacher sinh đề từ tài liệu (feature nổi bật)

```
1. Teacher upload PDF        → Core POST /api/v1/documents/upload (multipart)
                               → lưu file vào ./data/uploads/{uuid}.pdf
                               → INSERT documents (status=EXTRACTING)
                               → async: extract text (Apache Tika trong Core) → UPDATE status=READY
2. Teacher config gen        → Core POST /api/v1/questions/generate
                               { document_id, topic, count, difficulty, type }
                               → INSERT question_generation_jobs (status=QUEUED)
                               → publish question.generation.request.v1
                               → trả về {job_id} (202 Accepted)
3. AI xử lý                  → consumer pick up
                               → load document.text_content
                               → chunking + embedding + top-k retrieval theo topic
                               → LLM call structured output (Pydantic schema)
                               → validate + embed each Q → dedupe pgvector (cosine < 0.92)
                               → publish question.generation.result.v1
4. Core consume              → INSERT questions (ai_generated=true, source_document_id, embedding)
                               → UPDATE question_generation_jobs status=DONE
                               → WS push "job done" cho teacher UI
5. Teacher preview           → GET /api/v1/questions/generate/{job_id}
                               → UI hiển thị danh sách câu, teacher chọn/sửa/xóa
                               → POST /api/v1/questions/generate/{job_id}/commit (chọn câu lưu vĩnh viễn)
```

## 6. Danh mục Kafka topic

Đầy đủ 10 topic, payload JSON, version qua tên (`.v1`). Schema JSON ở
`shared-contracts/events/`.

| Topic                                  | Producer   | Consumer     |
| -------------------------------------- | ---------- | ------------ |
| `exam.answer.submitted.v1`             | Core       | Core         |
| `exam.attempt.submitted.v1`            | Core       | Core         |
| `grading.request.v1`                   | Core       | AI           |
| `grading.result.v1`                    | AI         | Core         |
| `cheat.event.raw.v1`                   | Core (WS)  | Proctoring   |
| `cheat.alert.v1`                       | Proctoring | Core         |
| `question.generation.request.v1`       | Core       | AI           |
| `question.generation.result.v1`        | AI         | Core         |
| `tutor.explanation.request.v1`         | Core       | AI           |
| `tutor.explanation.result.v1`          | AI         | Core         |

**Partition strategy**:
- `exam.*`, `cheat.*` — partition key = `attempt_id` (single-partition per attempt để preserve order).
- `grading.*`, `tutor.*` — partition key = `request_id` (unrelated order).
- `question.generation.*` — partition key = `job_id`.

## 7. Bảo mật

| Vấn đề                  | Giải pháp                                                               |
| ----------------------- | ----------------------------------------------------------------------- |
| AuthN                   | JWT RS256, keypair sinh bằng `ops/gen-jwt-keypair.sh`, public key phát qua JWKS |
| AuthZ                   | Role trong JWT claim (`STUDENT`, `TEACHER`, `ADMIN`). Spring Security `@PreAuthorize`. |
| Token refresh           | Refresh token xoay vòng, revoke on logout                               |
| Password hash           | BCrypt cost 12                                                          |
| CSRF (state-changing REST) | SameSite=Lax cookie + `X-Requested-With` header check trên endpoint cookie-auth (nếu dùng). Với token bearer thì không cần. |
| XSS                     | React escape mặc định, không dùng `dangerouslySetInnerHTML` trừ markdown đã sanitize (DOMPurify) |
| SQL injection           | JPA/Hibernate parameterized query                                       |
| File upload             | Whitelist MIME `application/pdf`, `application/vnd.openxmlformats-officedocument.wordprocessingml.document`; size max 20MB; scan magic number |
| Rate limit              | Spring Security filter cơ bản (10 login/min/IP, 100 req/min/user)       |
| LLM prompt injection    | Strip user text vượt > 4k ký tự, detect jailbreak pattern thô, system prompt rõ ràng |

## 8. Quan sát được (observability)

**DATN scope**: đủ để debug demo, không full production.

- **Log**: SLF4J JSON format qua Logback, stdout. MDC: `requestId`, `userId`, `attemptId`.
- **Metric**: Micrometer + `/actuator/prometheus` trên mỗi service Java. FastAPI dùng `prometheus-fastapi-instrumentator`.
- **Trace**: bỏ OpenTelemetry cho DATN. Dùng `X-Request-Id` header truyền tay giữa service.
- **Dashboard**: optional, có Prometheus + Grafana trong `docker-compose.obs.yml` bật khi cần.

## 9. Môi trường dev

```bash
# 1. Sinh JWT keypair (chỉ lần đầu)
bash ops/gen-jwt-keypair.sh

# 2. Copy env template
cp ops/llm-api-keys.example.env ops/llm-api-keys.env
# (điền GEMINI_API_KEY hoặc ANTHROPIC_API_KEY)

# 3. Bật infra
docker compose -f infra/docker-compose.dev.yml up -d
bash ops/kafka/create-topics.sh

# 4. Run service (4 terminal)
cd services/auth       && ./gradlew bootRun
cd services/core       && ./gradlew bootRun
cd services/proctoring && ./gradlew bootRun
cd services/ai         && uv run uvicorn app.main:app --reload --port 8103

# 5. Run web
cd web && pnpm dev
```

## 10. Deployment (DATN scope)

Target: chạy được trên 1 VM hoặc laptop cho demo.

- **Option A**: `docker compose -f infra/docker-compose.prod.yml up -d` build tất cả service → 1 VM.
- **Option B**: Render.com / Fly.io / Railway free tier — 1 service/container.
- Không cần Kubernetes, không cần CI/CD pipeline công phu (GitHub Actions build + test là đủ).

## 11. Ranh giới service (để tránh drift khi code)

| Service    | Không bao giờ                                                      |
| ---------- | ------------------------------------------------------------------ |
| Auth       | Không biết về exam/attempt; chỉ quản user + token                  |
| Core       | Không chấm essay tự chạy LLM; phải gọi qua AI service               |
| Core       | Không detect cheating; chỉ forward raw event sang Proctoring       |
| AI         | Không đọc/ghi `exam_attempts`, `users` trực tiếp                    |
| AI         | Không biết về Core's outbox; chỉ producer/consumer Kafka của mình   |
| Proctoring | Không biết về question/exam nội dung; chỉ xử lý event + session    |

Nếu thấy vi phạm khi scaffold → push back và thảo luận lại boundary.

## 12. Doc liên quan

- **Scope**: `docs/scope-datn.md`
- **DB**: `docs/database.md`
- **Service design**: `docs/{auth,core,ai,proctoring}-service-design.md`
- **ADR**: `docs/adr/ADR-00{1..7}-*.md`
- **Operations**: `docs/RUNBOOK.md`
- **Archive production**: `docs/archive/production-design/`
