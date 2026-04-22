# Core Service — Design (DATN)

> **Port**: 8102 · **Ngôn ngữ**: Java 21 + Spring Boot 3.3 · **DB schema**: `core`
> Gộp các trách nhiệm Exam + Question + Analytics của docs production, cộng
> với orchestration AI (§9 scope-datn.md) và document upload.

## 1. Trách nhiệm

**Có**:
- CRUD câu hỏi (MCQ, TF, Essay) với content JSONB + embedding vector.
- CRUD đề thi, gán đề cho học sinh, publish/archive.
- Lifecycle attempt: start → in-progress → submit → graded, với `state_version` fencing.
- Lưu đáp án qua **outbox pattern** (RPO ≤30s).
- Chấm MCQ/TF đồng bộ (so sánh `correct_answer`).
- Chấm essay async: publish `grading.request.v1`, consume `grading.result.v1`.
- Orchestrate AI tutor: publish `tutor.explanation.request.v1` sau submit, consume result.
- Upload document (PDF/DOCX), extract text bằng Apache Tika, lưu vào `documents`.
- Orchestrate sinh câu hỏi từ document: publish `question.generation.request.v1`, consume result, dedupe pgvector.
- WebSocket phiên thi: heartbeat, remaining time, push cheat alert, push result-ready.
- Analytics view (PG view) cho teacher dashboard.

**Không có**:
- Xác thực user (Auth service).
- Thuật toán detect gian lận (Proctoring service).
- Gọi LLM trực tiếp (AI service).
- Phân tích OLAP phức tạp, IRT, DIF (future work).

## 2. Entity & DB

Xem `docs/database.md` §4 cho DDL đầy đủ. Nhóm bảng chính:

```
Exam domain     : exams, exam_questions, exam_assignments
Question domain : questions (+embedding), documents, question_generation_jobs
Attempt domain  : exam_attempts (+state_version), attempt_answers (+ai_* fields)
AI domain       : student_writing_profiles, ai_cache
Infra           : outbox, processed_events
Analytics views : v_exam_stats, v_score_histogram, v_question_quality
```

## 3. REST API

Base URL: `http://localhost:8102/api/v1`. Mọi endpoint (trừ healthcheck) yêu cầu
`Authorization: Bearer <access_token>`, verify qua JWKS của Auth service (cache 1h).

### 3.1. Questions (`TEACHER`, `ADMIN`)

| Method | Path                         | Mô tả                                          |
| ------ | ---------------------------- | ---------------------------------------------- |
| GET    | `/questions`                 | List (query: `type`, `difficulty`, `search`, `page`, `size`) |
| GET    | `/questions/{id}`            | Chi tiết                                       |
| POST   | `/questions`                 | Tạo mới (teacher manual input)                 |
| PUT    | `/questions/{id}`            | Cập nhật (tăng `version`)                      |
| DELETE | `/questions/{id}`            | Soft delete (nếu chưa dùng trong exam publish) |
| POST   | `/questions/search-similar`  | Tìm câu hỏi tương tự theo embedding (demo dedupe) |

**Request POST /questions**:
```json
{
  "type": "MCQ_SINGLE",
  "difficulty": "MEDIUM",
  "content": {
    "stem": "Thuật toán sắp xếp nào có độ phức tạp O(n log n)?",
    "options": ["Bubble sort", "Quick sort", "Selection sort", "Insertion sort"],
    "correct_answer": [1]
  },
  "metadata": { "topic": "sorting", "tags": ["algorithm","complexity"] }
}
```

Server tự tính embedding của `content.stem` → lưu `embedding`.

### 3.2. Documents + AI generation (§9.1 scope-datn)

| Method | Path                                              | Mô tả                                              |
| ------ | ------------------------------------------------- | -------------------------------------------------- |
| POST   | `/documents/upload`                               | Multipart: upload PDF/DOCX. Giới hạn 20MB.          |
| GET    | `/documents/{id}`                                 | Metadata + status                                  |
| GET    | `/documents`                                      | List của user hiện tại                             |
| POST   | `/questions/generate`                             | Tạo job sinh câu hỏi (202 Accepted, trả job_id)    |
| GET    | `/questions/generate/{job_id}`                    | Polling status + preview list                      |
| POST   | `/questions/generate/{job_id}/commit`             | Chọn câu giữ lại, save vào question bank           |

