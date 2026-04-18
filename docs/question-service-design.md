# QUESTION SERVICE - THIẾT KẾ CHI TIẾT

**Hệ Thống Thi Trực Tuyến Thông Minh** | Version 1.0 | Tháng 4/2026

Tài liệu này mở rộng mục "Question Service" trong `design.md`, ở mức đủ để triển khai code production.

---

## I. TỔNG QUAN

### 1.1 Vai trò & phạm vi

Question Service là **nguồn sự thật duy nhất** cho ngân hàng câu hỏi. Mọi thao tác CRUD câu hỏi, tìm kiếm, import/export, và quản lý vòng đời đều đi qua đây. Exam Service không truy cập MongoDB trực tiếp — chỉ qua gRPC.

| Trách nhiệm | Không thuộc phạm vi |
| ----------- | ------------------- |
| CRUD ngân hàng câu hỏi (11 loại) | Sinh câu hỏi bằng AI (AI Service) |
| Quản lý phiên bản (version history) | Chấm điểm câu hỏi (Exam Service + AI Service) |
| Tìm kiếm: full-text, faceted, semantic (KNN) | Calibrate IRT params (Analytics batch) |
| Import/Export: CSV, Excel, GIFT, QTI | Lưu video/audio (Media Service) |
| Duplicate detection (embedding similarity) | Sinh embedding (AI Service qua Kafka) |
| Quality review workflow (draft → active) | Quản lý tag taxonomy master |
| Thống kê sử dụng (`times_used`, `correct_rate`) | Bài thi + lượt thi (Exam Service) |
| Subject/topic taxonomy | Auth & phân quyền (Auth Service) |
| Sync với Elasticsearch + Redis cache | |

### 1.2 Stack công nghệ

| Thành phần | Chọn | Lý do |
| ---------- | ---- | ----- |
| Runtime | Java 21 (LTS) + Spring Boot 3.2 | Thống nhất với Exam/Auth |
| ORM | Spring Data MongoDB (primary) + Spring Data JPA (PostgreSQL `subjects`) | Câu hỏi ở Mongo, taxonomy ở PG |
| Elasticsearch client | Spring Data Elasticsearch + `co.elastic.clients:elasticsearch-java` | Search text + KNN vector |
| Redis client | Lettuce | LRU cache + publish invalidation |
| Kafka | Spring Kafka | Consume AI-generated questions + publish update events |
| gRPC | grpc-java | Serve Exam Service (GetQuestion, BatchGet) |
| Import/export | Apache POI (Excel), OpenCSV, custom GIFT parser, QTI via xerces | |
| Validation | Jakarta Bean Validation + custom validators theo question type | |
| Observability | Micrometer + OpenTelemetry | |

### 1.3 Port & giao thức

| Giao thức | Port | Mục đích |
| --------- | ---- | -------- |
| HTTP REST | `3003` | CRUD, search, import/export |
| gRPC | `4003` | Internal — Exam Service gọi khi start attempt |
| Actuator | `9003` | Health + Prometheus |

### 1.4 Yêu cầu phi chức năng (NFR)

| Chỉ số | Mục tiêu |
| ------ | -------- |
| `GetQuestion` (gRPC, cache hit) p99 | < 10ms |
| `GetQuestion` (cache miss, Mongo) p99 | < 50ms |
| `BatchGetQuestions` (50 questions) p99 | < 100ms |
| Search full-text p99 | < 200ms |
| KNN semantic p99 | < 300ms |
| Read/Write ratio | 1000:1 (read-heavy) |
| Availability | 99.95% |

---

## II. KIẾN TRÚC BÊN TRONG

### 2.1 Sơ đồ lớp

```
┌──────────────────────────────────────────────────────────────┐
│  API Layer                                                   │
│  ─ QuestionController (REST)         ─ SearchController      │
│  ─ ImportExportController            ─ TaxonomyController    │
│  ─ QuestionReportController          ─ AdminQuestionCtrl     │
│  ─ QuestionGrpcService                                       │
├──────────────────────────────────────────────────────────────┤
│  Application Services                                        │
│  ─ CreateQuestionUseCase, UpdateQuestionUseCase              │
│  ─ PublishQuestionUseCase (draft→review→active)              │
│  ─ ImportQuestionsUseCase, ExportQuestionsUseCase            │
│  ─ SearchQuestionsUseCase (text, semantic, faceted)          │
│  ─ DetectDuplicateUseCase                                    │
│  ─ ReviewReportUseCase                                       │
├──────────────────────────────────────────────────────────────┤
│  Domain Layer                                                │
│  ─ Question (aggregate root)                                 │
│  ─ QuestionVersion (value object / collection)               │
│  ─ QuestionContent (text/rich/media/code/math)               │
│  ─ QuestionGradingConfig (polymorphic theo 11 type)          │
│  ─ IrtParams ─ QuestionStats                                 │
│  ─ QuestionStateMachine                                      │
│  ─ ValidationPolicy (per type)                               │
├──────────────────────────────────────────────────────────────┤
│  Infrastructure                                              │
│  ─ MongoQuestionRepo, MongoVersionRepo, MongoReportRepo      │
│  ─ PgSubjectRepo, PgTagRepo                                  │
│  ─ EsQuestionIndexer (sync Mongo → ES)                       │
│  ─ RedisQuestionCache                                        │
│  ─ KafkaEventPublisher, KafkaAiConsumer                      │
│  ─ GridFsMediaRef (ảnh nhúng nhỏ; lớn dùng S3)              │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 Cấu trúc module

```
question-service/
├── build.gradle.kts
├── api-grpc/
├── src/main/java/vn/smartquiz/question/
│   ├── QuestionServiceApplication.java
│   ├── config/                       # MongoConfig, EsConfig, RedisConfig, KafkaConfig
│   ├── web/                          # REST + DTOs (per type)
│   ├── grpc/
│   ├── application/
│   │   ├── crud/
│   │   ├── search/
│   │   ├── importexport/
│   │   └── review/
│   ├── domain/
│   │   ├── question/                 # Question, QuestionType, Content
│   │   ├── type/                     # McSingleQuestion, EssayQuestion... (polymorphic)
│   │   ├── version/
│   │   ├── tag/
│   │   └── policy/                   # ValidationPolicy, QualityPolicy
│   ├── infrastructure/
│   │   ├── mongo/
│   │   ├── pg/
│   │   ├── elasticsearch/
│   │   ├── redis/
│   │   ├── kafka/
│   │   └── parser/                   # GiftParser, QtiParser, CsvParser
│   └── common/
└── src/test/java/...
```

---

## III. DOMAIN MODEL

### 3.1 Aggregate root: `Question`

```java
public abstract class Question {
    protected QuestionId id;                    // UUID v4
    protected OrgId orgId;                      // NULL = shared across orgs
    protected SubjectCode subjectCode;
    protected UserId createdBy;
    protected UserId reviewedBy;
    protected QuestionStatus status;            // DRAFT | REVIEW | ACTIVE | DEPRECATED
    protected QuestionType type;
    protected int version;
    protected boolean aiGenerated;
    protected Integer aiQualityScore;           // 0-100, nullable
    protected List<String> aiQualityFlags;
    protected QuestionContent content;          // text, rich, media, code, math
    protected String explanation;
    protected String hint;
    protected List<String> referenceLinks;
    protected QuestionMetadata metadata;        // topic, subtopic, tags, bloom_level, language, estTime
    protected IrtParams irt;
    protected QuestionStats stats;
    protected EmbeddingRef embedding;           // ref sang AI Service, không lưu vector trực tiếp trong Mongo
    protected Instant createdAt;
    protected Instant updatedAt;
    protected Instant reviewedAt;

