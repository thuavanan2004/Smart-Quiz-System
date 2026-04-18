\# ADR-001: SLA 99.9%, RPO đáp án ≤ 5s và Transactional Outbox

- **Status**: Accepted
- **Date**: 2026-04-18
- **Deciders**: Kiến trúc hệ thống (SmartQuizSystem)
- **Related**:
  - `docs/design.md` §4.1–4.4
  - `docs/exam-service-design.md` §1.4 (NFR), §III (Attempt aggregate)
  - `CLAUDE.md` §3 (NFR lock)

## Context

Hệ thống thi trực tuyến phải đảm bảo:

1. **Đáp án học sinh không được mất** khi pod Exam Service chết, Kafka broker khởi động lại,
   hay Redis failover. Đây là ràng buộc pháp lý/đạo đức: mất đáp án có nghĩa là hỏng bài
   thi của học sinh — không thể chấp nhận.
2. **Kỳ thi quan trọng** (tốt nghiệp, tuyển dụng) không chịu được outage dài —
   mục tiêu uptime phải đủ cao để không cần hoàn tiền / đền bù.
3. **Event-driven architecture** (Kafka) tách biệt Analytics / Cheating / Grading khỏi hot
   path. Nhưng điều đó chỉ đúng khi **event được phát đi đáng tin cậy** — một bug publish
   bị nuốt âm thầm sẽ khiến analytics lệch, cheating detection bỏ sót, grading không chạy.

Hiện tại trong repo không có cơ chế chống mất event giữa "ghi DB" và "publish Kafka".
Pattern ngây thơ (`save(entity); kafka.send(event);`) có 3 failure mode:

- Crash sau `save` trước `send` → DB có, Kafka không → consumer downstream không bao giờ biết.
- `kafka.send` lỗi tạm thời (broker chọn leader, network partition) → retry không có state lưu.
- `kafka.send` thành công nhưng ack chưa về → retry gửi 2 lần → consumer nhận duplicate.

Cần quyết định 3 con số + 1 cơ chế:

- **SLA target** (availability mục tiêu).
- **RPO đáp án** (dữ liệu có thể mất tối đa khi disaster).
- **Cơ chế đảm bảo event không mất + không duplicate** giữa DB source-of-truth và Kafka.

## Decision

### 1. SLA = 99.9% single-region (≤ 43,8 phút downtime / tháng)

- Mục tiêu mặc định cho toàn platform **single-region** ở giai đoạn MVP.
- Exam Service (luồng quan trọng) có **SLO nội bộ cao hơn**: 99.95% trong **window kỳ thi**
  (từ `starts_at - 15m` đến `ends_at + 30m`). Ngoài window áp dụng SLA chung 99.9%.
- Auth Service: 99.95% (nó là blocking dependency của mọi service khác — xem
  `auth-service-design.md` §14.2).
- Khi tăng trưởng lên 1M+ user hoặc đi đa region, nâng SLA lên 99.95% global — tạo ADR mới
  thay thế cái này.

### 2. RPO đáp án ≤ 5 giây

- Khi đáp án được chấp nhận (HTTP 200 trả về client), **tối đa 5 giây sau** đáp án phải
  durable ở cả PostgreSQL source-of-truth **và** đã được publish lên Kafka cho downstream
  consumer (Grading, Analytics, Cheating).
- RPO tính ở **tầng durable storage** (PG + Kafka), không phải Redis. Redis là cache
  write-through, không phải nguồn truth. Một Redis cluster sập không được phép gây mất
  đáp án — PG vẫn có.
- Các luồng ít quan trọng hơn (analytics.user.activity, auth.login.*): RPO "best effort",
  chấp nhận mất vài giây ở disaster. Không áp dụng outbox pattern cho chúng.

### 3. Transactional Outbox Pattern cho event quan trọng

Khi service cần publish event gắn với state change DB:

```
BEGIN;
  INSERT/UPDATE <bảng domain>;
  INSERT INTO outbox (event_id, aggregate_type, aggregate_id, topic, payload, created_at);
COMMIT;
```

Một **relayer process** (tách khỏi request thread) liên tục:

1. Poll `SELECT ... FROM outbox WHERE published_at IS NULL ORDER BY created_at LIMIT N FOR UPDATE SKIP LOCKED`.
2. Publish từng row lên Kafka với `event_id` làm Kafka message key (đảm bảo partition order + dedup key).
3. `UPDATE outbox SET published_at = now() WHERE event_id = ?` sau khi Kafka ack.
4. Row đã publish giữ lại N ngày cho debug, sau đó cleanup batch job.

Consumer downstream **dedupe bằng `event_id`** lưu vào bảng `processed_events` per-service.
Đây là half còn lại của at-least-once + idempotent:

```
BEGIN;
  INSERT INTO processed_events (event_id, topic, processed_at)  -- PK: event_id
    ON CONFLICT DO NOTHING;
  IF affected_rows = 0 THEN skip (đã xử lý);
  ELSE xử lý business logic;
COMMIT;
```

### Applied to

