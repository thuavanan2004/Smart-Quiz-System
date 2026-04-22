# Legacy databases (archived)

Thư mục này lưu schema + seed cho các DB **không dùng trong scope DATN**:

- `mongodb/` — question content (thay bằng `core.questions.content JSONB` — ADR-004)
- `clickhouse/` — OLAP analytics (thay bằng PG view trong `core.v_*` — ADR-005)
- `elasticsearch/` — full-text search + RAG corpus (thay bằng PG `tsvector` + pgvector — ADR-007)
- `docker-compose.yml` — stack all-in-one cũ (5 DB + Kibana). Thay bằng `infra/docker-compose.dev.yml` (PG+pgvector, Redis, Kafka).
- `LOCAL_NATIVE_SETUP.md` — hướng dẫn cài native 5 DB trên Windows. Không cần cho DATN vì chỉ còn 1 PG.

Giữ lại để:
1. Tham khảo kiến trúc production đầy đủ khi defend đồ án.
2. Làm roadmap migrate lên production nếu muốn thương mại hoá post-DATN.

**Không dùng các file này cho development DATN.** Scope thật sự nằm ở
`database/postgresql/` + `database/redis/` + `infra/docker-compose.dev.yml`.
