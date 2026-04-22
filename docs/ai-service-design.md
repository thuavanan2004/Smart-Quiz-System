# AI Service — Design (DATN)

> **Port**: 8103 · **Ngôn ngữ**: Python 3.12 + FastAPI · **Stateless** (không DB riêng, dùng `core.ai_cache` + `core.student_writing_profiles` qua role `ai_reader`).

Cung cấp 5 năng lực AI (Combo A, §9 scope-datn):
1. **Chấm essay** (gọi LLM với rubric).
2. **Sinh câu hỏi từ chủ đề** (manual).
3. **Sinh câu hỏi từ tài liệu** (RAG pipeline).
4. **Giải thích câu trả lời sai** (AI tutor).
5. **Detect essay do AI viết** (hybrid: perplexity + burstiness + stylometry).

Ngoài ra cung cấp endpoint **embedding** cho Core dùng khi CRUD câu hỏi.

## 1. Trách nhiệm

**Có**:
- 2 consumer Kafka: `grading.request.v1`, `question.generation.request.v1`, `tutor.explanation.request.v1`.
- 3 producer: `grading.result.v1`, `question.generation.result.v1`, `tutor.explanation.result.v1`.
- 5 REST endpoint (được Core gọi nội bộ hoặc debug).
- Cache aggressive qua `core.ai_cache` (key = sha256(prompt_type + inputs + model)).
- Load 2 local model lúc startup: `sentence-transformers/all-MiniLM-L6-v2` (384d embedding) + `distilgpt2` (perplexity, ~350MB).
- Gọi LLM API (Gemini 2.0 Flash hoặc Claude Haiku 4.5).

**Không có**:
- Quản lý user, attempt, exam — đó là Core.
- Ghi trực tiếp vào `core.attempt_answers`, `core.questions` — chỉ gửi event.
- Train / fine-tune model.

## 2. Architecture

```
┌──────────────────────────────────────────────────────────┐
│ FastAPI app (Uvicorn, port 8103)                          │
│                                                            │
│  Routers:                                                  │
│   /grade-essay · /generate-questions                       │
│   /generate-questions-from-document                        │
│   /explain-answer · /detect-ai-essay                       │
│   /embed · /health                                         │
│                                                            │
│  Kafka workers (aiokafka):                                 │
│   grading_consumer · generation_consumer · tutor_consumer  │
│                                                            │
│  Services:                                                 │
│   LlmClient (Gemini/Claude)                                │
│   PromptBuilder · StructuredOutputValidator                │
│   ChunkingService · RetrievalService                       │
│   EmbeddingService  (local MiniLM, batched)                │
│   PerplexityService (local distilgpt2)                     │
│   StylometryService (MiniLM + cosine vs baseline)          │
│   CacheService     (PG ai_cache)                           │
└──────────────────────────────────────────────────────────┘
```

**Dependencies** (`pyproject.toml`):
```toml
[project]
dependencies = [
  "fastapi>=0.115",
  "uvicorn[standard]>=0.30",
  "pydantic>=2.8",
  "aiokafka>=0.11",
  "asyncpg>=0.29",
  "pgvector>=0.3",
  "google-generativeai>=0.8",   # hoặc anthropic>=0.39
  "sentence-transformers>=3.0",
  "transformers>=4.44",
  "torch>=2.4",                  # CPU build, ~200MB
  "pymupdf>=1.24",
  "python-docx>=1.1",
  "langchain-text-splitters>=0.2",
  "prometheus-fastapi-instrumentator>=7",
  "opentelemetry-api>=1.27",     # optional
]
```

## 3. REST API

Base URL: `http://localhost:8103`. Các endpoint được Core gọi nội bộ — auth
bằng **shared secret header** `X-Internal-Auth: <token>` (đơn giản cho DATN;
production nên JWT service-to-service).

### 3.1. `POST /grade-essay`

**Request**:
```json
{
  "question": "Giải thích thuật toán quicksort và độ phức tạp.",
  "rubric": "3 điểm: mô tả pivot; 3 điểm: chia để trị; 2 điểm: best/avg/worst case; 2 điểm: ví dụ",
  "student_answer": "Quicksort chọn pivot rồi...",
  "max_score": 10,
  "need_ai_detection": true,
  "student_id": "uuid"
}
```

