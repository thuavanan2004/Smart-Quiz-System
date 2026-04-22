# ADR-003: Rút gọn hệ thống cho phạm vi đồ án tốt nghiệp

- **Status**: Accepted
- **Date**: 2026-04-22
- **Deciders**: thuavanan2004
- **Related**: `docs/scope-datn.md`, ADR-001 (SLA/RPO/outbox), ADR-002 (Analytics/Cheating split)

## Context

Hệ thống ban đầu được thiết kế production-ready với 6 microservice (Auth, Exam,
Question, AI, Analytics, Cheating Detection), 4 DB chuyên dụng (PG, MongoDB,
ClickHouse, Elasticsearch), Kafka + Avro + Apicurio Registry, gRPC inter-service,
Flink cho cheat stream, và đầy đủ feature IRT/DIF, A/B experiment, video
proctoring, multi-tenancy.

Sau audit cross-service (conversation 2026-04-22), nổi lên các vấn đề:

1. **22+ Avro topic referenced nhưng chưa có `.avsc` nào**.
2. **4 file gRPC proto chưa tồn tại**.
3. **Ranh giới Analytics vs Cheating** chưa rõ (ADR-002 bỏ ngỏ).
4. **Thời lượng DATN (~3–4 tháng)** không đủ để implement đầy đủ mà vẫn có chất
   lượng code + test + demo ổn.
5. **Hội đồng DATN** đánh giá dựa trên: demo chạy mượt, có điểm sáng kỹ thuật,
   kiến trúc hợp lý — không yêu cầu production scale thật.

Đồng thời, các design doc production-ready **đã đầy đủ và có giá trị** — không
nên xóa mà nên để làm tài liệu tham chiếu kiến trúc đầy đủ, thể hiện năng lực
thiết kế hệ thống.

## Decision

Chúng ta sẽ **implement một subset gọn** của hệ thống trong phạm vi DATN, cụ thể:

- **4 service** thay vì 6: Auth, Core (gộp Exam + Question + Analytics),
  AI (Python), Proctoring (cheat L1–L3).
- **1 PostgreSQL** với schema riêng cho mỗi service; drop MongoDB, ClickHouse,
  Elasticsearch.
- **JSON event** thay Avro + Apicurio; dùng version qua tên topic (`.v1`).
- **REST inter-service** thay gRPC.
- **Spring Boot Kafka consumer** thay Flink.
- **Single-tenant** — loại `org_id` khỏi mọi bảng + event.
- **Giữ** các điểm kỹ thuật có giá trị defend: JWT RS256 + JWKS, outbox pattern,
  `state_version` fencing, idempotent consumer, WebSocket real-time, AI service
  chấm essay + generate câu hỏi, cheat L1–L3.
- **Nới NFR**: RPO từ ≤5s → ≤30s; SLA best-effort; không cam kết RTO.
- **Giữ nguyên** tất cả design doc production trong `docs/` làm tham chiếu;
  thêm `docs/scope-datn.md` làm source of truth cho phần thực sự code.

## Alternatives considered

| Lựa chọn                                     | Ưu                                                     | Nhược                                                          | Lý do loại                                                  |
| -------------------------------------------- | ------------------------------------------------------ | -------------------------------------------------------------- | ----------------------------------------------------------- |
| A. Implement đầy đủ như design production    | Full fidelity với doc                                  | Không khả thi trong 3–4 tháng; chất lượng code/test bị hy sinh | Quá tham vọng cho DATN, rủi ro không demo được              |
| B. Modular monolith 1 Spring Boot app + AI   | Đơn giản nhất, nhanh nhất                              | Mất tinh thần microservice khi defend; ít điểm kỹ thuật để nói | Hội đồng có thể cho rằng không đủ thử thách kiến trúc       |
| **C. 4 service, cắt feature/infra thừa (đã chọn)** | **Cân bằng demo được + có điểm defend + khả thi**     | **Phải duy trì 2 bộ doc (production + DATN)**                  | **Trả lời tốt nhất cho DATN**                               |
| D. 6 service gộp Analytics+Cheating như ADR-002 đề xuất | Giữ kiến trúc gần design gốc nhất                    | Vẫn phải implement Avro, gRPC, Mongo, CH... quá nhiều          | Overhead infra không đổi, chỉ cắt 1 service, không đủ đơn giản |