**POST /documents/upload**:
```
Content-Type: multipart/form-data
Fields: file (required), title (optional)
```
Response 201:
```json
{ "id": "uuid", "filename": "...", "status": "EXTRACTING", "created_at": "..." }
```

Flow extract: async task (Spring `@Async`) — đọc file từ disk, dùng Apache Tika
`AutoDetectParser` → `text_content`, `page_count`. Update status → `READY` hoặc `FAILED`.

**POST /questions/generate**:
```json
{
  "document_id": "uuid",       // optional; nếu null → sinh theo topic thuần
  "topic": "thuật toán sắp xếp",
  "difficulty": "MEDIUM",
  "count": 10,
  "type": "MCQ_SINGLE"
}
```
Response 202:
```json
{ "job_id": "uuid", "status": "QUEUED" }
```

**GET /questions/generate/{job_id}**:
```json
{
  "job_id": "uuid",
  "status": "DONE",
  "progress": { "done": 10, "total": 10 },
  "questions": [
    { "temp_id": "t1", "type": "MCQ_SINGLE", "content": {...}, "is_duplicate": false },
    { "temp_id": "t2", "content": {...}, "is_duplicate": true, "duplicate_of": "existing-qid" }
  ]
}
```

**POST /questions/generate/{job_id}/commit**:
```json
{ "accept_temp_ids": ["t1", "t3", "t5"] }
```
→ INSERT questions thật vào bank, trả về list `{temp_id → question_id}`.

### 3.3. Exams (`TEACHER`, `ADMIN`)

| Method | Path                                         | Mô tả                               |
| ------ | -------------------------------------------- | ----------------------------------- |
| GET    | `/exams`                                     | List (filter: status, created_by)   |
| GET    | `/exams/{id}`                                | Chi tiết                            |
| POST   | `/exams`                                     | Tạo DRAFT                           |
| PUT    | `/exams/{id}`                                | Cập nhật khi DRAFT                  |
| POST   | `/exams/{id}/questions`                      | Gán câu hỏi (tạo `exam_questions` rows) |
| POST   | `/exams/{id}/publish`                        | DRAFT → PUBLISHED; snapshot câu hỏi |
| POST   | `/exams/{id}/archive`                        | → ARCHIVED                          |
| POST   | `/exams/{id}/assignments`                    | Gán student list                    |

### 3.4. Attempts (`STUDENT` cho own, `TEACHER` cho review/override)

| Method | Path                                                     | Mô tả                                            | Role         |
| ------ | -------------------------------------------------------- | ------------------------------------------------ | ------------ |
| GET    | `/me/exams`                                              | Exam được assign + status attempt của mình        | STUDENT      |
| POST   | `/exams/{id}/start`                                      | Tạo attempt (hoặc resume nếu `IN_PROGRESS`)      | STUDENT      |
| GET    | `/attempts/{id}`                                         | Snapshot + câu hỏi theo thứ tự                   | STUDENT(own) |
| POST   | `/attempts/{id}/answers`                                 | Lưu đáp án (idempotent theo `position`)          | STUDENT      |
| POST   | `/attempts/{id}/submit`                                  | Kết thúc                                         | STUDENT      |
| GET    | `/attempts/{id}/result`                                  | Điểm + từng câu + AI explanation                 | STUDENT(own), TEACHER |
| GET    | `/teacher/attempts/needing-review`                       | Danh sách essay có `ai_explanation_status=FAILED` hoặc `ai_detection_score >= 0.7` | TEACHER |
| PATCH  | `/attempts/{id}/answers/{position}/override`             | Teacher override AI score (ADR-008 safety net)   | TEACHER      |

**POST /exams/{id}/start**:
- Kiểm tra `open_at <= now <= close_at`, assignment tồn tại, chưa có attempt.
- Tạo `exam_attempts` (state=IN_PROGRESS, state_version=0, deadline_at = now + duration_min).
- Return `{attempt_id, ws_url, questions: [{position, question: snapshot}], deadline_at}`.
- Nếu exam có `shuffle_questions` → shuffle per-attempt deterministic (seed = attempt_id).

**POST /attempts/{id}/answers**:
```json
{ "position": 3, "answer_data": {"selected": [1]} }
```
- Fencing: `UPDATE attempt_answers SET ... WHERE attempt_id=$1 AND position=$2 AND attempt_status='IN_PROGRESS'`.
- Transaction:
  1. UPSERT attempt_answers.
  2. INSERT outbox `exam.answer.submitted.v1`.
- Response 204.