**Response** `200 OK`:
```json
{
  "score": 7.5,
  "feedback": "Bài giải thích đúng ý tưởng chia để trị... thiếu phân tích worst case.",
  "rubric_breakdown": [
    { "criterion": "pivot", "points": 3, "given": 3 },
    { "criterion": "divide-and-conquer", "points": 3, "given": 3 },
    { "criterion": "complexity", "points": 2, "given": 0.5 },
    { "criterion": "example", "points": 2, "given": 1 }
  ],
  "ai_detection": {
    "score": 0.23,
    "method": "hybrid",
    "flag": "LOW",
    "details": { "perplexity": 42.1, "burstiness": 6.3, "stylometry_distance": 0.18 }
  }
}
```

**Model**: Gemini 2.0 Flash structured output theo Pydantic schema.
Cache key = `sha256("grade|" + question_hash + answer_hash + model)`.

### 3.2. `POST /generate-questions`

Sinh từ chủ đề thuần (không dùng document).

**Request**:
```json
{
  "topic": "Cấu trúc dữ liệu cây nhị phân",
  "difficulty": "MEDIUM",
  "count": 5,
  "type": "MCQ_SINGLE",
  "language": "vi"
}
```

**Response** `200 OK`:
```json
{
  "questions": [
    {
      "type": "MCQ_SINGLE",
      "difficulty": "MEDIUM",
      "content": {
        "stem": "Cây nhị phân tìm kiếm (BST) có tính chất nào?",
        "options": ["Trái < gốc < phải", "Trái > gốc", "Mọi node có 2 con", "Cân bằng luôn"],
        "correct_answer": [0],
        "explanation": "BST định nghĩa trái luôn nhỏ hơn gốc, phải luôn lớn hơn."
      },
      "metadata": { "topic": "bst", "bloom_level": "understand" }
    }
  ]
}
```

### 3.3. `POST /generate-questions-from-document` (RAG)

Được gọi **nội bộ** từ consumer `question.generation.request.v1`. Cũng có endpoint REST để debug.

**Request**:
```json
{
  "document_text": "<full extracted text>",
  "topic": "thuật toán sắp xếp",
  "difficulty": "MEDIUM",
  "count": 10,
  "type": "MCQ_SINGLE"
}
```

**Flow**:
1. `ChunkingService`: `RecursiveCharacterTextSplitter` size 2000, overlap 200.
2. `EmbeddingService`: embed query `"câu hỏi về {topic}"` + embed mỗi chunk.
3. Top-k retrieval (k=6) theo cosine similarity.
4. `PromptBuilder`: system prompt tiếng Việt + passage context + instruction structured output.
5. LLM call với Pydantic schema validation (`ResponseFormat(type=json_schema)`).
6. Nếu validate fail → retry 1 lần với error message feedback.
7. Return `QuestionGenResult` (same schema 3.2).

**System prompt** (template):
```
Bạn là chuyên gia ra đề thi. Dựa CHỈ trên nội dung được cung cấp, sinh {count}
câu hỏi {type} độ khó {difficulty} về chủ đề "{topic}".

Yêu cầu:
- Câu hỏi phải trả lời được từ nội dung.
- KHÔNG tạo thông tin ngoài nội dung.
- Đáp án đúng phải được minh chứng trong passage.
- Mỗi câu kèm explanation nêu rõ passage nào làm căn cứ.

Trả về đúng schema JSON.
```

### 3.4. `POST /explain-answer` (AI tutor)

**Request**:
```json
{
  "question": { "stem": "...", "options": [...], "correct_answer": [1] },
  "correct_answer_text": "Quick sort",
  "user_answer_text": "Bubble sort",
  "context": "optional — snippet bài giảng"
}
```

**Response**:
```json
{
  "explanation": "Bạn chọn Bubble sort. Thực ra Bubble sort có độ phức tạp O(n²)...",
  "study_tip": "Hãy xem lại phần 'Phân tích độ phức tạp' của thuật toán sắp xếp.",
  "related_concept": "sorting-complexity"
}
```

**Prompt** (system):
```
Bạn là gia sư thân thiện. Học sinh trả lời sai 1 câu trắc nghiệm. Hãy:
1. Giải thích tại sao đáp án học sinh chọn SAI.
2. Giải thích tại sao đáp án ĐÚNG là đúng.
3. Đưa ra 1 gợi ý ôn tập (1 câu, max 20 từ).

Viết tiếng Việt, thân thiện, tổng max 150 từ.
```

**Model**: Claude Haiku 4.5 (hoặc Gemini Flash). Cache tích cực.

### 3.5. `POST /detect-ai-essay`