## Consequences

### Positive

- Scope implement thu hẹp ~60%, khả thi trong thời gian DATN.
- Vẫn giữ đủ điểm kỹ thuật để defend: JWT + JWKS, outbox, fencing, idempotent,
  AI integration, WebSocket, cheat detection real-time.
- Docs production đầy đủ vẫn tồn tại như minh chứng năng lực thiết kế → khi
  hội đồng hỏi "scale thế nào" có sẵn câu trả lời chi tiết.
- Ít phụ thuộc infra → demo trên laptop dễ hơn (không cần Flink, CH, ES).
- Đóng ADR-002 (ranh giới Analytics/Cheating) vì Analytics gộp vào Core.

### Negative / trade-offs

- Phải duy trì **2 layer doc**: production design (`docs/*-service-design.md`)
  và DATN scope (`docs/scope-datn.md`). Nếu không cẩn thận, code có thể drift
  khỏi cả hai.
- RPO nới ≤30s — về lý thuyết có thể mất đáp án nếu relayer + Kafka down đúng
  lúc; chấp nhận vì DATN không cam kết SLA.
- Mất điểm "Avro schema governance", "gRPC type-safe", "Flink stream" khi defend.
  Bù lại bằng "outbox + idempotent + JWT/JWKS + AI" là đủ nặng ký.
- Single-tenant khó migrate lên multi-tenant sau — nhưng đã thiết kế sẵn trong
  doc production nên đã "biết làm", chỉ là không làm.

### Neutral

- 4 service vẫn gọi là microservice — đủ minh họa service boundary, IDP tách
  riêng, AI khác ngôn ngữ.
- JWT RS256 + JWKS giữ nguyên → code auth giống hệt production.

## Implementation notes

Sau khi accept ADR này:

1. Cập nhật `CLAUDE.md`:
   - §1: thêm "Phạm vi code đi theo `docs/scope-datn.md`, docs service là tham chiếu kiến trúc đầy đủ."
   - §3 NFR: nới RPO ≤30s, bỏ SLA 99.9%, bỏ `org_id` rule.
   - §2 Stack: bỏ Mongo, ClickHouse, ES, Avro/Apicurio, gRPC, Flink.
2. Tạo ADR-004 (drop MongoDB, dùng PG JSONB cho question).
3. Tạo ADR-005 (drop ClickHouse, dùng PG view cho analytics).
4. Rà `database/postgresql/schema.sql`:
   - Drop mọi cột `org_id`.
   - Drop bảng `organizations`.
   - Merge schema Exam + Question + Analytics thành schema `core`.
   - Giữ `state_version`, `outbox`, `processed_events`.
5. Move `database/{mongodb,clickhouse,elasticsearch}/` → `docs/future-work/db/`.
6. Rà `infra/docker-compose.dev.yml`: disable Mongo, CH, ES, Flink, Apicurio
   containers. Giữ PG, Redis, Kafka, Zookeeper.
7. Tạo `shared-contracts/events/` với 6 JSON schema cho topic DATN.
8. Đóng ADR-002 với outcome "Superseded by ADR-003 — Analytics gộp vào Core,
   không còn vấn đề ranh giới trong DATN scope".

## References

- `docs/scope-datn.md` — chi tiết scope DATN
- `docs/adr/ADR-001-sla-rpo-outbox.md` — SLA/RPO gốc (NFR được nới trong DATN)
- `docs/adr/ADR-002-analytics-vs-cheating-split.md` — superseded by this ADR
- `docs/design.md` — kiến trúc production tham chiếu