**POST /attempts/{id}/submit**:
- Fencing: `UPDATE exam_attempts SET status='SUBMITTED', state_version=state_version+1, submitted_at=now() WHERE id=$1 AND status='IN_PROGRESS' AND state_version=$expected`.
- Nếu 0 rows updated → return 409 (race với suspend).
- Chấm MCQ/TF đồng bộ trong cùng TX.
- Publish outbox:
  - `exam.attempt.submitted.v1`
  - `grading.request.v1` cho mỗi essay answer chưa có score (kèm `need_ai_detection=true`)
  - `grading.request.v1` với `mode=short_answer_semantic` cho SHORT_ANSWER nào rule-based fail (xem §8.5)
  - `tutor.explanation.request.v1` batch cho list wrong answer (MCQ/TF/SHORT_ANSWER đã biết sai)
- Response 200 với điểm tạm (MCQ/TF/SHORT_ANSWER đã pass rule) + status `AWAITING_AI`.

**GET /attempts/{id}/result**: cho xem sau khi `status = GRADED` (AI đã xong). Final score per answer = `COALESCE(teacher_override_score, score)`.

**PATCH /attempts/{id}/answers/{position}/override** (teacher safety net, ADR-008):
```json
{ "score": 8.5, "reason": "AI chấm quá thấp, đáp án đúng ý nhưng viết ngắn" }
```
- Validate role TEACHER/ADMIN; teacher phải là creator của exam (ADMIN bỏ qua).
- UPDATE `teacher_override_score/reason/by/at`, set `graded_by='TEACHER'`.
- Recompute `exam_attempts.total_score = SUM(COALESCE(override_score, score))`.

### 3.5. Analytics (`TEACHER`)

| Method | Path                                 | Mô tả                                   |
| ------ | ------------------------------------ | --------------------------------------- |
| GET    | `/analytics/exams/{id}/stats`        | `v_exam_stats` + histogram              |
| GET    | `/analytics/questions/quality`       | `v_question_quality` (difficulty, pct_correct) |
| GET    | `/analytics/attempts/{id}/cheat`     | List cheat alerts (proxy sang Proctoring) |

## 4. WebSocket

Endpoint: `ws://localhost:8102/ws/attempts/{attempt_id}?token=<access_token>`.

**Protocol**: STOMP hoặc raw WebSocket + JSON frame. DATN scope dùng raw WS + Spring `WebSocketHandler` cho đơn giản.

**Client → Server message**:
```json
{ "type": "HEARTBEAT", "ts": "..." }
{ "type": "CHEAT_EVENT", "event_type": "TAB_BLUR", "data": { "duration_ms": 1200 } }
{ "type": "PING" }
```

**Server → Client message**:
```json
{ "type": "TIME_REMAINING", "seconds": 1340 }
{ "type": "CHEAT_WARNING", "severity": "HIGH", "rule": "EXCESSIVE_TAB_BLUR", "message": "..." }
{ "type": "RESULT_READY", "attempt_id": "..." }
{ "type": "EXPLANATION_READY", "position": 3 }
{ "type": "PONG" }
```

**Cheat event forward**: nhận `CHEAT_EVENT` → publish `cheat.event.raw.v1` Kafka (fire-and-forget, không blocking). Partition key = `attempt_id`.

**Heartbeat**: client gửi mỗi 10s. Server track lần cuối trong Redis `session:ws:{attempt_id}`. Nếu miss 3 beat → log WARN + publish `cheat.event.raw.v1` với `event_type=HEARTBEAT_LOST`.

**Timer authoritative**: server maintain `attempt:timer:{attempt_id}` trong Redis, push `TIME_REMAINING` mỗi 30s. Client hiển thị local countdown, nhưng mỗi 30s đồng bộ lại.

## 5. Kafka

### 5.1. Producer

| Topic                              | Khi nào publish                                                  |
| ---------------------------------- | ---------------------------------------------------------------- |
| `exam.answer.submitted.v1`         | Mỗi lần student submit 1 đáp án (via outbox)                     |
| `exam.attempt.submitted.v1`        | Khi attempt chuyển sang SUBMITTED (via outbox)                   |
| `grading.request.v1`               | Cho mỗi essay answer sau submit (via outbox)                     |
| `tutor.explanation.request.v1`     | Batch cho wrong answers sau submit (via outbox)                  |
| `question.generation.request.v1`   | Khi teacher tạo generation job (via outbox)                      |
| `cheat.event.raw.v1`               | WebSocket handler forward (direct publish, không qua outbox)     |

