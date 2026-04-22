# SmartQuizSystem — Scope DATN

**Mục đích**: Tài liệu này chốt phạm vi implement trong khuôn khổ đồ án tốt nghiệp.
Các thiết kế production-ready trong `docs/*-service-design.md` được giữ nguyên
làm **tài liệu tham chiếu kiến trúc đầy đủ**; doc này định nghĩa phần **thực sự
được code** và phần để lại cho future work.

- **Status**: Accepted
- **Date**: 2026-04-22
- **Related**: `ADR-003-datn-scope.md`, tất cả `docs/*-service-design.md`

## 1. Mục tiêu DATN

1. Chạy được end-to-end một flow thi trực tuyến (login → chọn đề → làm → chấm → xem điểm).
2. **Điểm sáng AI (Combo A — AI-heavy)**:
   - Chấm essay tự động qua LLM.
   - Sinh đề thi từ câu hỏi thô theo topic/difficulty.
   - **Upload tài liệu (PDF/DOCX) → AI sinh đề thi tự động** (feature nổi bật §9.1).
   - **AI tutor giải thích sau câu trả lời sai** với gợi ý học thêm (§9.2).
   - **AI-generated essay detector** — chống học sinh copy từ ChatGPT (§9.3).
3. Có cơ chế phát hiện gian lận thời gian thực L1–L3 (tab, paste, timing).
4. Kiến trúc đủ "microservice" để bảo vệ hội đồng, không phải monolith một file.
5. Defend được các kỹ thuật: JWT + JWKS, outbox pattern, state fencing, idempotent consumer, WebSocket real-time, AI integration, **embedding-based dedupe (pgvector), prompt engineering + structured output, stylometry**.

**Không mục tiêu**: production scale, multi-tenancy, multi-region, SLA 99.9%,
video proctoring, IRT calibration, A/B experiment.

## 2. Kiến trúc 4 service (giảm từ 6)

```
┌────────────┐    ┌───────────────────────────────┐    ┌──────────────┐
│  Auth      │    │  Core (Java/Spring Boot)      │    │  AI          │
│  (Java)    │    │                               │    │  (Python)    │
│  JWT IDP   │◄───┤  • Exam CRUD                  │───►│  FastAPI     │
│  RS256     │    │  • Question CRUD (PG JSONB)   │    │  • Essay     │
│  JWKS      │    │  • Attempt + Grading          │    │    grading   │
│            │    │  • Outbox relayer             │    │  • Question  │
│            │    │  • Analytics (view trong PG)  │    │    generation│
└────────────┘    │  • WebSocket phiên thi        │    └──────┬───────┘
                  └───────────┬───────────────────┘           │
                              │ Kafka                         │
                              ▼                               │
                  ┌───────────────────────────────┐           │
                  │  Proctoring (Java)            │           │
                  │  • Cheat L1–L3 (tab, paste,   │           │
                  │    timing)                    │           │
                  │  • Alert aggregator           │           │
                  └───────────────────────────────┘           │
                                                              │
                        Core ──grading.request──► AI ◄────────┘
                        Core ◄──grading.result── AI
```

**Service ownership**:

| Service    | Ngôn ngữ          | DB owned        | Port  |
| ---------- | ----------------- | --------------- | ----- |
| Auth       | Java 21 / SB 3.3  | PG: users, refresh_tokens | 8101 |
| Core       | Java 21 / SB 3.3  | PG: exams, questions (+embedding), exam_attempts, attempt_answers (+ai_explanation, ai_detection), documents, outbox, processed_events, analytics_* views | 8102 |
| AI         | Python 3.12 / FastAPI | — (stateless; gọi LLM API + HuggingFace model cục bộ cho perplexity) | 8103 |
| Proctoring | Java 21 / SB 3.3  | PG: cheat_events, cheat_alerts, proctoring_sessions | 8104 |
| Web        | Next.js 15        | —               | 3000 |

**Note**: Tất cả service dùng **1 PG instance chung** với **schema riêng cho từng service**
(`auth`, `core`, `proctoring`). Outbox + processed_events nằm trong schema của service ghi nó.
Schema isolation đủ để demo tinh thần "database-per-service" mà không phải chạy 4 PG.

