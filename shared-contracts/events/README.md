# shared-contracts/events

Contract cho event Kafka trong SmartQuizSystem (DATN scope).

- **Format**: JSON (không Avro — xem `docs/scope-datn.md` §3).
- **Schema language**: JSON Schema draft 2020-12.
- **Versioning**: bump qua **tên topic** (`exam.attempt.submitted.v1` → `.v2`). Trong schema giữ field `schema_version` để parser sanity-check.

## Envelope chung

Mọi event có envelope thống nhất (bắt buộc 5 field):

```json
{
  "event_id":       "uuid",                      // dedupe key
  "event_type":     "exam.attempt.submitted.v1", // bằng topic name
  "occurred_at":    "2026-05-01T10:23:45.123Z",  // RFC 3339, UTC
  "schema_version": 1,                            // tăng monotonic
  "payload":        { /* per-topic */ }
}
```

Xem `_envelope.schema.json`. Mỗi schema topic `$ref` envelope rồi override `event_type` thành const và define `payload`.

## Dedupe

Consumer dedupe `event_id` qua bảng `processed_events` (mỗi service có bảng riêng trong schema của mình). Insert `event_id` cùng transaction với hành động chính → idempotent. Xem `docs/core-service-design.md` §5.4.

## Topic list (10 topic, scope DATN)

| Schema file                                | Producer   | Consumer   | Trigger                                              |
| ------------------------------------------ | ---------- | ---------- | ---------------------------------------------------- |
| `exam.answer.submitted.v1.schema.json`     | Core       | Core       | Mỗi lần student submit 1 đáp án (outbox).            |
| `exam.attempt.submitted.v1.schema.json`    | Core       | Core       | Attempt chuyển SUBMITTED (outbox).                   |
| `grading.request.v1.schema.json`           | Core       | AI         | Sau submit, cho ESSAY + SHORT_ANSWER fallback.       |
| `grading.result.v1.schema.json`            | AI         | Core       | AI chấm xong essay / short-answer semantic.          |
| `cheat.event.raw.v1.schema.json`           | Core (WS)  | Proctoring | WS handler forward (direct publish, không outbox).   |
| `cheat.alert.v1.schema.json`               | Proctoring | Core       | Rule engine vượt ngưỡng (outbox).                    |
| `question.generation.request.v1.schema.json` | Core     | AI         | Teacher tạo generation job (outbox).                 |
| `question.generation.result.v1.schema.json`  | AI       | Core       | AI sinh xong batch câu hỏi.                          |
| `tutor.explanation.request.v1.schema.json` | Core       | AI         | Batch wrong answers sau submit (outbox).             |
| `tutor.explanation.result.v1.schema.json`  | AI         | Core       | AI tutor explain xong 1 wrong answer (per-item).     |

## Quy ước

- **Kafka key** = `attempt_id` (cho event scope attempt) hoặc `document_id` / `job_id` (cho generation). Đảm bảo same-attempt event đi cùng partition → ordering.
- **Headers** Kafka đính kèm: `event_id`, `schema_version`, `event_type` (duplicate với body nhưng cho consumer filter rẻ).
- **Timestamp** mọi field datetime: RFC 3339 với timezone (UTC khuyến nghị, format `Z`).
- **UUID**: lowercase, có dấu gạch (chuẩn RFC 4122).

## Khi cần bump version (.v2)

1. Tạo file mới `*.v2.schema.json`, giữ `*.v1.schema.json`.
2. Producer publish song song 2 topic trong giai đoạn migration.
3. Consumer thêm handler `.v2`, giữ `.v1`.
4. Khi mọi consumer chuyển xong → producer drop `.v1`.

DATN chưa cần bao giờ. Quy ước này là defend cho hội đồng.