- **Exam Service**: `attempt_answers` insert + `outbox` row (`exam.answer.submitted.v1`) trong 1 transaction.
- **Exam Service**: `exam_attempts` state transition + `outbox` row (`exam.attempt.submitted.v1`).
- **Auth Service**: `users.role_changed` + `outbox` (`auth.role.changed.v1`) — để Exam / Question
  invalidate cache permission.
- **Cheating Detection**: `cheat.alert.generated.v1` khi risk score crosses threshold.

### Not applied to

- Analytics event từ FE (tracking click, page view) — dùng fire-and-forget Kafka producer, chấp nhận mất.
- `auth.login.success` — log audit, không drive state change ở service khác. Nếu mất vài event
  chỉ là miss vài dòng audit.

## Alternatives considered

| Lựa chọn | Ưu | Nhược | Lý do loại |
| -------- | -- | ----- | ---------- |
| SLA 99.99% single-region | Margin an toàn | Cần redundancy cấp hardware + 24/7 on-call 2 tier — chưa có tổ chức cho MVP | Quá đắt cho giai đoạn hiện tại |
| RPO đáp án = 0 (synchronous replication all-layer) | Không mất tuyệt đối | Latency `POST /answers` tăng >300ms, vi phạm NFR < 100ms p99 | Mục tiêu UX quan trọng hơn đảm bảo tuyệt đối |
| Chỉ dùng Redis AOF `fsync=always`, không outbox | Đơn giản, latency thấp | Redis không phải nguồn truth; mất Redis = mất answer nếu PG flush chưa kịp | Violation: Redis ≠ source of truth |
| Debezium CDC (đọc WAL PostgreSQL → Kafka) | Không đổi code domain | Thêm service phụ thuộc (Kafka Connect), schema evolution khó, không kiểm soát được format event (WAL raw) | Outbox cho phép event shape có chủ đích, dễ versioning Avro |
| 2PC (XA transaction PG + Kafka) | Strong consistency | Kafka không hỗ trợ XA production-grade; performance penalty lớn | Không khả thi |
| Event sourcing (store event, materialize state) | Audit miễn phí, replay dễ | Đại phẫu domain model; team chưa có kinh nghiệm | Over-engineering cho MVP |

## Consequences

### Positive

- **Không mất event** trong mọi failure scenario service-level (crash sau commit, Kafka broker
  không available tạm thời, network partition).
- **Consumer idempotent tự nhiên** — cùng `event_id` trả về từ outbox nhiều lần vẫn an toàn
  vì downstream có `processed_events` dedup.
- **Decoupling thật sự** — producer chỉ cam kết với DB của nó, không chặn chờ Kafka ack.
- **Debug dễ** — row trong `outbox` có thể inspect thủ công; re-publish được khi cần.

### Negative / trade-offs

- **Latency thêm**: relayer poll interval (mặc định 100ms) → event có thể delay 100–200ms so
  với publish trực tiếp. Vẫn nằm trong RPO 5s.
- **Write amplification** ở PG: mỗi state change tốn thêm 1 row `outbox`. Với 500k answer/phút
  cao điểm = 500k row outbox/phút. Cần partition `outbox` theo day + cleanup job.
- **Relayer là SPOF logic** — phải có HA (leader election qua advisory lock PG hoặc
  Kafka consumer group).
- **Contract lock-in**: event format cố định phải qua Apicurio Schema Registry, BACKWARD compat mode
  (đã lock trong CLAUDE.md §2).

### Neutral

- `processed_events` cũng phải cleanup định kỳ — giữ 7 ngày cho dedup window (Kafka retention ≤ 7 ngày
  cho hầu hết topic — xem `shared-contracts/avro/TOPICS.md`).
- Cần chuẩn hoá helper library `outbox-starter` (Spring) để 5 service Java không tái cài đặt mỗi lần.

## Implementation notes

1. Migration `V{epoch}__add_outbox_and_fencing.sql` tạo bảng `outbox`, `processed_events`
   (xem ADR-003 hoặc migration file).
2. Implement `OutboxRelayer` Spring component với advisory lock (`pg_try_advisory_lock`) để
   đảm bảo chỉ 1 instance publish tại 1 thời điểm trong cluster.
3. Consumer phải dùng `@Transactional` + check `processed_events` trước khi handle business logic.
4. Metric bắt buộc:
   - `outbox_pending_size` (gauge) — cảnh báo khi > 10k.
   - `outbox_publish_lag_seconds` (histogram) — cảnh báo p99 > 5s.
   - `processed_events_dedup_total{result="duplicate|new"}` — để debug consumer idempotency.
5. Test Testcontainers: kill producer sau `COMMIT` trước `relayer publish` → restart → event phải xuất hiện trên Kafka.

## References

- Chris Richardson, *Transactional Outbox Pattern* — microservices.io/patterns/data/transactional-outbox.html
- Debezium Outbox Event Router (không dùng ở đây vì code-owned outbox dễ kiểm soát hơn, nhưng có thể migrate sau).
- PostgreSQL `FOR UPDATE SKIP LOCKED` — 9.5+, dùng cho relayer pool nhiều instance.
