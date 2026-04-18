# MongoDB 7 - Setup Local

## Files

| File | Mô tả |
| ---- | ----- |
| `schema.js` | Tạo collections + JSON schema validator + indexes |
| `seed.js`   | Dữ liệu mẫu: 7 câu hỏi (đủ 7 loại), versions, reports, tags |

## Tổng hợp Schema

**6 collections:**

| Collection | Mô tả | Index chính |
| ---------- | ----- | ----------- |
| `questions` | Ngân hàng câu hỏi (11 loại) | `question_id` (unique), tags, IRT, text search |
| `question_versions` | Lịch sử chỉnh sửa | `(question_id, version)` unique |
| `question_reports` | Báo cáo câu hỏi có vấn đề | `status`, `reported_by` |
| `question_tags` | Master tag list cho autocomplete | `tag` unique |
| `question_imports` | Audit job import câu hỏi (CSV/XLSX/GIFT/QTI...) | `job_id` unique, `(org_id, status)` |
| `ai_prompts` | Prompt registry cho AI Service (versioned) | `(name, version)` unique, `(name, active)` |

**11 loại câu hỏi hỗ trợ:**
`multiple_choice_single`, `multiple_choice_multi`, `true_false`, `fill_blank`, `matching`, `ordering`, `short_answer`, `essay`, `code_execution`, `drag_drop`, `hotspot`

---

## Cách 1: Docker

```bash
docker compose up -d mongodb

# Kết nối
docker exec -it sq_mongodb mongosh "mongodb://root:root@localhost/smartquiz?authSource=admin"
```

`schema.js` + `seed.js` mount vào initdb → tự chạy lần đầu.

## Cách 2: Native Windows

### Bước 1 - Install

- Tải: https://www.mongodb.com/try/download/community
- Chọn **MongoDB 7.0 Community Server**
- Tick **"Install MongoDB as a Service"**
- Tải thêm **mongosh**: https://www.mongodb.com/try/download/shell

### Bước 2 - Chạy schema + seed

```bash
cd D:\SmartQuizSystem\database\mongodb

# Nếu không có auth (mặc định local):
mongosh "mongodb://localhost:27017/smartquiz" schema.js
mongosh "mongodb://localhost:27017/smartquiz" seed.js
```

### Bước 3 - Verify

```bash
mongosh "mongodb://localhost:27017/smartquiz" --eval "db.questions.countDocuments()"
mongosh "mongodb://localhost:27017/smartquiz" --eval "db.questions.findOne()"
```

---

## Chuỗi kết nối

```
# Docker (có auth)
mongodb://root:root@localhost:27017/smartquiz?authSource=admin

# Native (không auth)
mongodb://localhost:27017/smartquiz
```

## GUI Client

- **MongoDB Compass** (official, free)
- **Studio 3T** (mạnh, có trial)
- **NoSQLBooster**

## Truy vấn mẫu

```javascript
// Tất cả câu hỏi active
db.questions.find({ status: 'active' }).pretty();

// Tìm theo tag
db.questions.find({ 'metadata.tags': 'sap_xep' });

// Full-text search
db.questions.find({ $text: { $search: 'sắp xếp' } });

// Theo độ khó IRT
db.questions.find({ 'irt.b': { $gte: 0.5, $lte: 1.5 } });

// Theo loại
db.questions.aggregate([
    { $group: { _id: '$type', count: { $sum: 1 } } }
]);
```