## 3. Tech stack (diff với doc production)

| Layer            | Production design              | DATN scope                      |
| ---------------- | ------------------------------ | ------------------------------- |
| Số service       | 6                              | **4**                           |
| Question store   | MongoDB                        | **PG JSONB**                    |
| Analytics store  | ClickHouse                     | **PG views / materialized views** |
| Search           | Elasticsearch                  | **PG `tsvector` full-text**     |
| Vector store     | (không có)                     | **pgvector extension** — dedupe câu hỏi sinh từ AI + stylometry |
| File storage     | MinIO/S3                       | **Local disk** `./data/uploads/` (mount vào container) |
| Cheating stream  | Flink                          | **Spring Boot Kafka consumer**  |
| Inter-service RPC| gRPC + Avro                    | **REST + JSON**                 |
| Event schema     | Avro + Apicurio Registry       | **JSON + version trong tên topic** |
| Multi-tenancy    | `org_id` mọi bảng              | **Single-tenant, không có `org_id`** |
| Kafka            | Giữ                            | Giữ (3 node local, 6 topic)     |
| Redis            | Giữ                            | Giữ (cache + session + WS pubsub)|
| Frontend         | Next.js 15                     | Giữ                             |
| Auth             | JWT RS256 + JWKS               | **Giữ**                         |
| Outbox pattern   | Có                             | **Giữ (đơn giản hóa)**          |
| State fencing    | `state_version BIGINT`         | **Giữ**                         |
| Observability    | OTel + Loki + Prometheus       | Prometheus + log console đủ cho demo |

## 4. NFR — diff

| NFR                         | Production | DATN                         |
| --------------------------- | ---------- | ---------------------------- |
| SLA                         | 99.9%      | Best-effort, có thể restart  |
| RPO đáp án                  | ≤ 5s       | **≤ 30s** (relayer poll 5s)  |
| RTO                         | < 5 min    | Không cam kết                |
| Fencing token               | Bắt buộc   | **Bắt buộc** (rẻ, defend được) |
| Idempotent consumer         | Bắt buộc   | **Bắt buộc**                 |
| JWT RS256 + JWKS            | Bắt buộc   | **Bắt buộc**                 |
| JWKS key rotation SOP       | Cần        | Không cần (không rotate)     |
| Backup PG                   | Daily + PITR| `pg_dump` thủ công thỉnh thoảng |

## 5. Feature matrix

### ✅ Giữ (phải implement)

**Auth**
- Đăng ký, đăng nhập (email + password, BCrypt)
- JWT RS256 access token (15m) + refresh token (7d, rotate)
- JWKS endpoint `/.well-known/jwks.json`
- Role: `STUDENT`, `TEACHER`, `ADMIN`

**Core**
- CRUD exam (giáo viên tạo đề)
- CRUD câu hỏi (MCQ, True/False, Essay) — PG JSONB
- Snapshot câu hỏi vào `exam_questions` khi publish exam (pin version)
- Attempt lifecycle: START → IN_PROGRESS → SUBMITTED → GRADED
- `state_version` fencing
- Lưu đáp án qua outbox (gọn: PG transaction ghi `attempt_answers` + `outbox` row)
- Chấm MCQ tự động đồng bộ, chấm Essay async qua AI service
- WebSocket phiên thi: heartbeat, push cheat alert, push time remaining
- Trang kết quả + thống kê cơ bản cho giáo viên (tỉ lệ đúng, phân phối điểm, histogram thời gian)

**AI** (xem chi tiết §9)
- Endpoint `POST /grade-essay` — nhận (đề bài, đáp án học sinh, rubric) → điểm + feedback, gọi OpenAI/Gemini.
- Endpoint `POST /generate-questions` — nhận (chủ đề, độ khó, số lượng, loại) → list câu hỏi.
- **Endpoint `POST /generate-questions-from-document`** — nhận `document_id`, extract text, sinh đề theo cấu hình.
- **Endpoint `POST /explain-answer`** — giải thích câu trả lời sai + gợi ý ôn tập.
- **Endpoint `POST /detect-ai-essay`** — trả về AI-generated probability + breakdown lý do.
- Consumer Kafka: `grading.request.v1`, `question.generation.request.v1`.
- Producer Kafka: `grading.result.v1`, `question.generation.result.v1`.
- Cache LLM response theo hash(prompt + model) để giảm token cost cho demo.

