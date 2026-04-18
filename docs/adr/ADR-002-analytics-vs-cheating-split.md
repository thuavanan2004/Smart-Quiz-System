# ADR-002: Giữ Analytics Service và Cheating Detection Service là 2 service riêng

- **Status**: Accepted
- **Date**: 2026-04-18
- **Deciders**: Kiến trúc hệ thống (SmartQuizSystem)
- **Related**:
  - `docs/design.md` §2.1, §3.1
  - `docs/analytics-service-design.md`
  - `docs/cheating-detection-service-design.md`
  - `CLAUDE.md` §9.2 (gap đã biết)

## Context

Trong quá trình review thiết kế, có câu hỏi gộp Analytics Service (AS) và Cheating Detection
Service (CDS) làm 1:

- Cả hai đều **consume event stream Kafka**.
- Cả hai đều **dùng ClickHouse** làm analytic store.
- Cả hai đều có tác vụ **ML / statistical**.
- Team mới ít, 2 service tương đồng có thể tạo confusion, duplicate infra.

Ngược lại, 2 service có lý do để tách:

| Tiêu chí | Analytics | Cheating Detection |
| -------- | --------- | ------------------ |
| SLA | 99.9% (dashboard), 99.5% (ad-hoc) | 99.9% **nhưng real-time** |
| Latency target | Dashboard < 500ms (materialized), batch 24h OK | Event → alert p99 < 500ms |
| Ảnh hưởng hot path | Không — nếu down, dashboard trễ | Có indirect — nếu down, không phát hiện gian lận (fail-open) |
| Tính chất xử lý | Batch-heavy (Spark daily), stream cho dashboard | Stream-heavy (Kafka Streams + Flink), model serving GPU |
| Stateful | Materialized views, aggregation | Per-attempt state machine, risk score |
| Stack đặc thù | Spark, Trino, Airflow, Iceberg | Triton GPU, ONNX, MaxMind GeoIP, YOLOv8 |
| Stakeholder | Học sinh, giáo viên, admin BI | Proctor, giáo viên (review queue), compliance |
| Write pattern | Append-only event-sourced analytics | Write score (mutable) + append alert + read-modify-write review queue |
| Ngôn ngữ audit | "Insight" — kết quả học tập | "An ninh" — có thể ảnh hưởng học sinh bị oan |
| Failure mode khi sai | Dashboard lệch, có thể correct sau batch | False positive → học sinh bị suspend oan, gây tổn hại danh tiếng |

Cần quyết định dứt điểm để không loop lại câu hỏi này mỗi sprint.

## Decision

**Giữ Analytics Service và Cheating Detection Service là 2 service riêng biệt.**

- Mỗi service có repo module, Kubernetes Deployment, team owner, SLA, on-call rotation, schema store riêng.
- Chia sẻ **infrastructure layer** (Kafka, ClickHouse cluster, Prometheus, Loki) nhưng
  **không chia sẻ code, không chia sẻ domain model, không cross-call trực tiếp**.
- Khi CDS cần dữ liệu tổng hợp lịch sử (L6 — phát hiện đáp án giống nhau xuyên attempt),
  đọc **từ ClickHouse (fact tables của Analytics)** qua query gRPC tới Analytics Service,
  không tự maintain view.
- Khi Analytics cần số liệu cheating cho dashboard giáo viên, consume chung topic
  `cheat.alert.generated.v1` từ Kafka — không gọi API CDS.

## Alternatives considered