    // Domain operations
    public abstract GradingResult autoGrade(AnswerPayload payload);
    public abstract void validate();            // validate theo type
    public abstract AnswerPayloadSchema answerSchema();

    public void submitForReview() { stateMachine.transition(SUBMIT_REVIEW, this); }
    public void approve(UserId reviewer) { stateMachine.transition(APPROVE, this, reviewer); }
    public void reject(UserId reviewer, String reason) { ... }
    public void deprecate() { ... }
    public boolean isUsableInExam() { return status == ACTIVE; }
}
```

### 3.2 Polymorphic question types

Dùng **single table inheritance** trong Mongo (`type` là discriminator), mỗi type subclass override validation + grading:

```java
public class MultipleChoiceSingleQuestion extends Question {
    private List<Option> options;            // {id, text, isCorrect, explanation?}

    @Override
    public GradingResult autoGrade(AnswerPayload payload) {
        var selected = payload.array("selected_options");
        if (selected.size() != 1) return GradingResult.incorrect();
        var correctId = options.stream().filter(Option::isCorrect).findFirst().orElseThrow().id();
        return selected.get(0).equals(correctId)
            ? GradingResult.correct(points)
            : GradingResult.incorrect();
    }

    @Override
    public void validate() {
        assertNotEmpty(options, "options required");
        assertRange(options.size(), 2, 10, "options must be 2-10");
        long correctCount = options.stream().filter(Option::isCorrect).count();
        if (correctCount != 1) throw new ValidationException("exactly 1 correct option required");
    }
}
```

Tương tự cho 10 type còn lại (xem mục V).

### 3.3 Value objects

```java
public record QuestionMetadata(
    String topic,
    String subtopic,
    List<String> tags,
    BloomLevel bloom,             // KNOWLEDGE | COMPREHENSION | ... | EVALUATION
    String language,              // ISO-639-1
    int estimatedTimeSeconds
) { }

public record IrtParams(
    int difficultyAssigned,       // 1-5, giáo viên gán
    double b,                     // calibrated difficulty
    double a,                     // discrimination
    double c,                     // guessing
    boolean calibrated,
    Instant calibratedAt,
    int responsesCount
) { }

public record QuestionStats(
    int timesUsed,
    int correctCount,
    double correctRate,
    int avgTimeSeconds,
    double skipRate
) { }

public record EmbeddingRef(
    String model,                 // "text-embedding-3-large"
    Instant updatedAt,
    String esDocId                // reference Elasticsearch
) { }
```

### 3.4 Aggregate: `QuestionVersion`

Immutable snapshot của câu hỏi tại mỗi lần chỉnh sửa.

```java
public class QuestionVersion {
    private ObjectId id;
    private QuestionId questionId;
    private int version;
    private JsonNode contentSnapshot;   // toàn bộ Question serialized
    private UserId changedBy;
    private String changeReason;
    private Instant createdAt;
}
```

Quy tắc: **không cho xoá version**. Khi câu hỏi đã được dùng trong bài thi, Exam Service lưu `question_version` → sinh viên thi cũ vẫn thấy nội dung gốc.

### 3.5 Aggregate: `QuestionReport`

Báo cáo từ học sinh/giáo viên về câu hỏi có vấn đề.

---

## IV. STATE MACHINE

```
     ┌──────┐  submit_review   ┌────────┐  approve   ┌────────┐
     │draft ├─────────────────►│ review ├───────────►│ active │
     └──┬───┘                  └───┬────┘            └───┬────┘
        ▲                          │ reject              │ deprecate
        └──────────────────────────┘                     ▼
                                                    ┌────────────┐
                                                    │ deprecated │
                                                    └────────────┘