**Request**:
```json
{
  "text": "<essay content>",
  "student_id": "uuid"  // optional, nếu có → dùng stylometry
}
```

**Response**:
```json
{
  "score": 0.78,
  "method": "hybrid",
  "flag": "HIGH",
  "details": {
    "perplexity": 18.4,
    "perplexity_threshold": 30,
    "perplexity_note": "low (AI-like)",
    "burstiness": 2.1,
    "burstiness_note": "low (AI-like)",
    "stylometry_distance": 0.41,
    "stylometry_baseline_samples": 7,
    "stylometry_note": "xa baseline của student"
  }
}
```

**Flag threshold**:
- `LOW` < 0.4
- `MEDIUM` 0.4 – 0.7
- `HIGH` ≥ 0.7

### 3.5.1. `POST /grade-short-answer-semantic` (SHORT_ANSWER fallback)

Core gọi khi rule-based fuzzy match fail (xem `core-service-design.md` §8.5).

**Request**:
```json
{
  "question": "Viết tắt của International Organization for Standardization?",
  "correct_answer": "ISO",
  "accepted_variants": ["iso","I.S.O."],
  "user_answer": "Tổ chức tiêu chuẩn quốc tế"
}
```

**Flow**:
1. Embed `correct_answer` + `user_answer` bằng MiniLM.
2. Cosine similarity ≥ 0.85 → full credit (1.0).
3. 0.65–0.85 → partial credit tỉ lệ tuyến tính.
4. < 0.65 → gọi LLM "Does '{user_answer}' mean the same as '{correct_answer}'? Trả JSON {equivalent: bool, confidence: 0..1}" → quyết định cuối.

**Response**:
```json
{
  "score": 0.6,
  "method": "embedding+llm",
  "similarity": 0.71,
  "llm_verdict": { "equivalent": true, "confidence": 0.8 }
}
```

### 3.6. `POST /embed`

Trả embedding cho Core khi CRUD question.

**Request**:
```json
{ "texts": ["Câu hỏi 1", "Câu hỏi 2"] }
```

**Response**:
```json
{ "embeddings": [[0.012, -0.08, ...], [...]], "dim": 384, "model": "all-MiniLM-L6-v2" }
```

### 3.7. `GET /health`

```json
{ "status": "ok", "models": { "minilm": "ready", "distilgpt2": "ready" }, "llm": "reachable" }
```

## 4. Kafka

### 4.1. Consumer

| Topic                              | Handler                            | Idempotent key        |
| ---------------------------------- | ---------------------------------- | --------------------- |
| `grading.request.v1`               | `grading_handler.grade()`          | `event_id` (xem 4.4)  |
| `question.generation.request.v1`   | `generation_handler.generate()`    | `job_id` + `event_id` |
| `tutor.explanation.request.v1`     | `tutor_handler.explain_batch()`    | `event_id`            |

### 4.2. Producer

Output topic publish qua `aiokafka.AIOKafkaProducer` với `acks=all`, `enable_idempotence=true`.

- `grading.result.v1` — per answer. Payload: `{event_id, request_event_id, attempt_id, position, score, feedback, ai_detection}`.
- `question.generation.result.v1` — per job. Payload: `{event_id, job_id, questions: [...], errors: []}`.
- `tutor.explanation.result.v1` — per wrong answer. Payload: `{event_id, attempt_id, position, explanation, study_tip}`.

### 4.3. Tại sao AI không dùng outbox?

AI service stateless — không có DB transaction commit cùng lúc với publish. Payload
chỉ phụ thuộc kết quả LLM (đã có trong memory khi publish). Trade-off:

- **Risk**: AI crash sau khi chấm xong nhưng trước khi publish → Core retry khi timeout → xử lý lại.
- **Dedupe**: Core consumer `grading.result.v1` dedupe `event_id` → không apply 2 lần.
- **Timeout**: Core có deadline 5 phút cho essay grading. Nếu quá hạn → retry request (đẩy lại message với cùng `request_event_id` và `retry_count++`).

### 4.4. Idempotent AI processing

Ghi `core.ai_cache` **trước khi publish** → nếu consumer nhận 2 lần cùng event_id,
cache hit → skip LLM call (tiết kiệm token), vẫn publish result (Core dedupe).

