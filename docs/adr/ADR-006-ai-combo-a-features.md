# ADR-006: Scope AI Combo A — 3 tính năng điểm sáng

- **Status**: Accepted
- **Date**: 2026-04-22
- **Deciders**: thuavanan2004
- **Related**: `docs/scope-datn.md` §9, `docs/ai-service-design.md`, ADR-003

## Context

DATN cần ít nhất 1 điểm sáng AI để defend. Sau khi thảo luận (conversation
2026-04-22), chốt chọn **Combo A — AI-heavy**:

1. **Upload tài liệu → AI sinh đề thi** (RAG pipeline).
2. **AI tutor giải thích câu trả lời sai**.
3. **AI-generated essay detector** (chống ChatGPT cheat).

Các combo B (cân bằng wow + academic) và C (education-focused) đã bị loại.

Ràng buộc:
- Budget LLM API rất thấp (< $5/tháng cho demo).
- Không train / fine-tune model.
- Chạy được trên laptop demo (không GPU).
- Code Python được, không cần native.

## Decision

Chúng ta sẽ implement 3 feature AI trong AI service (FastAPI) như sau:

### 1. Upload → Generate (feature §9.1)

- Endpoint Core: `POST /api/v1/documents/upload` + `POST /api/v1/questions/generate`.
- AI: RAG pipeline = chunk (RecursiveCharacterTextSplitter 2000/200) + embed (MiniLM 384d local) + top-k retrieval + LLM (Gemini Flash) structured output (Pydantic schema).
- Dedupe: pgvector cosine < 0.92 với questions hiện có.
- Async qua Kafka `question.generation.request/result.v1`.

### 2. AI tutor explain (feature §9.2)

- Sau submit, Core publish `tutor.explanation.request.v1` cho list wrong answers.
- AI gọi LLM (Claude Haiku 4.5 hoặc Gemini Flash) per câu, trả explanation + study_tip + related_concept (max 150 từ, tiếng Việt).
- Cache `core.ai_cache` key = `sha256("explain" + question_id + user_answer + model)`.
- Core consume result → update `attempt_answers.ai_explanation` → WS push về student.

### 3. AI essay detector (feature §9.3)

- Piggyback vào grading: Core flag `need_ai_detection=true` trong `grading.request.v1` cho essay.
- AI chạy hybrid 3 chiều:
  - **Perplexity** (local `distilgpt2`) — mean per-sentence.
  - **Burstiness** (std dev perplexity per-sentence).
  - **Stylometry** — embedding MiniLM essay vs baseline `core.student_writing_profiles.avg_embedding`.
- Aggregate bằng weighted sigmoid (weights hard-code, có thể fine-tune từ dataset nhỏ nếu có time).
- Output: score 0..1, flag LOW/MEDIUM/HIGH với breakdown details.
- Teacher review UI hiển thị badge 🟢🟡🔴.

## Alternatives considered

| Lựa chọn                                      | Ưu                                           | Nhược                                                        | Lý do loại                                       |
| --------------------------------------------- | -------------------------------------------- | ------------------------------------------------------------ | ------------------------------------------------ |
| Combo B (Gen + Adaptive CAT + Live Kahoot)    | Academic depth (IRT)                         | CAT cần data calibration sẵn; live mode tốn effort WS multicast  | DATN chưa có dataset để calibrate IRT            |
| Combo C (Tutor + Spaced repetition + Gamification) | Education-focus rõ                           | Ít AI showcase; spaced repetition không wow demo             | Hội đồng AI-oriented sẽ kém ấn tượng             |
| **Combo A (3 AI feature, đã chọn)**           | **Wow factor cao; AI-heavy; thời sự (chống ChatGPT)** | **Cost LLM API + phải cẩn thận prompt injection**   | **Match mục tiêu defend + budget**               |
| Full 6 feature (cả 3 combo)                   | Nhiều điểm sáng                              | Không đủ thời gian, implement hời hợt                        | Trade-off depth vs breadth                       |

## Consequences

### Positive

- 3 feature rõ ràng để defend: RAG, LLM orchestration, hybrid detection.
- Dùng Python đúng chỗ (model inference + ML), Java đúng chỗ (business logic).
- Local model (MiniLM + distilgpt2) giảm cost + tăng tốc + defend được "biết xử lý offline".
- Mỗi feature kết nối với UX cụ thể (teacher generator wizard, student result page, teacher badge).

### Negative / trade-offs

- Cost LLM API dù thấp (~$0.30/tháng) vẫn cần theo dõi; cache aggressive.
- Essay detector có false positive ~5–10% — phải nêu trong doc + UI.
- AI service phụ thuộc Internet khi gọi LLM → demo cần backup (model offline fallback hoặc cache pre-warmed).
- Prompt injection risk nếu text document có nội dung malicious → sanitize ở Core trước khi publish event.

### Neutral

- Thêm 2 Kafka topic (`question.generation.*`, `tutor.explanation.*`) + patch `grading.*` có field `need_ai_detection`.
- Thêm 3 bảng PG: `documents`, `question_generation_jobs`, `student_writing_profiles`.

## Implementation notes

1. Ghi scope chi tiết trong `docs/ai-service-design.md` §3–§5.
2. Prompt template tiếng Việt, test bằng VCR cassette hoặc LangSmith trace.
3. Cost monitor qua metric `ai_tokens_total{model, direction}` — alert nếu > $10/ngày.
4. **Demo backup**: có script pre-warm `core.ai_cache` cho scenario demo (sinh 10 đề mẫu, grade 5 essay mẫu trước demo) → đảm bảo không phụ thuộc Internet.
5. False-positive guardrail: essay detector flag HIGH chỉ là warning cho giáo viên, không tự suspend attempt.

## References

- `docs/scope-datn.md` §9
- `docs/ai-service-design.md`
- Gemini structured output — https://ai.google.dev/gemini-api/docs/structured-output
- Anthropic prompt caching — https://docs.anthropic.com/claude/docs/prompt-caching