| Lựa chọn | Ưu | Nhược | Lý do loại |
| -------- | -- | ----- | ---------- |
| Gộp thành 1 service "Insight & Security" | Ít service, dễ vận hành | SLA xung đột (real-time vs batch); GPU cho cheating kéo theo cả batch workload; blast radius lớn khi deploy | Coupling SLA là lý do chính không làm |
| Gộp chỉ Analytics + L6 (statistical cheating cross-attempt) | L6 vốn là statistical, cần Spark/Flink | L6 phụ thuộc risk score và review queue (state của CDS) — tách đôi khó hơn là tách nguyên khối | Không đáng tách 1 tầng |
| Tách riêng thêm Proctoring Service (video L5) | Vision AI độc lập GPU pool | Tăng thêm 1 service phải vận hành ở MVP | Hoãn: để L5 nằm trong CDS giai đoạn MVP, tách ra khi quy mô GPU đáng kể (Phase 2) |
| Shared library "analytics-common" cho 2 service | Giảm duplicate | Tạo hidden coupling, release 1 lib kéo theo redeploy cả 2 service | Dùng shared *contracts* (Avro), không shared *code* |

## Consequences

### Positive

- **SLA tách biệt** — deploy Analytics không risk cho hot path cheating; fail isolation rõ ràng.
- **Scale độc lập** — CDS scale theo event/s; Analytics scale theo query/s + batch window.
- **Team ownership rõ** — khi lên tổ chức: team DataInsight owns AS, team TrustSafety owns CDS.
- **Security boundary** — CDS chạm dữ liệu nhạy cảm (video, keystroke dynamics). Tách service cho phép
  network policy, audit log, RBAC chặt hơn mà không ảnh hưởng team BI.
- **Trách nhiệm quyết định rõ** — CDS chỉ phát hiện; Exam Service quyết định trạng thái; AS không
  đụng đến kỷ luật. Nếu gộp, ranh giới này mờ đi.

### Negative / trade-offs

- **Duplicate consumer Kafka** cho các topic cả 2 cùng cần (`exam.answer.submitted.v1`,
  `exam.attempt.submitted.v1`). Chấp nhận: Kafka được thiết kế cho fan-out, đây là đúng pattern.
- **Duplicate ClickHouse cluster hoặc ít nhất là database** — cần quyết định sub-issue: dùng
  chung cluster CH + schema khác nhau, hay tách cluster. Chọn: **shared cluster, tách database**
  để tiết kiệm infra nhưng isolate quota.
- **Cross-service query**: khi CDS cần dữ liệu historical analytics (L6), phải gọi gRPC →
  thêm latency. Chấp nhận: L6 chạy batch (Spark), không trên hot path.
- **Infra cost** cao hơn so với 1 service: ~2x pods tối thiểu (mỗi service ≥ 3 pod HA).

### Neutral

- Nếu sau này scale 1M+ user và thấy coupling giữa 2 service quá chặt (mỗi lần publish
  event 2 mặt phải coordinate), có thể revisit — nhưng lúc đó là vấn đề contract evolution,
  không phải vấn đề topology.
- Cần coordinate cleanup retention giữa 2 service — Analytics giữ `cheat_analytics` 25 tháng, CDS giữ
  `cheat_events` 24 tháng. Mismatch chấp nhận được, doc rõ.

## Implementation notes

- Confirm topic fan-out trong `shared-contracts/avro/TOPICS.md` với consumer group riêng cho mỗi service:
  - `analytics-cheat-consumer` (AS) ≠ `cheat-detection-raw-consumer` (CDS).
- Network policy: CDS egress chỉ cho phép đến ClickHouse + Redis + Kafka; không gọi trực tiếp
  service domain khác (Exam / Auth) trừ qua event.
- Cheating Service publish `cheat.alert.generated.v1` → Analytics consume để materialize
  teacher dashboard. Analytics **không** publish event sang Cheating.
- **Proctoring Service** (video L5) giữ inline trong CDS ở MVP; tách ra thành service riêng khi
  GPU pool > 4 node hoặc khi cần đa vùng inference edge (Phase 2). Document quyết định tách
  trong ADR mới khi đến lúc.

## References

- `docs/analytics-service-design.md` §1.1 (Nguyên tắc: tách khỏi hot path; eventual consistency OK)
- `docs/cheating-detection-service-design.md` §1.1 (Nguyên tắc: real-time, fail-open, false positive < 0.5%)
- Sam Newman, *Building Microservices* — chương "Finding Seams" và "Decomposing the Monolith".