```

| Transition | Guard | Actor |
| ---------- | ----- | ----- |
| draft → review | validate() pass, có đủ content | creator (instructor) |
| review → active | reviewer ≠ creator (four-eyes nếu `ai_generated=true`) | reviewer (instructor/admin) |
| review → draft | có comment rejection | reviewer |
| active → deprecated | không còn dùng trong bài thi đang chạy | admin |
| deprecated → active | — | admin (rollback) |

**Automation:**
- AI-generated questions vào trạng thái `review` trực tiếp (bỏ qua draft)
- Khi `ai_quality_score < 70` hoặc có flag `factual_error`: chặn approve tự động, bắt buộc manual review

---

## V. QUESTION TYPE SPECIFICATIONS

### 5.1 Catalog đầy đủ 11 loại

| Type | Ý nghĩa | Độ phức tạp grading |
| ---- | ------- | ------------------- |
| `multiple_choice_single` | 1 đáp án đúng trong N lựa chọn | sync, trivial |
| `multiple_choice_multi` | Nhiều đáp án đúng, phải chọn đủ | sync, trivial |
| `true_false` | Đúng / Sai | sync, trivial |
| `fill_blank` | Điền vào ô trống, accept list/regex | sync |
| `matching` | Nối cặp trái-phải | sync |
| `ordering` | Sắp xếp đúng thứ tự | sync |
| `short_answer` | Trả lời ngắn, NLP similarity | sync nếu confidence ≥ 0.75, else manual |
| `essay` | Luận văn, chấm theo rubric | async (AI Service + manual review) |
| `code_execution` | Viết code, chạy test case | async (sandbox gVisor) |
| `drag_drop` | Kéo thả item vào zone | sync |
| `hotspot` | Click vào vùng trên ảnh | sync |

### 5.2 Schema chi tiết từng loại (phần type-specific fields)

#### 5.2.1 multiple_choice_single / multiple_choice_multi

```json
{
  "type": "multiple_choice_single",
  "options": [
    { "id": "opt_a", "text": "Bubble Sort",  "is_correct": false, "explanation": "O(n²) xấu nhất" },
    { "id": "opt_b", "text": "Merge Sort",   "is_correct": true,  "explanation": "Luôn O(n log n)" },
    ...
  ]
}
```

**Validation:**
- 2 ≤ `options.length` ≤ 10
- `multi`: ít nhất 1 option `is_correct=true`
- `single`: chính xác 1 option `is_correct=true`
- `option.id` unique trong câu hỏi

**Grading `multi`:**
- Default: strict — phải chọn đúng bộ đáp án đúng (all-or-nothing)
- Optional: partial credit theo `grading_config.partial_scoring = true` (mỗi đúng +1/N, mỗi sai -1/N, min 0)

#### 5.2.2 true_false

```json
{
  "type": "true_false",
  "options": [
    { "id": "true",  "text": "Đúng", "is_correct": true },
    { "id": "false", "text": "Sai",  "is_correct": false }
  ]
}
```

#### 5.2.3 fill_blank

```json
{
  "type": "fill_blank",
  "content": { "text": "Thuật toán ổn định O(n log n) là ______." },
  "grading_config": {
    "accepted_answers": ["Merge Sort", "MergeSort", "merge sort"],
    "use_regex": false,
    "case_sensitive": false,
    "trim_whitespace": true,
    "blanks_count": 1
  }
}
```

**Multi-blank:** `content.text` có nhiều `______` → `accepted_answers: [[...], [...]]` theo thứ tự blank.

#### 5.2.4 matching

```json
{
  "type": "matching",
  "pairs": [
    { "left_id": "l1", "left_text": "Merge Sort",  "right_id": "r1", "right_text": "O(n log n)" },
    { "left_id": "l2", "left_text": "Bubble Sort", "right_id": "r2", "right_text": "O(n²)" }
  ],
  "grading_config": { "partial_credit": true }
}
```

**Rendering:** client shuffle `right` column, server giữ mapping gốc.

#### 5.2.5 ordering

```json
{
  "type": "ordering",
  "items": [
    { "id": "i1", "text": "Đưa đỉnh xuất phát vào hàng đợi" },
    { "id": "i2", "text": "Lấy đỉnh đầu tiên ra" },
    { "id": "i3", "text": "Duyệt láng giềng" }
  ],
  "correct_order": ["i1", "i2", "i3"],
  "grading_config": { "strict": true }
}
```

**Grading:** strict = so sánh array hoàn toàn; non-strict = tính edit distance (Kendall tau).

#### 5.2.6 short_answer

```json
{
  "type": "short_answer",
  "grading_config": {
    "keywords": ["polymorphism", "inheritance", "override"],
    "min_similarity": 0.75,
    "max_words": 50,
    "case_sensitive": false,
    "sample_correct_answers": [
      "Polymorphism cho phép method cùng tên hoạt động khác nhau qua inheritance và override."
    ]
  }
}
```

**Grading logic:**
1. Tokenize + compute embedding answer (async pre-compute nếu < 200ms, else manual queue)
2. Cosine similarity với `sample_correct_answers` embeddings
3. Nếu max(similarity) ≥ `min_similarity` → correct
4. Nếu 0.5 ≤ similarity < 0.75 → `needs_manual_review=true`
5. Keyword coverage check (all keywords present) → bonus

#### 5.2.7 essay

```json
{
  "type": "essay",
  "grading_config": {
    "rubric_id": "UUID",
    "min_words": 150,
    "max_words": 500,
    "dimensions": [
      { "name": "content_accuracy", "max_points": 5, "description": "Nội dung chính xác" },
      { "name": "structure",        "max_points": 2 },
      { "name": "language_quality", "max_points": 3 }
    ],
    "ai_grading": {
      "enabled": true,
      "model": "gpt-4o",
      "confidence_threshold": 0.80,
      "require_human_review": true
    }
  }
}
```

**Grading flow:**
1. Exam Service publish `grading.request` Kafka
2. AI Service chấm từng dimension → tổng hợp score + confidence + explanation
3. Nếu `confidence < threshold` hoặc `require_human_review=true`: đưa vào queue giáo viên
4. Giáo viên xem AI suggestion + tự override → save

#### 5.2.8 code_execution

```json
{
  "type": "code_execution",
  "content": {
    "text": "Viết hàm Python `sum_of_list(nums)` trả về tổng.",
    "code_snippet": "def sum_of_list(nums):\n    # Your code here\n    pass"
  },
  "grading_config": {
    "language": "python",
    "runtime_version": "3.12",
    "time_limit_ms": 2000,
    "memory_limit_mb": 128,
    "allow_network": false,
    "test_cases": [
      { "id": "tc1", "input": "[1,2,3]",   "expected": "6",  "hidden": false, "points": 3 },
      { "id": "tc2", "input": "[]",         "expected": "0",  "hidden": false, "points": 2 },
      { "id": "tc3", "input": "[-1,-2,-3]", "expected": "-6", "hidden": true,  "points": 3 },
      { "id": "tc4", "input": "[10000]*1000", "expected": "10000000", "hidden": true, "points": 2 }
    ],
    "reference_solution": "def sum_of_list(nums): return sum(nums)"
  }
}
```

**Grading flow:**
1. Exam publish `grading.request` (code type)
2. Code Runner Service (Python/Go worker) thực thi trong sandbox gVisor/Firecracker
3. Mỗi test case: match exact hoặc fuzzy (configurable)
4. Return `{passed_tests: [...], failed_tests: [...], total_points, execution_details}`

#### 5.2.9 drag_drop

```json
{
  "type": "drag_drop",
  "zones": [
    { "id": "z1", "label": "OSI Layer 1", "max_items": 2 },
    { "id": "z2", "label": "OSI Layer 7", "max_items": 3 }
  ],
  "items": [
    { "id": "it1", "text": "Cable",       "correct_zone": "z1" },
    { "id": "it2", "text": "HTTP",        "correct_zone": "z2" },
    { "id": "it3", "text": "HTTPS",       "correct_zone": "z2" }
  ]
}
```

#### 5.2.10 hotspot

```json
{
  "type": "hotspot",
  "content": { "media": [{ "type": "image", "url": "s3://.../anatomy.png", "width": 800, "height": 600 }] },
  "hotspots": [
    { "id": "h1", "x": 400, "y": 250, "radius": 30, "is_correct": true,  "label": "Tim" },
    { "id": "h2", "x": 200, "y": 400, "radius": 30, "is_correct": false, "label": "Phổi" }
  ],
  "grading_config": { "allow_multiple_clicks": false }
}
```

**Grading:** check điểm click `(cx, cy)` có thuộc circle `(x, y, radius)` của hotspot nào và `is_correct=true`.

---

## VI. DATA MODEL

> **Mapping với schema thực tế:**
> - 6 collection Mongo (bao gồm `question_imports`) đã có trong `database/mongodb/schema.js`
> - Elasticsearch index `question_search` đã có trong `database/elasticsearch/schema.json`
> - PostgreSQL `subjects` đã có trong `database/postgresql/schema.sql`

### 6.1 MongoDB primary

| Collection | Mục đích | Index chính |
| ---------- | -------- | ----------- |
| `questions` | Câu hỏi chính (11 type polymorphic) | `question_id` unique, `(org_id, status, subject_code)`, `tags`, `(irt.b, irt.a)`, full-text |
| `question_versions` | Lịch sử chỉnh sửa | `(question_id, version)` unique desc |
| `question_reports` | Báo cáo lỗi | `(question_id, status)`, `status`, `reported_by` |
| `question_tags` | Master tag list | `tag` unique |
| `question_imports` | Lịch sử import (audit) | `imported_by`, `created_at` |

### 6.2 PostgreSQL (shared taxonomy — đã có trong database.md)

| Table | Ghi chú |
| ----- | ------- |
| `subjects` | Môn học / lĩnh vực, hierarchical với `parent_id` |

### 6.3 Elasticsearch `question_search` (đã có schema trong database.md)

Synced từ Mongo qua CDC (Debezium) hoặc event-driven (Kafka): khi câu hỏi `active` → indexed; khi `deprecated` → removed.

### 6.4 Redis cache

| Key | Kiểu | TTL | Khi nào invalidate |
| --- | ---- | --- | ------------------ |
| `question:{q_id}:{version}` | String JSON | 2 giờ | Khi câu hỏi update (publish event invalidation) |
| `question:{q_id}:latest` | String (pointer tới version) | 30 phút | Khi update |
| `q_search:{hash(query)}` | String JSON | 5 phút | Khi có câu mới `active` khớp filter |
| `q_subject:{code}:top_used` | List | 15 phút | Scheduled refresh |
| `q_tags:popular` | Sorted Set | 1 giờ | Scheduled |

---

## VII. API ENDPOINTS

> **Ghi chú về cột "Role":** Chỉ là **default role** có permission cần thiết. Enforcement thật dùng permission code như `question.create`, `question.approve`, `question.deprecate` (xem `auth-service-design.md` mục 3.4 hoặc `database/postgresql/seed.sql` `role_permissions`). Org có thể gán permission cho role tuỳ biến.

### 7.1 CRUD

| Method | Path | Body | Role |
| ------ | ---- | ---- | ---- |
| POST | `/questions` | `QuestionCreateDto` (polymorphic theo type) | instructor |
| GET | `/questions/{id}` | — | instructor (owner + org), admin |
| GET | `/questions/{id}/versions` | — | instructor |
| GET | `/questions/{id}/versions/{v}` | — | instructor |
| PATCH | `/questions/{id}` | partial update | instructor (owner) — auto bump version |
| DELETE | `/questions/{id}` | — | admin (soft: set `status=deprecated`) |

**Request body polymorphism:** Jackson dùng `@JsonTypeInfo(property="type")`:

```json
{
  "type": "multiple_choice_single",
  "content": { "text": "..." },
  "options": [...],
  "metadata": { "topic": "...", "tags": [...] }
}
```

### 7.2 Lifecycle

| Method | Path | Role |
| ------ | ---- | ---- |
| POST | `/questions/{id}/submit-review` | instructor (owner) |
| POST | `/questions/{id}/approve` | instructor ≠ owner, admin |
| POST | `/questions/{id}/reject` + `{reason}` | reviewer |
| POST | `/questions/{id}/deprecate` | admin |
| POST | `/questions/{id}/restore` | admin |

### 7.3 Search & filter

| Method | Path | Query params |
| ------ | ---- | ------------ |
| GET | `/questions/search` | `q=`, `subject=`, `tags=`, `type=`, `status=`, `bloom_level=`, `difficulty_range=`, `correct_rate_range=`, `lang=`, `page=`, `size=`, `sort=` |
| POST | `/questions/search/semantic` | `{query_text, k=10, min_similarity=0.7, filters}` |
| POST | `/questions/search/similar` | `{question_id, k=5}` — tìm câu tương tự |

**Response search:**
```json
{
  "total": 245,
  "page": 1,
  "size": 20,
  "aggregations": {
    "by_type":    [{ "type": "multiple_choice_single", "count": 120 }, ...],
    "by_bloom":   [{ "level": "analysis", "count": 88 }, ...],
    "by_subject": [{ "code": "CS101", "count": 95 }, ...]
  },
  "items": [ { "question_id": "...", "type": "...", "content": {...}, "_score": 4.2 }, ... ]
}
```

### 7.4 Bulk operations

| Method | Path | Body |
| ------ | ---- | ---- |
| POST | `/questions/batch` | `{ids: [...]}` — lấy nhiều câu cùng lúc |
| POST | `/questions/bulk-update` | `{ids: [...], patch: {...}}` |
| POST | `/questions/bulk-approve` | `{ids: [...]}` |

### 7.5 Import/Export

| Method | Path | Mô tả |
| ------ | ---- | ----- |
| POST | `/questions/import` (multipart) | Upload CSV/Excel/GIFT/QTI → async job |
| GET | `/questions/import/{jobId}` | Status import job |
| POST | `/questions/export` | Body: `{filters, format: csv|xlsx|gift|qti, include_answers}` → download URL |
| GET | `/questions/export/templates/{format}` | Download template file |

### 7.6 Quality & duplicate

| Method | Path |
| ------ | ---- |
| POST | `/questions/{id}/check-duplicates` → trả về câu tương tự ≥ 0.92 |
| POST | `/questions/{id}/request-ai-review` → publish Kafka event, AI Service chấm quality |
| GET | `/questions/{id}/quality-report` |

### 7.7 Reports (user flagging)

| Method | Path | Role |
| ------ | ---- | ---- |
| POST | `/questions/{id}/report` | authenticated |
| GET | `/admin/reports?status=pending` | admin |
| POST | `/admin/reports/{id}/resolve` | admin |

### 7.8 Stats

| Method | Path |
| ------ | ---- |
| GET | `/questions/{id}/stats` |
| GET | `/questions/stats/popular?subject=&limit=` |

### 7.9 Taxonomy

| Method | Path | Role |
| ------ | ---- | ---- |
| GET | `/subjects?org_id=&parent=` | authenticated |
| POST | `/subjects` | instructor, admin |
| PATCH | `/subjects/{id}` | admin |
| GET | `/tags?q=&limit=20` | authenticated (autocomplete) |
| POST | `/tags` | admin (manual add to master list) |

### 7.10 gRPC (internal — cho Exam Service)

```proto
service QuestionService {
    rpc GetQuestion(GetQuestionRequest) returns (Question);
    rpc BatchGetQuestions(BatchGetRequest) returns (BatchGetResponse);
    rpc GetQuestionForStudent(GetForStudentRequest) returns (StudentQuestion);  // Strip đáp án
    rpc IncrementUsageCount(UsageIncrement) returns (Empty);
    rpc StreamQuestionUpdates(Filter) returns (stream QuestionUpdate);
}
```

**StudentQuestion** bỏ `is_correct`, `explanation`, `correct_order`, `correct_zone`, `grading_config.accepted_answers`, `reference_solution`, `hidden test_cases`.

---

## VIII. SEARCH ARCHITECTURE

### 8.1 Hai đường tìm kiếm

**Đường 1: Full-text + faceted (Elasticsearch)**
```
GET /question_search/_search
{
  "query": {
    "bool": {
      "must":   [{ "multi_match": { "query": "thuật toán sắp xếp",
                                     "fields": ["content_text^3","topic^2","explanation"] } }],
      "filter": [
        { "term":  { "status": "active" } },
        { "term":  { "org_id": "..." } },
        { "terms": { "tags": ["sap_xep","thuat_toan"] } },
        { "range": { "difficulty_irt": { "gte": 0, "lte": 1.5 } } }
      ]
    }
  },
  "aggs": {
    "by_type":  { "terms": { "field": "type" } },
    "by_bloom": { "terms": { "field": "bloom_level" } }
  }
}
```

**Đường 2: Semantic KNN**
```
POST /question_search/_search
{
  "knn": {
    "field": "embedding",
    "query_vector": [0.01, ...],    // sinh bởi AI Service cho query text
    "k": 10,
    "num_candidates": 100
  }
}
```

### 8.2 Sync Mongo → Elasticsearch

Event-driven qua Kafka (không dùng CDC Debezium cho Mongo để giữ event thuộc domain, không phải raw oplog):

```
QuestionService app                Elasticsearch
       │                                  │
       │ CREATE/UPDATE/DELETE question    │
       │ ─> Mongo write                   │
       │                                  │
       ├─► Kafka publish:                 │
       │    - question.created            │
       │    - question.updated            │
       │    - question.deprecated         │
       │                                  │
       └─► QuestionIndexWorker consume    │
           ├─ Transform Question → ES doc │
           ├─ Strip sensitive fields      │
           └─► ES index/delete ───────────┘