```python
async def grade(event):
    cache_key = hash_key("grade", event.question_id, event.answer_text, MODEL)
    cached = await cache.get(cache_key)
    if cached:
        result = cached
    else:
        result = await llm.grade(...)
        await cache.put(cache_key, result, MODEL, tokens)
    await producer.send("grading.result.v1", {
        "event_id": uuid4(),
        "request_event_id": event.event_id,
        **result
    })
```

## 5. Mô hình chi tiết feature

### 5.1. AI tutor explanation batch

Core publish 1 event cho batch wrong answers của 1 attempt:
```json
{
  "event_id": "...",
  "attempt_id": "...",
  "items": [
    { "position": 3, "question": {...}, "user_answer": "...", "correct_answer": "..." },
    { "position": 7, ... }
  ]
}
```

AI xử lý: gọi LLM parallel (asyncio.gather, max concurrency 5) → 1 explanation per item → publish **1 event per item** về `tutor.explanation.result.v1` (không batch result, để Core update WS streaming).

### 5.2. Essay detector — công thức

```python
def detect(text: str, student_id: Optional[UUID]) -> DetectionResult:
    perp   = perplexity_service.score(text)       # mean perplexity
    burst  = perplexity_service.burstiness(text)  # std dev per-sentence
    stylo  = None
    if student_id:
        baseline = profile_repo.find(student_id)
        if baseline and baseline.sample_count >= 3:
            text_emb = embedder.embed_one(text)
            stylo = 1 - cosine(text_emb, baseline.avg_embedding)

    # Normalize
    p_norm = clamp((30 - perp) / 30, 0, 1)        # perp thấp → AI
    b_norm = clamp((10 - burst) / 10, 0, 1)       # burst thấp → AI
    s_norm = clamp(stylo / 0.5, 0, 1) if stylo is not None else 0

    # Weighted sum (weight hard-code, có thể học từ dataset sau)
    if stylo is not None:
        score = sigmoid(1.8*p_norm + 1.2*b_norm + 1.5*s_norm - 2.0)
    else:
        score = sigmoid(2.2*p_norm + 1.5*b_norm - 2.0)

    return DetectionResult(score=score, method="hybrid", details={...})
```

**Sau grading**: nếu teacher confirm essay là do student viết (review UI), update baseline:

```python
async def update_profile(student_id, text):
    emb = embedder.embed_one(text)
    # running average
    UPDATE student_writing_profiles
    SET avg_embedding = (avg_embedding * sample_count + emb) / (sample_count + 1),
        sample_count = sample_count + 1,
        updated_at = now()
    WHERE user_id = student_id
```

### 5.3. RAG cho generation — chi tiết

- **Chunk size**: 2000 chars, overlap 200.
- **Embedding**: MiniLM 384d, batch 32 chunks.
- **Retrieval**: top-6 theo cosine. Nếu tổng passage > 8000 chars, cắt bớt.
- **LLM**: Gemini Flash, `response_mime_type=application/json`, `response_schema=QuestionList`.
- **Retry**: 1 lần nếu validate fail hoặc model trả empty.
- **Dedupe nội bộ** (trong cùng batch output): cosine pairwise > 0.95 → giữ câu đầu.

## 6. Cấu hình (ADR-008 free-tier strategy)

`config.yaml`:
```yaml
server:
  port: 8103

db:
  dsn: postgresql://ai_reader:${DB_PASSWORD}@localhost:5432/smartquiz

kafka:
  bootstrap: localhost:9092
  group_id: ai
  consumer:
    max_poll_records: 10
    session_timeout_ms: 45000

llm:
  # Tier 1 — Gemini free tier (primary)
  primary:
    name: gemini
    model: gemini-2.0-flash
    api_key_env: GEMINI_API_KEY
    rate_limit_rpm: 12           # < 15 RPM free tier, có buffer
    daily_token_limit: 900000    # < 1M free, có buffer
    timeout_sec: 30
  # Tier 2 — Ollama local (fallback khi quota/rate hit)
  fallback:
    name: ollama
    base_url: http://ollama:11434
    model: llama3.1:8b           # hoặc qwen2.5:7b-instruct; pull trước bằng docker compose --profile ai-fallback
    timeout_sec: 60
  routing:
    preemptive_fallback_threshold: 0.1   # nếu quota remaining < 10% → tier 2 với request priority thấp
    priority_tier1: [grade_essay, generate_from_document]
    priority_tier2: [tutor_explain, short_answer_semantic]

models:
  embedding:
    name: sentence-transformers/all-MiniLM-L6-v2
    device: cpu
    batch_size: 32
  perplexity:
    name: distilgpt2
    device: cpu

detection:
  thresholds: { low: 0.4, high: 0.7 }

cache:
  enabled: true
  # No TTL: cache forever cho deterministic prompt.
  # Invalidate bằng cách bump prompt_version trong cache_key.
  prompt_version: 1
  evict_unused_days: 90         # periodic cron xoá entry last_hit_at cũ

internal_auth:
  header: X-Internal-Auth
  token_env: INTERNAL_AUTH_TOKEN

degraded_mode:
  # Khi tier 1 + tier 2 đều fail
  grade_essay: human_review     # trả ai_explanation_status=FAILED, teacher chấm tay
  tutor_explain: skip           # không bắt buộc, UI hiển thị nút Retry
  generate_from_document: fail  # job status=FAILED, teacher retry
```

