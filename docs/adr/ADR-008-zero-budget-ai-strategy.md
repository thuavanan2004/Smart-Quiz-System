# ADR-008: Chiến lược AI budget $0 — Gemini free tier + Ollama fallback

- **Status**: Accepted
- **Date**: 2026-04-22
- **Deciders**: thuavanan2004
- **Related**: `docs/scope-datn.md` §9, `docs/ai-service-design.md`, ADR-006 (Combo A)

## Context

ADR-006 chọn Combo A (3 feature AI: upload→gen, tutor explain, essay detector) với
cost estimate ~$0.10–0.30/tháng trên Gemini Flash paid.

Ràng buộc mới từ user: **ngân sách AI = 0 đồng**, không được phép phát sinh chi phí
API bất kỳ hoàn cảnh nào (kể cả $5/tháng).

Điều này loại bỏ gần như toàn bộ LLM API paid. Các lựa chọn còn lại:

1. **Google Gemini free tier** — Gemini 2.0 Flash có free tier thật sự sử dụng
   được (không "trial"). Giới hạn: 15 requests/minute, ~1500 requests/day,
   1M tokens/day. Structured output (JSON schema) đầy đủ. Chất lượng ngang
   paid tier.
2. **Groq free tier** — Llama 3.3 70B, tốc độ cực nhanh, giới hạn ~30 RPM.
3. **OpenRouter free endpoints** — Deepseek V3, Llama 3.3 qua free endpoint
   (độ ổn định thấp, rate thay đổi).
4. **Ollama local** — self-host Llama 3.1 8B / Qwen 2.5 7B trên CPU. Zero
   network dependency. Yêu cầu ~6GB RAM. Latency 5–30s/request trên CPU.
5. **OpenAI GPT-4o-mini free** — không có free tier production (chỉ $5 trial
   hết hạn sau 3 tháng, không stable cho DATN 6–12 tháng).
6. **Anthropic Claude** — không có free tier.

Ngoài LLM, embedding + perplexity đã local (MiniLM, distilgpt2) — $0.

## Decision

Chúng ta sẽ dùng **Gemini Flash free tier làm provider chính, Ollama local
làm fallback** cho AI service. Budget mục tiêu: **$0/tháng**.

### Provider tier

```
┌─────────────────────────────────────────────────────┐
│  Request đến AI service                              │
└──────────────────┬──────────────────────────────────┘
                   │
          Cache hit?
                   │
         No ───────┴─────── Yes ──► trả response từ core.ai_cache
                   │
┌──────────────────▼──────────────────────────────────┐
│  Tier 1: Gemini 2.0 Flash (free tier API)           │
│          15 RPM · 1500 req/day · 1M tokens/day       │
└──────────────────┬──────────────────────────────────┘
                   │
       Quota hit? Rate 429?
                   │
         No ───────┴─────── Yes
                                │
┌───────────────────────────────▼─────────────────────┐
│  Tier 2: Ollama local (Llama 3.1 8B q4)             │
│          CPU only · ~10s/request · zero rate limit   │
└──────────────────┬──────────────────────────────────┘
                   │
       Ollama unavailable?
                   │
         No ───────┴─────── Yes
                                │
┌───────────────────────────────▼─────────────────────┐
│  Tier 3: degraded mode                               │
│  - Essay grading: trả "PENDING_HUMAN_REVIEW", teacher chấm tay │
│  - Tutor explain: trả cached generic answer hoặc skip        │
│  - Generate Q: báo lỗi "AI service busy, thử lại sau"        │
└─────────────────────────────────────────────────────┘
```

### Quota guardrail

- Metric `ai_gemini_quota_used_ratio` — cập nhật sau mỗi call dựa trên header `X-RateLimit-Remaining`.
- Nếu `remaining < 10%` → preemptively switch tier 2 (tránh burn vào tier 1 cho request ít quan trọng).
- Priority: essay grading > generate-from-document > tutor explain (skip được). Lên grading đi tier 1 trước, tutor đi tier 2 trước.

### Pre-warm cache cho demo

Trước buổi defend, chạy script `ops/prewarm-ai-cache.sh`:
- Grade 5 essay mẫu trong seed data → populate `core.ai_cache`.
- Explain 10 wrong-answer patterns thường gặp → cache.
- Generate 3 job mẫu từ document mẫu → cache.

Demo chạy **100% từ cache, zero API call** → bất kể có Internet hay không cũng chạy mượt.

### Cache key & TTL

- `core.ai_cache.cache_key = sha256(prompt_type + stable_inputs + provider + model)`.
- Không TTL (cache forever cho deterministic prompt). Chỉ invalidate khi đổi
  prompt template (bump `prompt_version` trong key).
- Periodic eviction: xoá entry `last_hit_at < now - 90 ngày` để tránh phình.

## Alternatives considered