```

**Embedding enrichment:** QuestionIndexWorker không tự sinh embedding (đó là việc AI Service). Flow:

```
question.created / updated với content.text thay đổi
        │
        ▼
AI Service EmbeddingWorker consume
        │
        ▼
Gọi OpenAI embedding API / local model
        │
        ▼
Publish question.embedding.ready {question_id, vector[1536]}
        │
        ▼
QuestionIndexWorker consume → update ES doc với embedding
```

### 8.3 Cache strategy

| Query pattern | Cache |
| ------------- | ----- |
| `GET /questions/{id}` | Redis `question:{id}:latest` 30m |
| `POST /questions/batch` | Per-id lookup cache, MGET |
| `GET /questions/search?q=...` | Redis key hash(query) 5m (chỉ nếu page=1, size=20, sort=default) |
| `GET /questions/search/semantic` | KHÔNG cache (user ý định mỗi lần khác) |

---

## IX. DUPLICATE DETECTION

### 9.1 Khi nào chạy

1. Khi tạo câu hỏi mới → trigger auto
2. Khi AI sinh câu hỏi → trigger bắt buộc trước khi approve
3. Manual qua endpoint `/questions/{id}/check-duplicates`

### 9.2 Flow

```
Question created / updated (status = draft / review)
         │
         ▼
