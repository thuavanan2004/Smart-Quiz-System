# ADR-007: Dùng pgvector cho embedding thay cho vector DB riêng

- **Status**: Accepted
- **Date**: 2026-04-22
- **Deciders**: thuavanan2004
- **Related**: `docs/scope-datn.md`, `docs/database.md` §1, ADR-003, ADR-006

## Context

Combo A (ADR-006) cần embedding cho 2 mục đích:

1. **Dedupe câu hỏi AI sinh** — cosine similarity giữa câu mới và câu đã có (ngưỡng 0.92).
2. **Stylometry baseline** — so sánh essay mới với baseline của student (average embedding).

Ước tính volume: ~500 question vector + ~100 student profile vector + ~2k essay embedding cache. **Tổng < 3k vector**, dimension = 384.

Các lựa chọn vector store phổ biến: Pinecone (managed), Qdrant (self-host), Weaviate (self-host), Milvus, Chroma, **pgvector** (PG extension).

Trong scope DATN:
- Đã có PG làm DB chính (ADR-004, ADR-005).
- Volume siêu nhỏ (3k vector) — không cần ANN index tối ưu cực đoan.
- Muốn tránh thêm 1 DB nữa.

## Decision

Chúng ta sẽ dùng **pgvector extension** (đã có image `pgvector/pgvector:pg16`)
làm vector store, không thêm Qdrant/Pinecone/Weaviate.

Cấu hình:

```sql
CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE core.questions ADD COLUMN embedding vector(384);
ALTER TABLE core.student_writing_profiles ADD COLUMN avg_embedding vector(384) NOT NULL;

-- Index HNSW (faster ANN) vì pgvector 0.5+ support
CREATE INDEX ix_questions_embedding
  ON core.questions USING hnsw (embedding vector_cosine_ops);
```

Embedding được sinh bởi AI service qua model **`sentence-transformers/all-MiniLM-L6-v2`** (384d, chạy CPU) — không gọi API bên ngoài, không phát sinh cost.

## Alternatives considered

| Lựa chọn                             | Ưu                                          | Nhược                                                | Lý do loại                                 |
| ------------------------------------ | ------------------------------------------- | ---------------------------------------------------- | ------------------------------------------ |
| A. Pinecone (managed SaaS)           | Scale tự động, no-ops                        | Phải trả phí, latency network, vendor lock-in        | Budget DATN ~$0, không cần scale           |
| B. Qdrant (self-host Docker)         | Tốt cho production vector; API chuyên biệt  | +1 container, +300MB RAM, +1 migration tool          | Overhead không xứng 3k vector              |
| C. Weaviate (self-host)              | GraphQL, modular                             | +1 container, thêm config phức tạp                   | Như B                                      |
| D. In-memory numpy trong AI service  | Không cần DB, rất nhanh                     | Mất khi restart; không share giữa worker; stateful    | Stateless AI là nguyên tắc                 |
| **E. pgvector (đã chọn)**            | **Tận dụng PG có sẵn; SQL TX với bảng khác** | **HNSW index chậm hơn Qdrant vài lần — nhưng không có ý nghĩa ở scale 3k vector** | **Ít overhead nhất; đồng bộ với ADR-004/005** |

## Consequences

### Positive

- Zero thêm container, zero thêm SDK.
- Embedding + business data trong cùng 1 transaction (embed câu hỏi + INSERT question cùng TX).
- `pg_dump` backup toàn bộ bao gồm vector.
- Cross-table JOIN + vector search đồng thời (`WHERE difficulty='MEDIUM' AND embedding <=> $1 < 0.3`).
- Tận dụng HNSW index pgvector 0.5+ — đủ nhanh cho < 1M vector.

### Negative / trade-offs

- Khi volume > 1M vector + QPS cao, pgvector chậm hơn Qdrant/Milvus ~5–10x. **Không là vấn đề DATN.**
- PG vacuum / bloat khi update nhiều embedding cần theo dõi — DATN rare update nên không đáng lo.
- Không hỗ trợ binary quantization hay scalar quantization (pgvector 0.7+ có nhưng ta không cần).

### Neutral

- Spring Boot cần driver `com.pgvector:pgvector-java` cho JPA/JDBC type converter.
- Python async driver: `pgvector.asyncpg` hoặc raw SQL.

## Implementation notes

1. Image Docker: `pgvector/pgvector:pg16` (thay cho `postgres:16`).
2. Flyway migration enable extension:
   ```sql
   -- V20260501__extensions.sql (schema public)
   CREATE EXTENSION IF NOT EXISTS vector;
   CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
   CREATE EXTENSION IF NOT EXISTS pgcrypto;
   ```
3. Core service dùng `com.pgvector:pgvector-java` (gradle):
   ```kotlin
   implementation("com.pgvector:pgvector-java:0.1.6")
   ```
4. AI service dùng `pgvector` + `asyncpg`:
   ```python
   from pgvector.asyncpg import register_vector
   ```
5. HNSW index params (default OK cho DATN): `m=16, ef_construction=64`.
6. Distance operator: `<=>` (cosine), `<->` (L2), `<#>` (inner product). Chọn **cosine** cho cả dedupe câu hỏi và stylometry vì sentence-transformers khuyến nghị.

## References

- `docs/database.md` §4.1 (questions.embedding), §4.4 (student_writing_profiles)
- `docs/ai-service-design.md` §3.6 (endpoint /embed), §5.2 (stylometry), §5.3 (RAG dedupe)
- pgvector — https://github.com/pgvector/pgvector
- HNSW benchmark — https://ann-benchmarks.com/