**Proctoring**
- Consumer `cheat.event.raw.v1` từ Core (WebSocket forward)
- Detector L1: tab-switch / blur window (> N lần → alert)
- Detector L2: paste detection (copy từ ngoài dán vào)
- Detector L3: timing anomaly (trả lời < threshold giây, hoặc tốc độ bất thường)
- Publish `cheat.alert.v1` → Core để push WS cho coi thi
- Bảng `cheat_events`, `cheat_alerts` trong PG

**Web (Next.js)**
- Trang login/register
- Dashboard student: list exam, join exam
- Trang làm bài (với WS timer + alert)
- **Trang kết quả student: xem điểm + AI tutor explanation inline** (expand để đọc giải thích từng câu sai)
- Dashboard teacher: tạo đề, list attempt, xem kết quả, duyệt cheat alert
- **Teacher tool "AI-powered question generator"**: upload PDF/DOCX → chọn topic/difficulty/count → preview câu hỏi sinh ra → chọn/sửa → save vào question bank
- **Teacher review essay**: hiển thị badge "⚠ AI-likely 78%" cho essay khả nghi
- Admin tạo user thô sơ

### ❌ Cắt (không implement, ghi rõ "future work")

- IRT/DIF calibration
- A/B experiment framework
- Cheat L4 (ML behavioral), L5 (video proctoring), L6 (graph analysis)
- Bulk import/export câu hỏi (Excel, Anki format...)
- Forgot password, email verification, MFA
- Multi-tenancy (`org_id`)
- Multi-region, disaster recovery
- Flink, ClickHouse, Elasticsearch, MongoDB
- gRPC, Avro, Apicurio
- Audit log chi tiết
- Rate limiting phức tạp (chỉ làm basic filter Spring Security)
- Report export PDF/Excel phức tạp

## 6. Kafka topics (v1 rút gọn)

| Topic                                | Producer   | Consumer      | Mục đích                                                |
| ------------------------------------ | ---------- | ------------- | ------------------------------------------------------- |
| `exam.answer.submitted.v1`           | Core       | Core          | Refresh analytics view (async)                          |
| `exam.attempt.submitted.v1`          | Core       | Core          | Trigger chấm bài + analytics                            |
| `grading.request.v1`                 | Core       | AI            | Yêu cầu chấm essay (có cờ `need_ai_detection`)          |
| `grading.result.v1`                  | AI         | Core          | Kết quả chấm + AI detection score → cập nhật DB         |
| `cheat.event.raw.v1`                 | Core (WS)  | Proctoring    | Raw event từ client (blur, paste, timing)               |
| `cheat.alert.v1`                     | Proctoring | Core          | Alert tổng hợp, Core forward qua WS cho coi thi         |
| `question.generation.request.v1`     | Core       | AI            | Yêu cầu sinh câu hỏi từ document/topic (§9.1)          |
| `question.generation.result.v1`      | AI         | Core          | Kết quả câu hỏi sinh ra, Core validate + lưu question bank |
| `tutor.explanation.request.v1`       | Core       | AI            | Yêu cầu AI giải thích câu trả lời sai (§9.2) — batch sau submit |
| `tutor.explanation.result.v1`        | AI         | Core          | Kết quả explain, Core push WS về student                |

**Event payload**: JSON với field bắt buộc `event_id` (UUID), `event_type`, `occurred_at`, `schema_version`, `payload`. Không dùng Avro.

**Dedupe**: consumer ghi `event_id` vào bảng `processed_events` trong cùng TX với hành động chính.

## 7. Data model — diff

### Drop

- Bảng `org`, mọi cột `org_id`
- Collection MongoDB `questions` → chuyển vào PG `questions.content JSONB`
- Bảng ClickHouse (`exam_facts`, `answer_analytics`, `cheat_analytics`, `question_irt_params`)
- Elasticsearch index