Publish Kafka: question.check_duplicate.requested
         │
         ▼
DuplicateDetectionWorker consume
         │
         ├─► AI Service ensure embedding ready
         │
         ├─► ES KNN query với k=5, same org
         │
         ├─► Filter similarity >= 0.92 → DUPLICATE
         │    0.85 <= sim < 0.92      → SIMILAR (warning)
         │    sim < 0.85              → UNIQUE
         │
         └─► Publish question.duplicate.detected với kết quả
             │
             ▼
             QuestionService update aiQualityFlags + notification instructor
```

### 9.3 Ngưỡng similarity (tunable)

| Ngưỡng | Hành động |
| ------ | --------- |
| ≥ 0.95 | Chặn publish, yêu cầu giáo viên xác nhận |
| 0.92 - 0.95 | Cảnh báo rõ, cho phép publish nếu acknowledge |
| 0.85 - 0.92 | Hiển thị gợi ý "câu hỏi tương tự" |
| < 0.85 | Không action |

---

## X. IMPORT / EXPORT

### 10.1 Format hỗ trợ

| Format | Use case | Library |
| ------ | -------- | ------- |
| **CSV** | Simple import từ Excel | OpenCSV |
| **XLSX** | Template chuẩn với nhiều sheet (1 sheet = 1 type) | Apache POI |
| **GIFT** | Moodle-style text format | Custom ANTLR parser |
| **QTI 2.1/3.0** | IMS standard, interop với LMS khác | XML + xerces |
| **JSON** | Full fidelity, backup/restore | Jackson |
| **MoodleXML** | Migrate từ Moodle | XSL transform + custom |

### 10.2 Import pipeline (async)

```
POST /questions/import (file)
         │
         ▼
