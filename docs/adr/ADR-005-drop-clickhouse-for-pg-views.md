# ADR-005: Dùng PostgreSQL view cho analytics thay cho ClickHouse

- **Status**: Accepted
- **Date**: 2026-04-22
- **Deciders**: thuavanan2004
- **Related**: `docs/scope-datn.md`, `docs/core-service-design.md`, ADR-003

## Context

Thiết kế production (archive) dùng **ClickHouse** cho OLAP với các bảng
`exam_facts`, `answer_analytics`, `cheat_analytics`, `question_irt_params`.
Lý do gốc: support query aggregate nhanh trên hàng triệu row, materialized
view phức tạp, IRT calibration.

Trong scope DATN:
- Volume ước tính: ~500 attempts, ~10k answers, ~20k cheat events / toàn bộ vòng đời demo.
- Query analytics chỉ cần:
  1. Thống kê mỗi exam (count, avg score, histogram).
  2. Tỉ lệ đúng/sai per câu hỏi.
  3. Danh sách cheat alert theo attempt.
- IRT calibration đã cắt (ADR-003 future work).
- ClickHouse cần ~800MB RAM + ingestion pipeline (Kafka Engine hoặc Materialized View) — overhead không xứng với query load DATN.

PostgreSQL với ~10k–100k row:
- `CREATE VIEW` thuần trả về < 50ms cho tất cả use case DATN.
- Nếu chậm → nâng lên `MATERIALIZED VIEW` + cron `REFRESH` mỗi 5 phút.
- Không phải vận hành DB thứ hai, không phải duy trì 2 nguồn truth.

## Decision

Chúng ta sẽ **thay ClickHouse bằng view trong schema `core`** trên cùng PG instance.

Định nghĩa (xem `docs/database.md` §4.6):

```sql
CREATE VIEW core.v_exam_stats         AS ...  -- count, avg, median, min/max score per exam
CREATE VIEW core.v_score_histogram    AS ...  -- bucket 10% điểm per exam
CREATE VIEW core.v_question_quality   AS ...  -- pct_correct per question
```

Nếu latency vượt 300ms trên query nào → convert sang `MATERIALIZED VIEW` +
cron `REFRESH MATERIALIZED VIEW CONCURRENTLY` mỗi 5 phút.

## Alternatives considered

| Lựa chọn                                | Ưu                                              | Nhược                                                       | Lý do loại                                   |
| --------------------------------------- | ----------------------------------------------- | ----------------------------------------------------------- | -------------------------------------------- |
| A. ClickHouse (như design gốc)          | Aggregate cực nhanh, column-store tối ưu        | +800MB RAM, +1 DB, +ingestion pipeline, phải dedupe cross-store | Overkill cho DATN scale                      |
| **B. PG view (đã chọn)**                | **Không thêm DB, join cross-table đơn giản**    | **Chậm khi rows > 10M — nhưng DATN < 100k row**             | **Match DATN scale**                         |
| C. PG materialized view ngay từ đầu     | Nhanh hơn view thuần                            | Phải refresh; delay 5 phút                                  | Overkill khi view thường chạy < 50ms          |
| D. Precompute trong Kafka consumer      | Realtime, không query time                      | Code phức tạp, duplicate logic, sync issue                  | Không đáng                                   |

## Consequences

### Positive

- Giảm 1 DB + 1 migration tool (`clickhouse-migrations`).
- Tiết kiệm ~800MB RAM cho demo trên laptop.
- Query cross-entity đơn giản (view JOIN `exams`, `exam_attempts`, `attempt_answers` trong cùng schema).
- Analytics luôn real-time (view = current data), không có lag đến ClickHouse.

### Negative / trade-offs

- Nếu demo scale bất ngờ (>100k attempts) → view có thể chậm, phải escalate materialized.
- Mất các tính năng CH như aggregation function phức tạp (quantileExact, uniqExact). PG có percentile_cont(), percentile_disc() là đủ cho DATN.
- Không có column store compression; disk footprint lớn hơn nhưng không đáng kể (< 100MB total).

### Neutral

- Teacher dashboard query trực tiếp view qua Core REST.
- Test view bằng SQL assertion trong Flyway repeatable migration `R__test_views.sql` (optional).

## Implementation notes

1. Xem DDL view trong `docs/database.md` §4.6.
2. Drop folder `database/clickhouse/` (archive).
3. Nếu view nào > 300ms trong test → rewrite thành materialized + cron:
   ```sql
   CREATE MATERIALIZED VIEW core.mv_exam_stats AS ...;
   CREATE UNIQUE INDEX ON core.mv_exam_stats (exam_id);
   -- refresh job
   REFRESH MATERIALIZED VIEW CONCURRENTLY core.mv_exam_stats;
   ```
4. `spring.jpa.properties.hibernate.query.use_class_level_check` để Hibernate không kiểm tra FK trên view entity.

## References

- `docs/database.md` §4.6
- `docs/core-service-design.md` §3.5
- PG materialized view — https://www.postgresql.org/docs/16/rules-materializedviews.html