### Add / adjust

- `questions (id UUID, type ENUM, content JSONB, metadata JSONB, created_at, updated_at, version INT, ai_generated BOOLEAN DEFAULT false, source_document_id UUID NULL, embedding vector(384))`
  - `content` chứa câu hỏi + đáp án + rubric (nếu essay). JSONB đủ linh hoạt cho MCQ/TF/Essay.
  - `embedding` dùng cho dedupe câu hỏi AI sinh ra (pgvector, model `all-MiniLM-L6-v2` = 384d).
- `exam_questions (exam_id, question_id, question_version, position, points, snapshot JSONB)`
- `attempt_answers.answer_data JSONB`
- `attempt_answers.score`, `.feedback` — nullable, populate khi chấm xong
- **`attempt_answers.ai_explanation TEXT NULL`** — AI tutor explanation (§9.2)
- **`attempt_answers.ai_explanation_status VARCHAR(16) NULL`** — pending/ready/failed
- **`attempt_answers.ai_detection_score NUMERIC(5,4) NULL`** — 0–1 probability (§9.3)
- **`attempt_answers.ai_detection_method VARCHAR(32) NULL`** — `perplexity|stylometry|hybrid`
- **`attempt_answers.ai_detection_details JSONB NULL`** — breakdown (perplexity, burstiness, stylometry distance...)
- **`documents (id UUID PK, uploaded_by UUID REFERENCES users, filename TEXT, mime_type VARCHAR, storage_path TEXT, text_content TEXT, page_count INT, status VARCHAR(16), error_message TEXT NULL, created_at TIMESTAMPTZ DEFAULT now())`** — tài liệu giáo viên upload để sinh đề (§9.1)
- **`question_generation_jobs (id UUID PK, document_id UUID NULL, requested_by UUID, config JSONB, status VARCHAR(16), result_count INT, created_at, completed_at)`** — theo dõi job sinh đề
- **`student_writing_profiles (user_id UUID PK, avg_embedding vector(384), sample_count INT, updated_at TIMESTAMPTZ)`** — baseline stylometry per student cho AI essay detector
- **`ai_cache (cache_key TEXT PK, response JSONB, model VARCHAR, tokens INT, created_at)`** — cache LLM response theo hash prompt+model
- Giữ `exam_attempts.state_version BIGINT NOT NULL DEFAULT 0`
- `outbox`, `processed_events` giữ nguyên
- View: `v_exam_stats`, `v_attempt_score_histogram` trong PG (thay ClickHouse)

**PG extensions**: `CREATE EXTENSION IF NOT EXISTS vector;` (pgvector, cần image `pgvector/pgvector:pg16`).

## 8. Mapping docs cũ → docs DATN mới

Docs production đã được archive sang `docs/archive/production-design/` và thay thế
hoàn toàn bởi bộ docs DATN-scope dưới đây:

| Doc production (archive)                                              | Thay thế bằng                                               |
| --------------------------------------------------------------------- | ----------------------------------------------------------- |
| `archive/production-design/design.md`                                 | `docs/design.md` (4 service, DATN)                          |
| `archive/production-design/database.md`                               | `docs/database.md` (PG only + pgvector)                     |
| `archive/production-design/auth-service-design.md`                    | `docs/auth-service-design.md`                               |
| `archive/production-design/exam-service-design.md`                    | `docs/core-service-design.md` (gộp)                         |
| `archive/production-design/question-service-design.md`                | `docs/core-service-design.md` (gộp)                         |
| `archive/production-design/analytics-service-design.md`               | `docs/core-service-design.md` (view trong PG)               |
| `archive/production-design/ai-service-design.md`                      | `docs/ai-service-design.md` (Combo A)                       |
| `archive/production-design/cheating-detection-service-design.md`      | `docs/proctoring-service-design.md` (L1–L3)                 |

Khi scaffold code **chỉ đi theo docs DATN mới** + scope doc này. Archive chỉ mở ra
khi cần future work hoặc defend câu hỏi "scale thế nào".

## 9. Feature AI nâng cao (Combo A)