Save file → S3 (temp bucket, TTL 7 days)
         │
         ▼
Create job `question_imports` record status=pending
         │
         ▼
Publish Kafka question.import.requested
         │
         ▼
Trả về client {job_id, status_url}
         │
         ▼
ImportWorker consume
         │
         ├─► Parse file theo format
         ├─► Validate từng câu (per type)
         ├─► Duplicate detection (optional)
         ├─► Insert Mongo với status=draft
         ├─► Publish question.created per question
         └─► Update job.status=completed với {total, success, failed, errors[]}
         │
         ▼
Notification qua email hoặc WS
```

**Chunking:** file > 1000 câu → chia chunk 200/lần, process parallel với bounded parallelism (4 worker).

### 10.3 Export

```
POST /questions/export { filters, format }
         │
         ▼
Tạo job → Worker → Query Mongo → Generate file → Upload S3 → Presigned URL (TTL 24h)
         │
         ▼
Trả về {download_url}
```

---

## XI. EVENTS (KAFKA)

### 11.1 Produced

| Topic | Key | Payload | Consumer |
| ----- | --- | ------- | -------- |
| `question.created` | question_id | `{id, org_id, type, status, created_by}` | ES indexer, AI embedding worker |
| `question.updated` | question_id | `{id, version, changed_fields[], content_changed: bool}` | ES indexer, AI (nếu content changed), Cache invalidator |
| `question.deprecated` | question_id | `{id}` | ES indexer (remove), Cache invalidator |
| `question.published` (status→active) | question_id | `{id, type, reviewed_by}` | ES indexer, Audit |
| `question.check_duplicate.requested` | question_id | `{id, org_id}` | DuplicateDetectionWorker |
| `question.import.requested` | job_id | `{job_id, org_id, file_s3_key, format}` | ImportWorker |

### 11.2 Consumed

| Topic | Group | Hành động |
| ----- | ----- | --------- |
| `ai.question.generated` | question-service-ai-gen | Tạo draft Q từ payload AI, status=review |
| `ai.embedding.ready` | question-service-embed | Update embedding ref trong Mongo + trigger ES reindex |
| `ai.quality.scored` | question-service-quality | Update `ai_quality_score`, `ai_quality_flags` |
| `exam.attempt.answer_submitted` | question-service-stats | Increment `stats.times_used`, update `correct_rate` (batch mỗi 30s) |
| `analytics.irt.calibrated` | question-service-irt | Update `irt.b`, `irt.a`, `irt.c` |

### 11.3 Stats update chiến lược

Thay vì update `stats` đồng bộ mỗi answer (quá nặng):
- Consume `exam.attempt.answer_submitted` vào buffer in-memory
- Mỗi 30s flush: group by question_id, bulk update `$inc: {times_used, correct_count}` + recompute `correct_rate`
- Tradeoff: stats trễ 30s — chấp nhận được cho dashboard

---

## XII. QUALITY CONTROL (AI + Human)

### 12.1 AI-generated question lifecycle

```
AI Service sinh câu hỏi
        │
        ▼
Publish ai.question.generated {draft_id, content, type, ...}
        │
        ▼
Question Service consume → Mongo INSERT
    - status = 'review'  (skip draft)
    - ai_generated = true
    - version = 1
        │
        ▼
Trigger 2 side jobs song song:
    ├─ Quality check AI (Kafka ai.quality.request)
    └─ Duplicate detection (ES KNN)
        │
        ▼
Sau khi có kết quả:
    - Nếu ai_quality_score < 70 → flag cần manual review kỹ
    - Nếu duplicate >= 0.92 → flag "possibly duplicate"
    - Notification giáo viên review
        │
        ▼
Giáo viên manual review
    ├─ Approve → status = 'active'
    ├─ Edit → keep status='review', version++
    └─ Reject → status = 'deprecated' + discard