### 6.1. LlmClient router pseudocode

```python
class LlmRouter:
    async def call(self, task: TaskType, prompt: Prompt) -> Response:
        cache_key = self.cache.key(task, prompt)
        if cached := await self.cache.get(cache_key):
            return cached

        provider = self._pick_provider(task)   # tier 1 or tier 2
        try:
            resp = await provider.call(prompt)
        except (RateLimitError, QuotaExceededError, TimeoutError) as e:
            metrics.fallback_triggered.labels(reason=e.reason).inc()
            if provider is self.primary:
                resp = await self.fallback.call(prompt)   # tier 2
            else:
                raise DegradedModeError(task)             # tier 3

        await self.cache.put(cache_key, resp)
        return resp

    def _pick_provider(self, task: TaskType) -> LlmProvider:
        if task in config.routing.priority_tier2:
            return self.fallback      # low-priority task → tier 2 trước, giữ tier 1 cho grading
        if self.primary.quota_ratio() < config.routing.preemptive_fallback_threshold:
            return self.fallback      # quota gần hết, bảo vệ tier 1
        return self.primary
```

### 6.2. Rate limit guard

Token bucket với rate = 12 RPM. Nếu bucket rỗng → sleep + retry max 3 lần, sau đó fallback.

### 6.3. Ollama setup

```bash
# Pull model 1 lần (~4GB)
docker compose -f infra/docker-compose.dev.yml --profile ai-fallback run --rm ollama ollama pull llama3.1:8b
```

Container ollama không auto-start (profile `ai-fallback`). AI service healthcheck
detect ollama unreachable → chấp nhận degraded tier 3 cho phần đó.

## 7. Test strategy

- **Unit**: prompt builder output stable, cache hash stable, detector thresholds.
- **Integration**: gọi LLM stub (VCR cassette) → test full flow grade/generate/explain/detect.
- **Contract**: JSON schema các event request/result validate qua `jsonschema`.
- **Smoke**: `pytest -k smoke` chạy 1 lần thật với API key (CI secret) để catch regression prompt.

## 8. Observability

- Log JSON stdout, field `request_id`, `event_id`, `model`, `tokens`.
- Metric (prometheus-fastapi-instrumentator + custom):
  - `ai_llm_calls_total{endpoint, model, result}`
  - `ai_llm_latency_seconds_bucket{endpoint, model}`
  - `ai_cache_hit_ratio`
  - `ai_tokens_total{model, direction=prompt|completion}`
  - `ai_detection_score_histogram`
  - `ai_kafka_consumer_lag_seconds{topic}`

## 9. Cost — mục tiêu $0 (ADR-008)

Budget DATN = 0 đồng. Dùng Gemini free tier (1500 req/day, 1M token/day) làm
primary, Ollama local fallback khi quota/rate hit, pre-warm `core.ai_cache`
cho scenario demo → **zero paid API call**.

Với cache và 2 tier fallback:
- Development (~300 call/ngày): 100% Gemini free tier.
- Defend demo: 100% cache hit, zero API call, không phụ thuộc Internet.

## 10. Ranh giới

| Không bao giờ                                    | Thay vào đó                              |
| ------------------------------------------------ | ---------------------------------------- |
| Ghi `core.attempt_answers` trực tiếp             | Publish `grading.result.v1` cho Core update |
| Ghi `core.questions`                              | Publish `question.generation.result.v1`  |
| Cache trong memory process (mất khi restart)      | Dùng `core.ai_cache` trong PG            |
| Train model runtime                               | Dùng model đã có sẵn                     |
| Gọi LLM không qua `LlmClient`                    | Mọi LLM call đi qua adapter (cho retry + cache + metric) |
