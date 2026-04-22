# Elasticsearch 8 - Setup Local

## Files

| File | Mô tả |
| ---- | ----- |
| `schema.json`              | Index mapping `question_search` (text + vector KNN) |
| `seed.ndjson`              | 7 document câu hỏi mẫu (bulk format) |
| `schema_rag_corpus.json`   | Index mapping `rag_corpus` (AI Service RAG knowledge base) |
| `seed_rag_corpus.ndjson`   | 5 chunks tài liệu mẫu cho RAG (CS101 sorting + CS201 graph/tree) |

## Tổng hợp Index

**Index:** `question_search`

| Field type | Fields |
| ---------- | ------ |
| `keyword` (lọc chính xác) | `question_id`, `org_id`, `status`, `type`, `subject_code`, `tags`, `bloom_level`, `language` |
| `text` (full-text, vi_analyzer) | `content_text`, `topic`, `explanation` |
| `float` / `integer` (xếp hạng, lọc) | `difficulty_irt`, `discrimination`, `correct_rate`, `times_used`, `ai_quality_score` |
| `dense_vector` 1536-dim | `embedding` (HNSW, cosine similarity) |
| `date` | `created_at`, `updated_at` |

**Analyzer `vi_analyzer`:** `standard tokenizer` + `lowercase` + Vietnamese stopwords.

> **Lưu ý:** Field `embedding` yêu cầu vector 1536 chiều (chuẩn OpenAI text-embedding-3-large). Seed data **không chứa** embedding; AI worker sẽ gọi embedding API và cập nhật sau. Nếu muốn test KNN ở local, có thể dùng model nhỏ hơn như `sentence-transformers/all-MiniLM-L6-v2` (384-dim) và chỉnh `dims` tương ứng trong `schema.json`.

---

## Cách 1: Docker

```bash
docker compose up -d elasticsearch

# Đợi ~30s cho ES khởi động xong, kiểm tra
curl http://localhost:9200/_cluster/health?pretty

# Tạo index
curl -X PUT "http://localhost:9200/question_search" \
  -H "Content-Type: application/json" \
  -d @schema.json

# Seed bulk
curl -X POST "http://localhost:9200/_bulk" \
  -H "Content-Type: application/x-ndjson" \
  --data-binary @seed.ndjson

# Tạo index RAG corpus (dùng bởi AI Service)
curl -X PUT "http://localhost:9200/rag_corpus" \
  -H "Content-Type: application/json" \
  -d @schema_rag_corpus.json

# Seed RAG corpus
curl -X POST "http://localhost:9200/_bulk" \
  -H "Content-Type: application/x-ndjson" \
  --data-binary @seed_rag_corpus.ndjson

# Kiểm tra
curl "http://localhost:9200/question_search/_count?pretty"
curl "http://localhost:9200/rag_corpus/_count?pretty"
```

## Cách 2: Native Windows

### Bước 1 - Download
- https://www.elastic.co/downloads/elasticsearch (Windows ZIP)
- Giải nén và chạy `bin\elasticsearch.bat`

### Bước 2 - Tắt security (dev only)
Sửa `config/elasticsearch.yml`:
```yaml
xpack.security.enabled: false
discovery.type: single-node
```

### Bước 3 - Index & Seed
Dùng curl như trên. Nếu Windows thiếu curl:

```powershell
# PowerShell
Invoke-WebRequest -Uri "http://localhost:9200/question_search" `
  -Method PUT -ContentType "application/json" `
  -InFile "schema.json"

Invoke-WebRequest -Uri "http://localhost:9200/_bulk" `
  -Method POST -ContentType "application/x-ndjson" `
  -InFile "seed.ndjson"
```

---

## GUI Client

- **Kibana** (đã có trong docker-compose): http://localhost:5601 → Dev Tools
- **Elasticvue** (browser extension, free)
- **Postman / Insomnia** (REST client)

## Query mẫu (chạy trong Kibana Dev Tools)

```json
# 1. Tìm kiếm full-text tiếng Việt
GET /question_search/_search
{
  "query": {
    "multi_match": {
      "query": "thuật toán sắp xếp",
      "fields": ["content_text^3", "topic^2", "explanation"]
    }
  }
}

# 2. Lọc theo tag + độ khó IRT
GET /question_search/_search
{
  "query": {
    "bool": {
      "must":   [{ "term": { "status": "active" } }],
      "filter": [
        { "terms": { "tags": ["sap_xep", "thuat_toan"] } },
        { "range": { "difficulty_irt": { "gte": 0, "lte": 1.5 } } }
      ]
    }
  }
}

# 3. KNN semantic search (cần có embedding)
# POST /question_search/_search
# {
#   "knn": {
#     "field": "embedding",
#     "query_vector": [0.01, 0.02, ..., 0.00],
#     "k": 10,
#     "num_candidates": 50
#   }
# }

# 4. Faceted search (aggregation)
GET /question_search/_search
{
  "size": 0,
  "aggs": {
    "by_type":  { "terms": { "field": "type" } },
    "by_bloom": { "terms": { "field": "bloom_level" } },
    "by_subject": { "terms": { "field": "subject_code" } }
  }
}
```

## Detection Duplicate (production)

Trước khi lưu câu hỏi mới:
1. Sinh embedding cho câu hỏi mới
2. Query KNN với `k=5`
3. Nếu `_score >= 0.92` → cảnh báo là có thể trùng

## Reindex khi thay đổi mapping

```bash
# Tạo index mới với mapping cập nhật
curl -X PUT http://localhost:9200/question_search_v2 -d @schema_v2.json

# Reindex
curl -X POST http://localhost:9200/_reindex -H 'Content-Type: application/json' -d '{
  "source": { "index": "question_search" },
  "dest":   { "index": "question_search_v2" }
}'

# Đổi alias
curl -X POST http://localhost:9200/_aliases -d '{
  "actions":[
    {"remove": {"index":"question_search","alias":"questions"}},
    {"add":    {"index":"question_search_v2","alias":"questions"}}
  ]
}'
```