```

### 12.2 Quality flags (`ai_quality_flags`)

| Flag | Ý nghĩa | Trigger |
| ---- | ------- | ------- |
| `factual_error` | AI suspect sai sự thật | AI quality check |
| `ambiguous` | Câu hỏi nhiều nghĩa | AI |
| `multiple_correct` | MC có > 1 đáp án đúng ngữ nghĩa | AI |
| `grammar_error` | Sai ngữ pháp | AI |
| `inappropriate_content` | Nội dung không phù hợp | AI moderation |
| `possibly_duplicate` | Gần giống câu có sẵn | DuplicateDetection |
| `low_discrimination` | IRT `a` < 0.3 sau calibration | Analytics batch |
| `too_easy` / `too_hard` | Correct rate > 0.95 / < 0.05 | Stats analyzer |

### 12.3 Human review UI requirements (informational)

Giáo viên cần thấy:
- Nội dung câu hỏi + đáp án + explanation
- AI quality score + flags
- Similar questions (top 3 if similarity ≥ 0.85)
- Stats nếu đã dùng (usage, correct_rate)
- Action: Approve / Edit / Reject với comment

---

## XIII. INTEGRATION VỚI SERVICES KHÁC

| Service | Giao tiếp | Use case | Fallback |
| ------- | --------- | -------- | -------- |
| Auth Service | JWKS offline + gRPC | Verify JWT + get user info | Reject 401 |
| Exam Service | gRPC in-bound | Exam gọi `BatchGetQuestions` khi start attempt | N/A (inbound) |
| AI Service | Kafka async | Sinh câu hỏi, embedding, quality check | Questions không có embedding, search degrade về text-only |
| Analytics | Kafka consume IRT calibration | Update irt params | Dùng giá trị khởi tạo từ giáo viên |
| Notification | Kafka produce | Email khi import xong, review notification | Không critical |
| Media Service | REST presigned URL | Serve ảnh/video trong câu hỏi | Broken image icon |
| Elasticsearch | HTTP | Search | Fallback Mongo text search (degraded) |

### 13.1 gRPC client timeout + circuit breaker

| Call | Timeout | CB threshold |
| ---- | ------- | ------------ |
| AuthService.ValidateToken | 200ms | 5 fail/10s → open 30s |
| Exam → Question.BatchGet | 500ms | 10 fail/30s → open 60s |

---

## XIV. SECURITY

### 14.1 RBAC matrix (permission-based)

Bảng dưới show default grant cho 4 system roles. Enforcement qua permission code, không hardcode role name.

| Action | Permission code | student | instructor | admin | platform |
| ------ | --------------- | ------- | ---------- | ----- | -------- |
| View active questions (via exam only) | (stripped via Jackson View) | ✔ | — | — | — |
| List org bank | `question.read.org` | ✖ | ✔ | ✔ | ✔ |
| Create | `question.create` | ✖ | ✔ | ✔ | ✔ |
| Edit own | `question.update.own` | ✖ | ✔ | ✔ | ✔ |
| Edit others in org | `question.update.any` | ✖ | ✖ | ✔ | ✔ |
| Approve review | `question.approve` | ✖ | ✔ (not own if `ai_generated`) | ✔ | ✔ |
| Deprecate | `question.deprecate` | ✖ | ✖ | ✔ | ✔ |
| Import | `question.import` | ✖ | ✔ | ✔ | ✔ |
| Export | `question.export` | ✖ | ✔ | ✔ | ✔ |
| Report | `question.report` | ✔ | ✔ | ✔ | ✔ |
| Delete permanent | (chỉ platform via DB) | ✖ | ✖ | ✖ | ✔ |

```java
@PreAuthorize("hasAuthority('question.update.own') and @qPolicy.isOwner(authentication, #id)")
@PatchMapping("/questions/{id}")
public void updateQuestion(@PathVariable UUID id, ...) { ... }
```

### 14.2 Data isolation

Mongo query mặc định filter `org_id ∈ {user.org_id, null}`:

```java
public List<Question> search(Query q, UserContext ctx) {
    var filter = Filters.and(
        Filters.or(Filters.eq("org_id", ctx.orgId()), Filters.eq("org_id", null)),
        ... // các filter khác
    );
    return mongo.find(filter);
}
```

Middleware `@QuestionAccessFilter` interceptor add filter tự động.

### 14.3 Strip sensitive fields cho student

Jackson View:
```java
public class QuestionViews {
    public static class Student {}
    public static class Instructor extends Student {}
    public static class Owner extends Instructor {}
}

