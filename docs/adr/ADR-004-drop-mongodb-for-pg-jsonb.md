# ADR-004: Dùng PostgreSQL JSONB cho câu hỏi thay cho MongoDB

- **Status**: Accepted
- **Date**: 2026-04-22
- **Deciders**: thuavanan2004
- **Related**: `docs/scope-datn.md`, `docs/core-service-design.md`, ADR-003 (DATN scope)

## Context

Thiết kế production (archive) dùng **MongoDB** cho `questions` collection với lập luận:
- Câu hỏi có cấu trúc đa dạng (MCQ, True/False, Essay, rubric, media…).
- Có thể scale horizontally khi question bank > 1M câu.
- Query theo nested field linh hoạt hơn SQL.

Trong scope DATN:
- Question bank ước tính **~500 câu** (bao gồm AI-generated).
- Chỉ có 1 pattern query chính (`SELECT ... WHERE type/topic/difficulty`) + embedding similarity qua pgvector.
- Chấp nhận single-tenant, không cần shard cross-region.
- Vận hành 2 DB khác công nghệ (PG + Mongo) tốn thời gian setup + migration + backup + monitoring mà không đem lại giá trị.

PostgreSQL JSONB từ PG 12+ đã:
- Hỗ trợ GIN index trên path trong JSONB.
- Có operator `->`, `->>`, `@>`, `?` đủ linh hoạt cho truy vấn nested.
- Full-text search qua `tsvector` (đã quyết định thay Elasticsearch — xem ADR-005 tinh thần tương tự).

## Decision

Chúng ta sẽ **lưu toàn bộ nội dung câu hỏi trong `core.questions.content JSONB`** trên PostgreSQL, không dùng MongoDB.

Cụ thể:

```sql
CREATE TABLE core.questions (
    id          UUID PRIMARY KEY,
    type        core.question_type NOT NULL,
    difficulty  core.difficulty NOT NULL,
    content     JSONB NOT NULL,   -- stem, options[], correct_answer, rubric
    metadata    JSONB NOT NULL,   -- topic, tags, bloom_level
    embedding   vector(384),      -- pgvector (xem ADR-007)
    ...
);
```

Index:
- `USING GIN (metadata jsonb_path_ops)` cho filter theo tag/topic.
- `USING HNSW (embedding vector_cosine_ops)` cho similarity search.

## Alternatives considered

| Lựa chọn                          | Ưu                                          | Nhược                                                          | Lý do loại                                      |
| --------------------------------- | ------------------------------------------- | -------------------------------------------------------------- | ----------------------------------------------- |
| A. MongoDB (như design gốc)       | Schema linh hoạt, native JSON, horizontal   | Thêm 1 DB phải vận hành; split truth giữa PG/Mongo; backup phức tạp | Overhead lớn, DATN scale không cần              |
| **B. PG JSONB (đã chọn)**         | **1 DB duy nhất; TX cross-entity OK; backup đơn giản** | **Document rất lớn (>1MB) hiệu năng kém hơn Mongo, nhưng câu hỏi < 10KB nên không là vấn đề** | **Match DATN scale**                            |
| C. PG column quan hệ thuần         | Query SQL thuần, tooling tốt                | Schema cứng, mỗi loại câu hỏi phải JOIN nhiều bảng             | Mất linh hoạt khi thêm type mới                 |

## Consequences

### Positive

- Chỉ vận hành 1 DB (PostgreSQL image `pgvector/pgvector:pg16`).
- Câu hỏi + câu trả lời cùng ACID transaction — không race condition giữa 2 DB.
- Backup đơn giản (`pg_dump`).
- Liền mạch với `ai_cache`, `student_writing_profiles` (cũng là PG).
- Tiết kiệm ~500MB RAM cho Mongo container khi demo trên laptop.

### Negative / trade-offs

- Khi question bank > 100k câu, PG JSONB có thể chậm hơn Mongo — đây là ngưỡng future work (ADR-003 future work #1: tách Question ra service riêng + Mongo khi scale).
- Không có schema validation runtime (Mongo có JSON Schema validator); bù bằng Pydantic/Jackson + Bean Validation ở application layer.

### Neutral

- Migration Flyway vẫn dùng SQL thuần; không cần `migrate-mongo`.

## Implementation notes

1. Schema `core.questions.content JSONB` — xem `docs/database.md` §4.1.
2. Drop folder `database/mongodb/` (di chuyển sang archive — xem ADR-003 checklist).
3. Application layer validate JSON content qua Java record / Pydantic tuỳ service.
4. Full-text search câu hỏi (future): add column `search_vector tsvector GENERATED ALWAYS AS (to_tsvector('simple', content->>'stem')) STORED`.

## References

- `docs/database.md` §4.1
- `docs/core-service-design.md` §3.1
- PG JSONB performance — https://www.postgresql.org/docs/16/datatype-json.html