### 5.2. Consumer

| Topic                              | Xử lý                                                               |
| ---------------------------------- | ------------------------------------------------------------------- |
| `grading.result.v1`                | UPDATE attempt_answers.score + ai_detection_*; re-check attempt status → GRADED nếu tất cả đã chấm |
| `tutor.explanation.result.v1`      | UPDATE attempt_answers.ai_explanation + ai_explanation_status       |
| `question.generation.result.v1`    | INSERT questions mới + dedupe pgvector; UPDATE job status          |
| `cheat.alert.v1`                   | WS push `CHEAT_WARNING` cho teacher monitor + student              |

### 5.3. Outbox pattern

**Bảng**: `core.outbox` (xem `database.md` §4.5).

**Ghi**:
```java
@Transactional
public void submitAnswer(...) {
    attemptAnswerRepo.upsert(...);
    outboxRepo.insert(new OutboxEvent("exam.answer.submitted.v1", attemptId, payload));
}
```

**Relayer** (dedicated Spring component, chạy cùng process):
- Leader election qua Redis `SET lock:outbox:core ... NX EX 30`, renew mỗi 10s.
- Poll mỗi 5s: `SELECT ... FROM outbox WHERE published_at IS NULL ORDER BY id LIMIT 500 FOR UPDATE SKIP LOCKED`.
- Publish Kafka batch với `key=partition_key`, `headers={event_id, schema_version}`.
- `UPDATE outbox SET published_at=now() WHERE id IN (...)`.
- Nếu publish fail → `attempts++`, retry exponential backoff.

**Cleanup**: cron job xóa `outbox WHERE published_at < now - 7 days`.

### 5.4. Idempotent consumer

```java
@Transactional
public void handleGradingResult(GradingResultEvent event) {
    if (processedEventsRepo.existsById(event.eventId())) return;
    attemptAnswerRepo.applyGrading(event);
    processedEventsRepo.insert(event.eventId(), "grading.result.v1");
}
```

## 6. Architecture

```
┌─────────────────────────────────────────────────────────────┐
│ Controller layer                                             │
│  QuestionController · ExamController · AttemptController     │
│  DocumentController · GenerationController                   │
│  AnalyticsController · WebSocketHandler                      │
└──────────────────────┬──────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────┐
│ Application service                                          │
│  QuestionService · ExamService · AttemptService              │
│  GradingOrchestrator · TutorOrchestrator · GenerationOrch.   │
│  DocumentService · AnalyticsService                          │
└──────────────────────┬──────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────┐
│ Infra                                                        │
│  OutboxRelayer · KafkaConsumers · WebSocketSessionRegistry   │
│  JwksClient (cache 1h) · TikaExtractor · EmbeddingClient     │
└──────────────────────┬──────────────────────────────────────┘
                       │
┌──────────────────────▼──────────────────────────────────────┐
│ Repository (Spring Data JPA + JdbcTemplate cho pgvector)     │
└──────────────────────┬──────────────────────────────────────┘
                       ▼
              PostgreSQL (schema: core) + Redis + Kafka
```

**Dependencies chính**:
- `spring-boot-starter-web`, `-websocket`, `-data-jpa`, `-security`, `-validation`
- `spring-kafka`
- `spring-boot-starter-data-redis`
- `org.apache.tika:tika-core` + `tika-parsers-standard-package`
- `com.pgvector:pgvector` (JDBC type adapter)
- `io.jsonwebtoken` hoặc `nimbus-jose-jwt` (JWT verify)
- `flyway-core` + `flyway-database-postgresql`

**EmbeddingClient**: gọi AI service `POST /embed` (batch) để lấy vector 384d. Cache trong Redis `cache:embed:{sha256(text)}` TTL 1d.

## 7. State machine

### 7.1. Exam

```
DRAFT ──publish──► PUBLISHED ──archive──► ARCHIVED
  │                    ▲
  └── edit──► DRAFT    │
                       └── không quay lại DRAFT
```

### 7.2. Attempt

```
                  ┌─────── SUSPENDED (từ cheat alert)
                  │
IN_PROGRESS ──────┼──submit──► SUBMITTED ──AI done──► GRADED
                  │
                  └──deadline──► SUBMITTED (auto)
                  │
                  └──cancel──► CANCELLED
```

Mọi transition phải check + tăng `state_version` trong WHERE clause.