| Lựa chọn                                       | Ưu                                          | Nhược                                                      | Lý do loại                          |
| ---------------------------------------------- | ------------------------------------------- | ---------------------------------------------------------- | ----------------------------------- |
| A. Gemini Flash paid ($0.30/tháng)             | Ổn định, không lo quota                     | Không phù hợp budget $0                                    | Vi phạm budget                      |
| B. Ollama only (local Llama/Qwen)              | Zero network, zero quota                    | Chất lượng kém hơn Gemini Flash; latency 10–30s/CPU; demo laptop ≥8GB RAM | Quality gap với essay grading + UX defend chậm |
| C. OpenRouter free endpoints only              | Nhiều model miễn phí                        | Rate thay đổi thất thường; không stable cho DATN 6 tháng   | Risk provider thay đổi giữa DATN    |
| D. Groq free tier                              | Nhanh cực (Llama 70B)                       | Rate limit chặt hơn Gemini; không native structured output | Gemini structured output mạnh hơn   |
| **E. Gemini free tier + Ollama fallback (đã chọn)** | **Quality Gemini + fallback khi quota hết + demo an toàn qua pre-warm cache** | **Phải code 2 provider + tier switching logic**            | **Match $0 budget + demo stable**   |

## Consequences

### Positive

- **$0/tháng thật sự**, không có surprise bill.
- Gemini free tier 1500 req/day đủ cho DATN development + defend (demo chỉ cần ~100 call thực tế, còn lại từ cache).
- Ollama fallback tăng resilience: mất Internet vẫn chạy (trừ generate-from-document vì cần context lớn, Ollama 8B xử lý kém).
- Pre-warm cache strategy cho defend siêu an toàn: demo "offline-ready".

### Negative / trade-offs

- Code AI service phức tạp hơn: 2 provider + tier router + quota tracking. Ước tính +3–4 ngày effort.
- Ollama chạy CPU chậm → UX khi hit fallback có thể 10–30s/request. Phải UI loading state rõ.
- Gemini free tier có thể thay đổi chính sách bất ngờ (Google deprecate, giảm quota). Rủi ro thấp trong 6–12 tháng DATN nhưng cần monitor.
- Structured output quality: Llama 3.1 8B JSON ít ổn định hơn Gemini — retry logic phức tạp hơn khi fallback.
- Rate limit 15 RPM = nếu giáo viên bấm "Sinh 50 câu hỏi" trong 1 lần, Gemini sẽ throttle → phải queue + delay.

### Neutral

- Teacher override AI score (ADR-003 update) trở thành "safety net" chính thức khi AI chất lượng kém.
- Hội đồng DATN có thể hỏi "nếu scale thì sao" — dẫn ADR này + ADR-006: upgrade tier paid 1 dòng code.

## Implementation notes

### 1. AI service config

```yaml
llm:
  providers:
    primary:
      name: gemini
      model: gemini-2.0-flash
      api_key_env: GEMINI_API_KEY
      rate_limit_rpm: 12          # < 15 để có buffer
      timeout_sec: 30
    fallback:
      name: ollama
      base_url: http://ollama:11434
      model: llama3.1:8b          # hoặc qwen2.5:7b-instruct
      timeout_sec: 60
  routing:
    preemptive_fallback_threshold: 0.1   # quota remaining <10% → tier 2
    priority_tier1: [grade_essay, generate_from_document]
    priority_tier2: [tutor_explain]      # có thể skip
```

### 2. Docker compose

Thêm service `ollama` vào `infra/docker-compose.dev.yml` với **profile `ai-fallback`** (không auto-start, chỉ bật khi cần):

```yaml
ollama:
  image: ollama/ollama:latest
  profiles: ["ai-fallback"]
  volumes:
    - ollama-models:/root/.ollama
  ports:
    - "11434:11434"
```

Pre-pull model 1 lần: `docker compose --profile ai-fallback run ollama ollama pull llama3.1:8b`.

### 3. Pre-warm cache script

`ops/prewarm-ai-cache.sh` — gọi endpoint AI service cho các scenario demo đã chốt trước, populate `core.ai_cache`. Chạy 1 lần, kết quả nằm trong PG nên persist qua restart.

### 4. Metric

- `ai_provider_calls_total{provider, model, result}`
- `ai_gemini_quota_remaining_ratio` — từ response header
- `ai_cache_hit_ratio`
- `ai_fallback_triggered_total{reason}` — `quota_exceeded|rate_limit|timeout|ollama_down`

### 5. Graceful degraded UX

- Essay grading request fail mọi tier → `ai_explanation_status='FAILED'`, UI hiển thị "Chấm tự động tạm thời không khả dụng, chờ giáo viên chấm tay". Teacher dashboard có danh sách "essay chưa chấm AI".
- Tutor explain fail → nút "Thử lại" trong UI, không block kết quả.
- Generate Q fail → job `FAILED` với `error_message`, teacher bấm retry.

## References

- Gemini free tier — https://ai.google.dev/pricing
- Ollama — https://ollama.com
- `docs/ai-service-design.md` §6 — config
- `docs/scope-datn.md` §9.4 — cost estimate (update $0)
