# Kafka Topics — SmartQuizSystem

Danh mục topic chính thức (v1). Tạo topic bằng script
`ops/kafka/create-topics.sh` khi chạy stack lần đầu.

| Topic | Schema | Producer | Consumer | Partitions | Retention | Ghi chú |
|-------|--------|----------|----------|-----------:|-----------|---------|
| `exam.attempt.submitted.v1` | `exam.attempt.submitted.v1.avsc` | exam | analytics, ai, notification | 20 | 7d | Key=attempt_id |
| `exam.answer.submitted.v1` | `exam.answer.submitted.v1.avsc` | exam | analytics, cheat-detection | 20 | 7d | Key=attempt_id; tăng từ 50 xuống 20 cho 100K MAU |
| `grading.request.v1` | `grading.request.v1.avsc` | exam | ai | 10 | 3d | Key=submission_id; bulkhead broker riêng nếu có thể |
| `grading.request.v1.DLQ` | (giống `grading.request.v1`) | ai (fail) | operator | 3 | 14d | Dead letter sau N retry |
| `grading.result.v1` | `grading.result.v1.avsc` | ai | exam, analytics | 10 | 7d | Key=submission_id |
| `cheat.event.raw.v1` | `cheat.event.raw.v1.avsc` | exam (relay từ client) | cheat-detection, analytics | 20 | 30d | Key=attempt_id; giữ 30d cho audit |
| `cheat.alert.generated.v1` | `cheat.alert.generated.v1.avsc` | cheat-detection | exam, notification, proctor-ui | 10 | 90d | Key=attempt_id; giữ lâu cho appeal |
| `auth.role.changed.v1` | `auth.role.changed.v1.avsc` | auth | all services có cache permission | 3 | 7d | Key=org_id |

## Naming convention

- `{domain}.{entity}.{action}.v{major}`
- Luôn kèm version suffix. Breaking change → tạo topic mới (`.v2`), không overwrite.
- DLQ: append `.DLQ` (VD: `grading.request.v1.DLQ`).

## Consumer group convention

- `{service}-{purpose}`, ví dụ `analytics-exam-ingest`, `exam-grading-result-handler`.
- Mỗi consumer group là at-least-once + idempotent (dedupe bằng `event_id` lưu trong bảng `processed_events` per service).

## Partition key

- Giữ thứ tự trong phạm vi attempt/user → key theo `attempt_id` hoặc `user_id`.
- `auth.role.changed` key theo `org_id` để giảm invalidation noise giữa các org.