@JsonView(Views.Student.class)
public class Option {
    @JsonView(Views.Student.class) String id, text;
    @JsonView(Views.Instructor.class) boolean isCorrect;
    @JsonView(Views.Owner.class) String explanation;
}
```

`GetQuestionForStudent` gRPC tự động dùng `Student` view.

---

## XV. OBSERVABILITY

### 15.1 Metrics

| Metric | Type | Label |
| ------ | ---- | ----- |
| `question_crud_total` | counter | `op=create\|update\|delete`, `type` |
| `question_get_duration_seconds` | histogram | `source=cache\|mongo` |
| `question_search_duration_seconds` | histogram | `kind=text\|semantic\|faceted` |
| `question_cache_hit_ratio` | gauge | — |
| `question_grpc_batch_size` | histogram | — |
| `question_import_jobs_total` | counter | `status=success\|failed`, `format` |
| `question_ai_generated_total` | counter | `status=approved\|rejected` |
| `question_duplicate_detected_total` | counter | — |
| `question_status_transitions_total` | counter | `from`, `to` |
| `question_es_sync_lag_seconds` | gauge | — |

### 15.2 SLO

| SLI | Target |
| --- | ------ |
| `gRPC GetQuestion` p99 | < 50ms |
| `Search text` p99 | < 200ms |
| Cache hit rate | > 90% |
| ES sync lag | < 5s p99 |
| Availability | 99.95% |

### 15.3 Alerts

| Alert | Điều kiện | Severity |
| ----- | --------- | -------- |
| `CacheHitRateLow` | < 70% trong 10 phút | WARNING |
| `EsIndexingLag` | lag > 30s p99 trong 5 phút | WARNING |
| `ImportJobStuck` | có job pending > 1 giờ | WARNING |
| `QuestionServiceDown` | up == 0 / 2 phút | CRITICAL |
| `SearchLatencyHigh` | p99 > 500ms trong 5 phút | WARNING |

---

## XVI. PERFORMANCE

### 16.1 Profile tải

Kỳ thi đỉnh điểm: 10k học sinh start exam đồng thời, mỗi exam 30 câu hỏi.

| Operation | QPS tính | Chiến lược |
| --------- | -------- | ---------- |
| `BatchGetQuestions` (50 câu/exam) | 10k start/phút = 167/s × batch 50 | Cache Redis; warm khi exam publish |
| `GetQuestion` (adaptive next) | Sau mỗi câu trả lời = ~100/s | Cache per-question hit 95% |
| Search (instructor) | 50/s | Rate limit + ES cluster |
| CRUD | 5/s | Trivial |

### 16.2 Cache warming

Khi giáo viên `POST /exams/{id}/publish`:
- Exam Service publish `exam.published` event
- Question Service QuestionCacheWarmer consume → preload tất cả question_ref_id của bài thi vào Redis với TTL = duration + 1 ngày

### 16.3 Scaling

- Stateless → HPA theo CPU/QPS
- MongoDB: read-heavy → secondary read preference (staleness ≤ 10s OK)
- Elasticsearch: 3 node min, scale shards khi index > 500k câu

---

## XVII. ERROR HANDLING

| Code | HTTP | Ý nghĩa |
| ---- | ---- | ------- |
| `QUESTION_NOT_FOUND` | 404 | |
| `QUESTION_ACCESS_DENIED` | 403 | Khác org |
| `QUESTION_VALIDATION_FAILED` | 422 | Theo từng type |
| `QUESTION_INVALID_STATE_TRANSITION` | 409 | E.g. approve 1 câu đang draft |
| `QUESTION_DUPLICATE_BLOCKED` | 409 | Similarity ≥ 0.95 |
| `QUESTION_IN_USE` | 409 | Cố delete khi đang dùng trong exam chưa completed |
| `IMPORT_FORMAT_INVALID` | 400 | File không parse được |
| `IMPORT_TOO_LARGE` | 413 | > 10MB hoặc > 10k câu |
| `SEARCH_QUERY_TOO_SHORT` | 400 | < 2 ký tự |

---

## XVIII. DEPLOYMENT

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: question-service, namespace: smartquiz }
spec:
  replicas: 3
  template:
    spec:
      containers:
        - name: question
          image: registry.smartquiz.vn/question-service:1.0.0
          ports:
            - { name: http, containerPort: 3003 }
            - { name: grpc, containerPort: 4003 }
            - { name: mgmt, containerPort: 9003 }
          resources:
            requests: { cpu: 500m, memory: 512Mi }
            limits:   { cpu: 2,    memory: 1Gi }
          env:
            - { name: MONGODB_URI,      valueFrom: { secretKeyRef: { name: mongo-cred,  key: uri  } } }
            - { name: ES_URL,           value: http://elasticsearch:9200 }
            - { name: REDIS_URL,        value: redis://redis:6379 }
            - { name: KAFKA_BROKERS,    value: kafka-0:9092,kafka-1:9092 }
```

HPA theo CPU 70% + custom metric `question_cache_hit_ratio < 0.7` trigger scale out (thêm instance → chia cache miss).

---

## XIX. TESTING

### 19.1 Unit — grading per type

```java
@Test
void fillBlank_regex_match_case_insensitive() {
    var q = new FillBlankQuestion(GradingConfig.regex("(?i)merge\\s+sort", false, false));
    var payload = AnswerPayload.of("{\"text\":\"Merge Sort\"}");
    assertThat(q.autoGrade(payload).correct()).isTrue();
}

@Test
void mcMulti_partialCredit_correct_fraction() {
    var q = new McMultiQuestion(opts("A+","B+","C","D"), partialCredit=true, points=10);
    var payload = AnswerPayload.of("{\"selected_options\":[\"A\",\"C\"]}");  // 1 đúng, 1 sai
    var result = q.autoGrade(payload);
    assertThat(result.earnedPercent()).isEqualTo(0.0);   // strict policy: sai là 0
}
```

### 19.2 Integration

- Testcontainers: Mongo + Redis + ES + Kafka
- Flow: create question → publish event → verify ES doc có mặt trong 5s
- Import 1000 câu GIFT file → verify tất cả valid + error report đúng cho invalid rows

### 19.3 Contract test

Dùng Spring Cloud Contract để đảm bảo gRPC API không break Exam Service.

### 19.4 Load test

```js
// k6: 1000 rps mixed (80% GetQuestion cache hit, 15% Search, 5% Create)
```

---

## XX. ROADMAP

### 20.1 MVP (Q2/2026)

- [x] MongoDB schema
- [ ] CRUD 11 types với validation
- [ ] gRPC GetQuestion / BatchGet cho Exam
- [ ] Basic search text (ES)
- [ ] Cache Redis + event invalidation
- [ ] Version history

### 20.2 Phase 2 (Q3/2026)

- [ ] Semantic search KNN
- [ ] AI-generated question workflow + quality check
- [ ] Duplicate detection
- [ ] Import CSV/Excel/GIFT
- [ ] Question reports flow

### 20.3 Phase 3 (Q4/2026)

- [ ] QTI 2.1 import/export
- [ ] Multi-language question (auto-translate UI)
- [ ] A/B test framework
- [ ] DIF (Differential Item Functioning) detection
- [ ] Question templates (parameterized questions)

### 20.4 Open questions

1. **Câu hỏi đã dùng trong exam có được edit không?** → Được edit nhưng **phải bump version**, exam cũ giữ nguyên snapshot qua `exam_questions.question_version`. Nếu edit đáp án của câu đang được làm → **cấm**.
2. **Chia sẻ câu hỏi giữa các org (marketplace)?** → Phase 3. Cơ chế: `org_id=null` + `is_public=true`, audit access.
3. **Version rollback?** → Có. Endpoint `POST /questions/{id}/rollback/{version}` tạo version mới với content của version cũ.
4. **Parameterized questions (Toán: random số)?** → Phase 3. Thêm `template_params` + compute answer dynamic tại render time.
5. **Lưu vector trong Mongo hay chỉ ES?** → Chỉ ES (Mongo không tối ưu vector, lưu 2 chỗ waste disk). Mongo giữ `EmbeddingRef` để track metadata.
6. **Cold cache khi deploy mới?** → Pre-warm script chạy sau khi pod ready: load top 10k most-used questions vào Redis.

---

_Tài liệu thuộc Hệ Thống Thi Trực Tuyến Thông Minh — Question Service Design v1.0 — Tháng 4/2026._