### 9.1. Upload tài liệu → AI sinh đề thi

**Story**: Giáo viên upload PDF bài giảng chương 3 → chọn "sinh 20 câu MCQ độ khó medium về chủ đề 'thuật toán sắp xếp'" → nhận 20 câu preview → chọn/sửa → save vào question bank.

**Flow**:
```
Teacher ──upload PDF──► Core /documents/upload ──► lưu file + extract text (Tika/PyMuPDF)
                                                    │
                                                    └──► bảng documents (status=ready)

Teacher ──POST /questions/generate──► Core ──publish question.generation.request.v1──►
         {document_id, topic, difficulty, count, type}     │
                                                           ▼
                                                          AI Service
                                                           │ 1. Load document.text_content
                                                           │ 2. Chunk (RecursiveCharacterTextSplitter, 2000 tokens)
                                                           │ 3. Retrieve top-k chunk liên quan topic (embedding similarity)
                                                           │ 4. LLM call với structured output (Pydantic schema)
                                                           │ 5. Validate + dedupe với questions hiện có (pgvector cosine < 0.92)
                                                           ▼
                                                 publish question.generation.result.v1
                                                           │
Core ──consume──► lưu questions (ai_generated=true) + update job status
      ──WS push──► Teacher UI hiển thị preview
```

**API**:
- `POST /api/v1/documents/upload` (Core, multipart) → `{document_id, status}`
- `POST /api/v1/questions/generate` (Core) → `{job_id}` (202 Accepted)
- `GET /api/v1/questions/generate/{job_id}` (Core) → `{status, progress, questions[]}`
- AI internal: `POST /generate-questions-from-document` (sync, chỉ gọi từ Kafka consumer)

**Prompt strategy** (AI service):
```
System: Bạn là chuyên gia ra đề thi. Sinh {count} câu hỏi {type} độ khó {difficulty}
        từ nội dung sau. Trả về JSON theo schema được cung cấp.
User:   Chủ đề: {topic}
        Nội dung: {retrieved_chunks}
Output: Pydantic schema với fields: question, options[4], correct_answer, explanation, difficulty, bloom_level
```

**Model**: Gemini 2.0 Flash (rẻ, structured output tốt) hoặc Claude Haiku 4.5.

**Dedupe logic**: trước khi save, tính embedding câu mới, `SELECT ... WHERE 1 - (embedding <=> $new) < 0.08` → nếu có match, skip hoặc flag trùng.

### 9.2. AI tutor giải thích câu trả lời sai

**Story**: Học sinh nộp bài → trang kết quả hiện điểm. Với mỗi câu sai, có nút "AI giải thích" (hoặc auto-expand cho 3 câu sai đầu). Click → giải thích + gợi ý xem lại phần nào trong tài liệu (nếu câu đó gen từ document).

**Flow**:
```
Student ──submit attempt──► Core ──publish tutor.explanation.request.v1
                                  { attempt_id, list of wrong_answer_ids }
                                    │
                                    ▼
                                   AI Service
                                    │ For each wrong answer:
                                    │   - Check ai_cache (cache_key = sha256(question_id + user_answer + model))
                                    │   - If miss: LLM call
                                    │   - Save to ai_cache
                                    ▼
                             publish tutor.explanation.result.v1
                                    │
Core ──consume──► UPDATE attempt_answers.ai_explanation
     ──WS push──► Student UI hiển thị inline explanation
```

**API**:
- AI internal: `POST /explain-answer` {question, correct_answer, user_answer, context?} → `{explanation, study_tip, related_concept}`

**Prompt**:
```
System: Bạn là gia sư. Học sinh trả lời sai. Giải thích:
        1) Tại sao đáp án của họ sai
        2) Tại sao đáp án đúng là đúng
        3) Gợi ý 1 khái niệm nên ôn lại
        Viết ngắn gọn, thân thiện, tiếng Việt.
User:   Câu hỏi: {q}
        Đáp án đúng: {correct}
        Học sinh chọn: {user_answer}
Output: markdown plain text, max 150 từ.
```

**Cost control**: cache aggressive (cùng wrong answer → không gọi lại). Giáo viên xem lại cũng dùng cache.