## 8. Golden-path pseudocode

### 8.1. Start attempt

```java
@Transactional
public AttemptView start(UUID examId, UUID studentId) {
    Exam exam = examRepo.findPublishedOrThrow(examId);
    assertAssigned(exam, studentId);
    assertWithinWindow(exam);
    if (attemptRepo.existsByExamIdAndStudentId(examId, studentId)) {
        ExamAttempt existing = attemptRepo.findByExamIdAndStudentId(examId, studentId);
        if (existing.status() == IN_PROGRESS) return resume(existing);
        throw new AlreadySubmittedException();
    }
    ExamAttempt a = new ExamAttempt(exam, studentId,
        now().plusMinutes(exam.durationMin()));
    attemptRepo.save(a);
    return buildInitialView(a);
}
```

### 8.2. Submit answer (outbox)

```java
@Transactional
public void submitAnswer(UUID attemptId, int position, JsonNode answerData) {
    ExamAttempt a = attemptRepo.findOrThrow(attemptId);
    if (a.status() != IN_PROGRESS) throw new InvalidStateException();
    attemptAnswerRepo.upsert(attemptId, position, answerData);
    outbox.enqueue("exam.answer.submitted.v1", attemptId.toString(),
        Map.of("attempt_id", attemptId, "position", position,
               "answer_data", answerData, "submitted_at", now()));
}
```

### 8.3. Submit attempt

```java
@Transactional
public SubmitResult submit(UUID attemptId, long expectedVersion) {
    int rows = attemptRepo.transition(attemptId, IN_PROGRESS, SUBMITTED, expectedVersion);
    if (rows == 0) throw new ConflictException();

    List<AttemptAnswer> answers = attemptAnswerRepo.findByAttempt(attemptId);
    gradeObjective(answers); // MCQ/TF/SHORT_ANSWER (rule-based, xem §8.5)

    outbox.enqueue("exam.attempt.submitted.v1", attemptId.toString(), ...);
    for (var a : answers) {
        if (a.question().type() == ESSAY) {
            outbox.enqueue("grading.request.v1", UUID.randomUUID().toString(),
                buildGradingRequest(a, /*needAiDetection=*/true));
        } else if (a.question().type() == SHORT_ANSWER && !a.isCorrect()) {
            // SHORT_ANSWER rule-based fail → gửi AI semantic fallback
            outbox.enqueue("grading.request.v1", UUID.randomUUID().toString(),
                buildSemanticShortAnswerRequest(a));
        }
        if (!a.isCorrect() && a.question().type() != ESSAY) {
            outbox.enqueue("tutor.explanation.request.v1", UUID.randomUUID().toString(),
                buildTutorRequest(a));
        }
    }
    return SubmitResult.of(answers);
}
```

### 8.4. Consume grading.result

```java
@KafkaListener(topics = "grading.result.v1")
@Transactional
public void onGradingResult(GradingResultEvent e) {
    if (processedEventsRepo.existsById(e.eventId())) return;
    attemptAnswerRepo.applyGrading(e.attemptId(), e.position(),
        e.score(), e.feedback(), e.aiDetection(),
        /*gradedBy=*/"AI", /*gradingProvider=*/e.provider());
    processedEventsRepo.insert(e.eventId(), "grading.result.v1");
    if (allAnswersGraded(e.attemptId())) {
        attemptRepo.transitionToGraded(e.attemptId());
        wsRegistry.push(e.attemptId(), new ResultReadyMessage());
    }
}
```

### 8.5. SHORT_ANSWER chấm 2-bước (ADR-008)

```java
// Bước 1 — rule-based (trong gradeObjective, chạy đồng bộ lúc submit)
private void gradeShortAnswer(AttemptAnswer a) {
    String userAns = normalize(a.answerData().text());   // lowercase, strip diacritic + punct
    var content = a.question().content();
    String correct = normalize(content.correctAnswer());
    List<String> variants = content.acceptedVariants().stream().map(this::normalize).toList();

    if (userAns.equals(correct) || variants.contains(userAns)) {
        a.setScore(a.question().points());               // exact match
        a.setGradedBy("RULE");
        a.setGradedAt(now());
        return;
    }
    // Fuzzy — Levenshtein ratio ≥ 0.9 hoặc distance ≤ 2
    double ratio = fuzzRatio(userAns, correct);
    int dist = levenshtein(userAns, correct);
    if (ratio >= 0.9 || dist <= 2) {
        a.setScore(a.question().points());               // fuzzy pass
        a.setFeedback("Fuzzy match (khoảng cách chỉnh sửa: " + dist + ")");
        a.setGradedBy("RULE");
        a.setGradedAt(now());
        return;
    }
    // Bước 2 — fail rule: để submit() publish grading.request.v1 mode=short_answer_semantic
    a.setScore(null);   // chưa chấm, AI sẽ chấm
}
```

Helper:
```java
private String normalize(String s) {
    if (s == null) return "";
    return Normalizer.normalize(s.trim().toLowerCase(), Form.NFD)
        .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")   // strip dấu
        .replaceAll("[\\p{Punct}\\s]+", "");                     // strip punct + whitespace
}
```

### 8.6. Teacher override

```java
@Transactional
public AttemptAnswer overrideScore(UUID attemptId, int position,
                                   BigDecimal score, String reason,
                                   UUID teacherId) {
    AttemptAnswer a = answerRepo.findByAttemptAndPosition(attemptId, position);
    authorizeTeacher(teacherId, a.attempt().exam());        // creator hoặc ADMIN
    a.setTeacherOverrideScore(score);
    a.setTeacherOverrideReason(reason);
    a.setTeacherOverrideBy(teacherId);
    a.setTeacherOverrideAt(now());
    // Recompute total_score
    BigDecimal total = answerRepo.sumFinalScore(attemptId);   // SUM(COALESCE(override, score))
    attemptRepo.updateTotalScore(attemptId, total);
    return a;
}
```

## 9. Test strategy

- **Unit**: grading MCQ/TF, outbox enqueue, state fencing, shuffle deterministic.
- **Integration (Testcontainers PG + Kafka + Redis)**:
  - Start→answer→submit→grading.result → GRADED.
  - Race test: submit concurrent với suspend (giả lập cheat.alert) → `state_version` chống lost state.
  - Outbox relayer: insert 100 row, relayer publish đúng thứ tự per partition.
  - Idempotent consumer: gửi 2 lần grading.result cùng event_id → chỉ apply 1 lần.
- **Contract**: OpenAPI validate response schema.

## 10. Cấu hình

```yaml
server.port: 8102

spring:
  datasource.url: jdbc:postgresql://localhost:5432/smartquiz?currentSchema=core
  datasource.username: core_app
  jpa.properties.hibernate.default_schema: core
  flyway: { schemas: core, default-schema: core }
  kafka:
    bootstrap-servers: localhost:9092
    consumer.group-id: core
    consumer.properties.isolation.level: read_committed
  data.redis: { host: localhost, port: 6379 }

smartquiz:
  auth:
    jwks-url: http://localhost:8101/.well-known/jwks.json
    audience: smartquiz
    jwks-cache-ttl: 1h
  ai-service.base-url: http://localhost:8103
  proctoring-service.base-url: http://localhost:8104
  outbox:
    poll-interval: 5s
    batch-size: 500
    cleanup-after: 7d
  upload:
    dir: ./data/uploads
    max-size: 20MB
    allowed-mime:
      - application/pdf
      - application/vnd.openxmlformats-officedocument.wordprocessingml.document
  attempt:
    timer-push-interval: 30s
    heartbeat-grace: 30s  # miss 3 beat → warn
```

## 11. Observability

- Log JSON với MDC: `requestId`, `userId`, `attemptId`.
- Metric:
  - `core_attempt_started_total`, `core_attempt_submitted_total`
  - `core_outbox_pending_gauge` (alert nếu > 1000)
  - `core_outbox_publish_latency_seconds` (p99)
  - `core_kafka_consumer_lag_seconds{topic=...}`
  - `core_ws_active_sessions_gauge`
- `/actuator/prometheus`.

## 12. Ranh giới

| Không bao giờ                                                 | Lý do                                  |
| ------------------------------------------------------------- | -------------------------------------- |
| Gọi LLM trực tiếp trong Core                                  | Tách concern, cost centralized ở AI    |
| Cài thuật toán cheat detect trong Core                         | Proctoring owner                        |
| Verify password / issue JWT                                    | Auth owner                              |
| Ghi trực tiếp vào `proctoring.*` schema                       | Cross-schema write cấm                  |
| Consume Kafka mà không dedupe `processed_events`              | Duplicate processing                    |
| Gọi ai-service không qua circuit breaker / timeout            | AI down sẽ đóng cả Core                  |