### 9.2.1. SHORT_ANSWER — chấm 2 bước (rule trước, AI fallback)

Question type `SHORT_ANSWER` là điền từ / câu trả lời ngắn (ví dụ: "Thủ đô
Việt Nam?" → "Hà Nội"). Chấm bằng **chiến lược 2 bước** để tiết kiệm AI quota:

**Bước 1 — Rule-based (Core, đồng bộ, zero-cost)**:
- Normalize user answer: lowercase, strip diacritic (tuỳ config), strip whitespace + punctuation.
- So khớp với `content.correct_answer` và list `content.accepted_variants[]`.
- Nếu match exact → điểm full.
- Nếu fuzzy (Levenshtein ≤ 2 hoặc ratio ≥ 0.9 bằng `rapidfuzz`) → điểm full + warn cho teacher xem.

**Bước 2 — AI fallback (chỉ khi rule fail)**:
- Core gửi `grading.request.v1` với flag `mode=short_answer_semantic`.
- AI so sánh ngữ nghĩa user answer vs correct_answer (embedding cosine) hoặc LLM "Does X mean Y?" prompt.
- Trả `score` 0..1 (partial credit) + explanation.

**Ước tính**: ~90% câu SHORT_ANSWER pass bước 1 → tiết kiệm 90% AI call.

### 9.2.2. Teacher override AI score (safety net)

Vì AI chất lượng có thể kém (đặc biệt khi fallback Ollama), giáo viên **có quyền
override điểm AI đã chấm**:

- Endpoint: `PATCH /api/v1/attempts/{id}/answers/{position}/override`
  ```json
  { "score": 8.5, "reason": "AI chấm thấp, học sinh trả lời đúng ý nhưng viết ngắn" }
  ```
- UPDATE `attempt_answers.teacher_override_score`, `teacher_override_reason`, `teacher_override_by`, `teacher_override_at`.
- Final score UI hiển thị: `COALESCE(teacher_override_score, score)`.
- Lưu history thay đổi (event `attempt.answer.overridden.v1` tùy chọn — future work).

Teacher dashboard có tab "Essay chờ review" → list essay `ai_explanation_status='FAILED'` hoặc `ai_detection_score >= 0.7` → ưu tiên review.

### 9.3. AI-generated essay detector

**Story**: Học sinh nộp essay → AI chấm điểm + detect khả năng essay do ChatGPT viết. Trang review của giáo viên hiển thị badge màu: 🟢 0-30% (an toàn), 🟡 30-60% (nghi ngờ), 🔴 60-100% (khả năng cao AI). Kèm lý do.

**Flow**: piggyback vào grading flow — khi Core publish `grading.request.v1` với essay, thêm cờ `need_ai_detection=true`.

**Hybrid method** (3 chiều, aggregate qua logistic regression đơn giản):

1. **Perplexity (P)**: AI-generated text thường có perplexity thấp (LLM chọn từ "có khả năng cao nhất").
   - Model: distilgpt2 local (~350MB, chạy CPU OK) hoặc gọi API (GPT-4o logprobs).
   - Tính mean perplexity của essay. P(AI) cao nếu perplexity < threshold.

2. **Burstiness (B)**: text người viết có variance perplexity giữa câu cao (đoạn khó, đoạn dễ xen kẽ); AI text đều đều.
   - Tính std dev perplexity per sentence.

3. **Stylometry (S)**: so sánh essay với baseline 5-10 essay cũ của chính student đó.
   - Mỗi essay cũ → embed với `sentence-transformers/all-MiniLM-L6-v2` → avg = baseline.
   - Essay mới → embed → cosine distance với baseline. Cao bất thường → nghi.
   - Lưu baseline ở bảng `student_writing_profiles`, update sau mỗi essay confirmed.

**Aggregate**: `ai_score = sigmoid(α·normalize(P) + β·normalize(1-B) + γ·normalize(S))`, weights α/β/γ hard-code từ vài essay test hoặc nếu có time thì train logistic regression trên dataset nhỏ (100 mẫu AI + 100 mẫu người).

**API**:
- AI internal: `POST /detect-ai-essay` {text, student_id?} → `{score, method, details: {perplexity, burstiness, stylometry_distance}}`

**Output example**:
```json
{
  "score": 0.78,
  "method": "hybrid",
  "details": {
    "perplexity": 18.4,
    "perplexity_threshold": 30,
    "burstiness": 2.1,
    "burstiness_note": "low (AI-like)",
    "stylometry_distance": 0.41,
    "stylometry_note": "xa baseline của student (có 7 essay so sánh)"
  },
  "flag": "HIGH"
}
```

**Defend**: nêu rõ giới hạn — false positive ~5-10%, không nên làm căn cứ xử phạt duy nhất, là **chỉ báo để giáo viên xem xét**.

### 9.4. Cost estimate — mục tiêu $0 (ADR-008)

Budget DATN = **0 đồng**. Chiến lược: Gemini free tier làm provider chính,
Ollama local làm fallback, pre-warm cache cho demo.

| Feature                    | Primary                | Fallback         | Cost   |
| -------------------------- | ---------------------- | ---------------- | ------ |
| Chấm essay                 | Gemini 2.0 Flash free  | Ollama llama3.1:8b | $0  |
| Sinh đề từ doc             | Gemini 2.0 Flash free  | Ollama (8B kém hơn, rate=1/lần) | $0 |
| Tutor explain              | Gemini 2.0 Flash free  | Ollama           | $0   |
| Essay detector (perplexity)| distilgpt2 local CPU   | —                | $0   |
| Embedding (dedupe + stylo) | MiniLM local CPU       | —                | $0   |
| SHORT_ANSWER grading       | Rule-based fuzzy match | Gemini nếu fuzzy fail | $0 |

**Gemini free tier quota** (2026): 15 RPM · ~1500 req/ngày · 1M tokens/ngày
cho `gemini-2.0-flash`. Đủ cho development + defend.

**Rủi ro**:
- Rate limit 15 RPM → khi teacher bấm "Sinh 50 câu hỏi" cần queue + delay 4s/lần.
- Quota cạn → tự động switch Ollama. Demo không bị đứng.
- Google đổi chính sách free tier → ít xảy ra trong 6–12 tháng DATN; backup là Ollama 100%.

**Pre-warm cache** (bắt buộc trước defend):
Chạy `ops/prewarm-ai-cache.sh` để populate `core.ai_cache` với scenario demo đã
chốt trước. Demo **chạy 100% từ cache, zero API call** → không phụ thuộc Internet
sân khấu.

Chi tiết ADR-008.

### 9.5. Dependency Python mới

Thêm vào `pyproject.toml` của AI service:
```
pymupdf                  # PDF extraction
python-docx              # DOCX extraction
langchain-text-splitters # chunking
sentence-transformers    # embedding local
transformers             # perplexity (distilgpt2)
torch                    # CPU inference
pgvector                 # client
google-generativeai      # Gemini free tier (primary, ADR-008)
httpx                    # Ollama API client (fallback, ADR-008)
rapidfuzz                # SHORT_ANSWER fuzzy matching (Levenshtein nhanh hơn python-Levenshtein)
```

## 10. Future work (để defend)

Khi hội đồng hỏi "nếu scale thì sao", trả lời:

1. **Tách Question ra service riêng + MongoDB** khi question bank > 100k câu.
2. **Đưa Analytics sang ClickHouse** khi attempt > 1M/tháng.
3. **Thay JSON event bằng Avro + Apicurio** khi có > 2 team consume chung topic.
4. **Thêm Cheat L4-L5** (video proctoring, behavioral ML) khi cần compliance thi thực sự.
5. **Đổi Spring Boot consumer sang Flink** cho cheat stream khi latency cần < 100ms.
6. **Multi-region + RPO 5s** — đã có ADR-001 sẵn thiết kế.
7. **Multi-tenancy** — `org_id` đã thiết kế trong docs, chỉ cần migrate.

## 11. Implementation checklist

### 11.1. Tiền-scaffold (chốt trước khi code)

- [x] Viết `ADR-003-datn-scope.md`
- [x] Cập nhật `CLAUDE.md` §3 (NFR relax) + thêm pointer tới doc này
- [ ] Tạo `docs/adr/ADR-004-question-in-pg.md` (drop MongoDB)
- [ ] Tạo `docs/adr/ADR-005-analytics-in-pg-view.md` (drop ClickHouse)
- [ ] Tạo `docs/adr/ADR-006-ai-combo-a.md` (scope AI Combo A: upload→gen, tutor, detector)
- [ ] Tạo `docs/adr/ADR-007-pgvector-for-embeddings.md` (dùng pgvector thay vector DB riêng)
- [ ] Rà lại `database/postgresql/schema.sql`: drop `org_id`, merge schema 4 service, thêm bảng §7
- [ ] Xóa / move `database/mongodb/`, `database/clickhouse/`, `database/elasticsearch/` sang `docs/future-work/`
- [ ] Xóa / disable `infra/docker-compose.dev.yml` các service không dùng (Mongo, CH, ES, Flink, Apicurio)
- [ ] Đổi PG image sang `pgvector/pgvector:pg16` trong docker-compose
- [ ] Tạo `shared-contracts/events/` với 10 JSON schema topic
- [ ] `ops/gen-jwt-keypair.sh` + `ops/llm-api-keys.example.env` (template biến môi trường)

### 11.2. Scaffold core path (sprint 1)

- [ ] Auth service: users, login, JWT, JWKS endpoint
- [ ] Core service: exam CRUD, question CRUD (JSONB), attempt lifecycle + state_version
- [ ] Outbox relayer
- [ ] WebSocket phiên thi cơ bản
- [ ] Web: login, dashboard student, trang làm bài

### 11.3. AI Combo A (sprint 2)

Feature 1 — Upload → sinh đề:
- [ ] Core: `POST /documents/upload`, bảng `documents`, extract text qua Apache Tika hoặc forward file sang AI
- [ ] Core: `POST /questions/generate` + `GET /questions/generate/{job_id}`, bảng `question_generation_jobs`
- [ ] AI: `POST /generate-questions-from-document` + consumer `question.generation.request.v1`
- [ ] AI: chunking + retrieval (RAG) + structured output (Pydantic)
- [ ] Core: embedding mỗi câu hỏi mới, dedupe qua pgvector
- [ ] Web: teacher "AI question generator" wizard (upload → config → preview → save)

Feature 2 — AI tutor:
- [ ] Core: publish `tutor.explanation.request.v1` sau `attempt.submitted`
- [ ] AI: `POST /explain-answer` + consumer
- [ ] Core: consumer `tutor.explanation.result.v1` → update `attempt_answers.ai_explanation` + WS push
- [ ] Bảng `ai_cache` + cache key = sha256(question_id, user_answer, model)
- [ ] Web: trang kết quả student hiển thị expandable explanation

Feature 3 — AI essay detector:
- [ ] AI: `POST /detect-ai-essay` với 3 pipeline (perplexity, burstiness, stylometry)
- [ ] AI: load distilgpt2 + sentence-transformers MiniLM lúc startup
- [ ] Core: khi có essay trong grading.request, set `need_ai_detection=true`
- [ ] Core: consumer `grading.result.v1` đọc thêm field `ai_detection` → save vào `attempt_answers`
- [ ] Core: bảng `student_writing_profiles`, update sau mỗi essay confirmed
- [ ] Web: teacher review page hiển thị badge 🟢🟡🔴 với tooltip breakdown

### 11.4. Proctoring (sprint 3)

- [ ] Proctoring service: consumer `cheat.event.raw.v1`, detector L1/L2/L3
- [ ] Publish `cheat.alert.v1`
- [ ] Core: consumer `cheat.alert.v1` → WS push cho coi thi
- [ ] Web: teacher live monitor view, duyệt cheat alert

### 11.5. Polish & demo

- [ ] Analytics view trong PG + dashboard teacher
- [ ] Seed data (5 giáo viên, 30 học sinh, 10 đề mẫu, 5 document PDF giáo trình)
- [ ] Demo script (kịch bản bấm bàn phím trên sân khấu)
- [ ] Video backup nếu Internet sân khấu flaky
