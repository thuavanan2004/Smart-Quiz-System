# EXAM SERVICE - THIẾT KẾ CHI TIẾT

**Hệ Thống Thi Trực Tuyến Thông Minh** | Version 1.0 | Tháng 4/2026

Tài liệu này mở rộng mục "Exam Service" trong `design.md`, ở mức đủ để triển khai code production.

---

## I. TỔNG QUAN

### 1.1 Vai trò & phạm vi

Exam Service là **trái tim** của hệ thống — nơi học sinh thực sự làm bài và điểm thi được tạo ra. Tính đúng đắn, độ trễ thấp và không mất đáp án là **yêu cầu không thể thỏa hiệp**.

| Trách nhiệm | Không thuộc phạm vi |
| ----------- | ------------------- |
| Vòng đời bài thi (draft → archived) | Tạo nội dung câu hỏi (Question Service) |
| Vòng đời lượt thi (attempt) | Chấm AI essay / code execution (AI Service) |
| Quản lý session + đồng hồ authoritative | Phát hiện gian lận (Cheating Service — Exam chỉ consume) |
| Xáo câu hỏi, lưu thứ tự riêng mỗi lượt | Phân tích OLAP (Analytics Service) |
| Nộp đáp án idempotent | Gửi email kết quả (Notification Service) |
| Auto-grade câu hỏi tự chấm được | Xuất PDF / chứng chỉ (Certificate Service tương lai) |
| Fan-out chấm điểm bất đồng bộ | Giám thị video (Proctoring Service) |
| WebSocket đồng bộ realtime | Lưu video (Media Service) |
| Adaptive exam theo IRT | Tinh chỉnh IRT (Analytics batch) |

### 1.2 Stack công nghệ

| Thành phần | Chọn | Lý do |
| ---------- | ---- | ----- |
| Runtime | Java 21 (LTS) + Spring Boot 3.2 | Virtual Threads cho 5k+ WS/pod |
| WebSocket | Spring WebSocket (native) + STOMP | Plus Redis pub/sub để broadcast |
| ORM | Spring Data JPA + Hibernate 6 | PostgreSQL `exams`, `exam_attempts`, `attempt_answers` |
| Mongo client | Spring Data MongoDB | Đọc câu hỏi khi bắt đầu attempt (cache Redis) |
| Redis client | Lettuce + Redisson | Distributed lock, Sorted Set, Lua scripts |
| Kafka | Spring Kafka | Producer events + Consumer cheat alerts |
| gRPC | Proto-gen + grpc-java | Gọi Question Service, Auth Service |
| Rule engine | (Không cần) — logic nghiệp vụ code trực tiếp | Giữ đơn giản |
| Scheduler | Spring `@Scheduled` + ShedLock | Job expire attempt, archive exam |
| Observability | Micrometer + OpenTelemetry | Metrics, tracing |

### 1.3 Port & giao thức

| Giao thức | Port | Mục đích |
| --------- | ---- | -------- |
| HTTP REST | `3002` | CRUD, start/submit |
| WebSocket (STOMP over WS) | `3002/ws` | Realtime session (heartbeat, cheat alerts, timer sync) |
| gRPC | `4002` | Internal — Analytics gọi để export attempts, Admin export |
| Actuator | `9002` | Health + Prometheus |

### 1.4 Yêu cầu phi chức năng (NFR)

| Chỉ số | Mục tiêu | Ghi chú |
| ------ | -------- | ------- |
| Độ trễ `POST /answers` p99 | < 100ms | Hot path tuyệt đối |
| Độ trễ `POST /exams/{id}/start` p99 | < 300ms | Chọn Q + tạo session Redis |
| Độ trễ `POST /submit` p99 | < 500ms | Flush Redis → PG + publish Kafka |
| Throughput | 10k RPS trên cluster | Peak thi cao điểm |
| Đồng thời WS | 100.000 kết nối | 20 pod × 5k WS/pod |
| RPO đáp án | 0 (tuyệt đối không mất) | Redis AOF fsync=always + dual-write |
| RTO pod crash | < 10s | K8s reschedule, client auto-reconnect |
| Availability | 99.99% | Trong kỳ thi quan trọng |

---

## II. KIẾN TRÚC BÊN TRONG

### 2.1 Sơ đồ lớp

```
┌──────────────────────────────────────────────────────────────┐
│  API Layer                                                   │
│  ─ ExamController (REST)         ─ AttemptController (REST)  │
│  ─ ExamWebSocketHandler (STOMP)  ─ AdminExamController       │
│  ─ ExamGrpcService                                           │
├──────────────────────────────────────────────────────────────┤
│  Application Services (Use Cases)                            │
│  ─ CreateExamUseCase, PublishExamUseCase                     │
│  ─ StartAttemptUseCase, SubmitAnswerUseCase                  │
│  ─ SubmitAttemptUseCase, ResumeAttemptUseCase                │
│  ─ ExpireAttemptsJob, SuspendAttemptUseCase                  │
│  ─ NextAdaptiveQuestionUseCase                               │
├──────────────────────────────────────────────────────────────┤
│  Domain Layer                                                │
│  ─ Exam (aggregate root) ─ ExamAttempt (aggregate root)      │
│  ─ ExamStateMachine ─ AttemptStateMachine                    │
│  ─ GradingPolicy ─ ShufflePolicy ─ AccessPolicy              │
│  ─ AdaptiveSelector (IRT)                                    │
├──────────────────────────────────────────────────────────────┤
│  Infrastructure                                              │
│  ─ JPA repositories (PG)                                     │
│  ─ MongoQuestionFetcher (gRPC to Question Service)           │
│  ─ RedisSessionStore, RedisAnswerBuffer, RedisLockManager    │
│  ─ KafkaEventPublisher, KafkaCheatConsumer                   │
│  ─ AuthGrpcClient (ValidateToken)                            │
└──────────────────────────────────────────────────────────────┘
```

### 2.2 Cấu trúc module

```
exam-service/
├── build.gradle.kts
├── api-grpc/                       # .proto shared
├── src/main/java/vn/smartquiz/exam/
│   ├── ExamServiceApplication.java
│   ├── config/                     # SecurityConfig, WebSocketConfig, RedisConfig, KafkaConfig
│   ├── web/                        # REST controllers, DTOs
│   ├── websocket/                  # Handler, session registry
│   ├── grpc/
│   ├── application/
│   │   ├── exam/
│   │   └── attempt/
│   ├── domain/
│   │   ├── exam/                   # Exam, ExamStatus, ExamConfig
│   │   ├── attempt/                # ExamAttempt, AttemptStatus, Answer
│   │   ├── grading/                # AutoGrader, GradingResult
│   │   ├── adaptive/               # IrtModel, FisherInformation, ThetaEstimator
│   │   └── policy/                 # AccessPolicy, IpWhitelist, PasswordGate
│   ├── infrastructure/
│   │   ├── persistence/            # JPA
│   │   ├── redis/
│   │   ├── kafka/
│   │   ├── question/               # gRPC client
│   │   └── auth/
│   └── common/                     # Exception, ErrorCode
└── src/test/java/...
```

---

## III. DOMAIN MODEL

> **Mapping với schema thực tế:** Tất cả bảng Exam Service dùng đã có sẵn trong `database/postgresql/schema.sql`:
> - Exam group: `exams`, `exam_sections`, `exam_questions`, `exam_enrollments`
> - Attempt group: `exam_attempts`, `attempt_answers`
> - Grading & output: `grading_rubrics`, `attempt_feedback`, `certificates`, `proctoring_sessions`
> - Fact ClickHouse: `exam_facts`, `answer_analytics` (trong `database/clickhouse/schema.sql`)
> - Redis session keys: `session:*`, `answers:*`, `q_order:*`, `adaptive:*` (trong `database/redis/schema.md` Nhóm 1)

### 3.1 Aggregate: `Exam`

Root aggregate quản lý cấu hình bài thi.

```java
public class Exam {
    private ExamId id;
    private OrgId orgId;
    private UserId createdBy;
    private SubjectId subjectId;
    private String title;
    private ExamStatus status;
    private ExamType type;           // STANDARD | ADAPTIVE | PRACTICE | SURVEY
    private Duration duration;
    private int maxAttempts;
    private Score passingScore;
    private Score totalPoints;
    private ExamPolicy policy;       // shuffle, IP whitelist, password, proctoring level
    private TimeWindow window;       // starts_at, ends_at, grace_period
    private List<ExamSection> sections;
    private List<ExamQuestion> questions; // Section = null hoặc Section không bắt buộc
    private Instant publishedAt;
    // ...

    // Domain operations
    public void publish(Clock clock) { stateMachine.transition(PUBLISH, this, clock); }
    public void archive(Clock clock) { ... }
    public boolean isCurrentlyOpen(Clock clock) { ... }
    public boolean allowsUser(User user, Set<ExamEnrollmentId> enrolledIds) { ... }
    public int computeTotalPoints() { ... }
    public List<ExamQuestion> shuffledFor(UUID attemptSeed) { ... }
}
```

### 3.2 Aggregate: `ExamAttempt`

Root aggregate cho 1 lượt thi — **đơn vị concurrency**.

```java
public class ExamAttempt {
    private AttemptId id;
    private ExamId examId;
    private UserId userId;
    private short attemptNumber;
    private AttemptStatus status;
    private Instant startedAt;
    private Instant submittedAt;
    private Instant expiresAt;
    private Duration timeSpent;
    private Score rawScore;
    private Score maxScore;
    private BigDecimal percentageScore;
    private Boolean passed;
    private short riskScore;
    private boolean flaggedForReview;
    private InetAddress ipAddress;
    private String userAgent;
    private GeoInfo geo;
    private List<QuestionRefId> questionOrder;  // thứ tự đã xáo cho lượt thi này
    private short currentQuestionIndex;
    private Double adaptiveTheta;
    private Double adaptiveSe;
    private Map<QuestionRefId, Answer> answers;  // load lazy

    // Domain operations
    public void submitAnswer(QuestionRefId qid, AnswerPayload payload, UUID submissionId) { ... }
    public void submitFinal(Clock clock) { ... }
    public void suspendForCheating(String reason) { ... }
    public void expire(Clock clock) { ... }
    public Duration remainingTime(Clock clock) { ... }
    public boolean canAnswerQuestion(QuestionRefId qid) { ... }
}
```

### 3.3 Value objects

```java
public record Score(BigDecimal value) { ... }
public record QuestionRefId(String value) { ... }
public record AnswerPayload(String rawJson) { /* validated theo question.type */ }
public record GradingResult(Score earned, boolean correct, boolean needsManual, String method) { }
public record AttemptSnapshot(short currentIdx, Duration remaining, short riskScore) { }
```

---

## IV. STATE MACHINE

### 4.1 Exam state machine

```
       ┌─────┐   publish    ┌──────────┐
       │draft├─────────────►│published │
       └──┬──┘              └────┬─────┘
          │                      │
          │      schedule        │
          │ ◄────────────────────┤ (nếu starts_at > now)
          ▼                      ▼
     ┌─────────┐            ┌─────────┐
     │scheduled│ ──start──► │ active  │
     └─────────┘            └────┬────┘
                                 │ ends_at reached
                                 ▼
                            ┌─────────┐ archive ┌──────────┐
                            │completed├────────►│archived  │
                            └─────────┘         └──────────┘
```

**Guards & actions:**

| Transition | Guard | Action |
| ---------- | ----- | ------ |
| draft → published | có >= 1 câu hỏi, total_points > 0 | `published_at = now`; publish event |
| published → scheduled | `starts_at > now` | auto, cron every 1 min |
| published/scheduled → active | `now >= starts_at` | auto cron; cache config lên Redis |
| active → completed | `now > ends_at + grace_period` OR tất cả enrollee đã submit | cron every 1 min; publish `exam.completed` |
| completed → archived | thủ công hoặc cron sau 90 ngày | freeze data, không cho sửa |

### 4.2 Attempt state machine

```
                     ┌──────────────┐
                     │ in_progress  │
                     └──┬─────┬─────┘
             submit     │     │  timeout (now >= expires_at)
                   ┌────┘     └────┐
                   ▼               ▼
             ┌──────────┐     ┌─────────┐
             │submitted │     │ expired │ ← auto cron every 30s
             └────┬─────┘     └─────────┘
                  │ grade
                  ▼
             ┌──────────┐
             │  graded  │
             └──────────┘

             in_progress ──cancel──► cancelled (student chủ động huỷ — rare)
             in_progress ──risk>=60──► suspended (chờ proctor review)
             suspended ──resume──► in_progress  OR  ──confirm_cheat──► submitted (0 điểm)
```

**Guards:**

| Transition | Guard |
| ---------- | ----- |
| → in_progress (khởi tạo) | exam đang active, user được enroll, không còn attempt đang chạy |
| in_progress → submitted | chưa hết giờ HOẶC force submit |
| in_progress → expired | `now >= expires_at + network_grace_5s` |
| in_progress → suspended | consume cheat event với `attempt.risk_score >= 60` |
| submitted → graded | tất cả answer có `points_earned` hoặc `grading_method = manual` kèm `graded_at` |

### 4.3 Triển khai bằng Spring State Machine

Dùng thư viện `spring-statemachine-core`, config mỗi aggregate 1 state machine factory. **Lưu ý:** persist trạng thái trong entity field, không persist bằng `StateMachinePersister` (tránh phức tạp — chỉ dùng library để validate transition).

---

## V. SESSION MANAGEMENT (Redis)

### 5.1 Cấu trúc Redis cho 1 attempt đang thi

Theo schema tại `database.md` mục 4.1, nhưng với chi tiết thao tác:

| Key | Kiểu | Trường | Cập nhật bởi |
| --- | ---- | ------ | ------------ |
| `session:{attempt_id}` | Hash | `remaining_seconds`, `current_q_idx`, `risk_score`, `status`, `last_heartbeat`, `exam_id`, `user_id` | Mọi request ghi vào attempt |
| `answers:{attempt_id}` | Hash | `{question_ref_id}` → JSON payload | POST /answers |
| `q_order:{attempt_id}` | List | UUID câu hỏi đã xáo | Set khi start attempt |
| `adaptive:{attempt_id}` | Hash | `theta`, `se`, `answered_ids`, `next_q_pool` | Adaptive selector |
| `lock:exam_start:{attempt_id}` | String NX | distributed lock khi start | StartAttemptUseCase |
| `lock:submit:{attempt_id}` | String NX | lock khi submit | SubmitAttemptUseCase |

**TTL chiến lược:**
- `session:*`, `answers:*`, `q_order:*`: `duration + 30 phút` (cho phép chấm xong)
- `adaptive:*`: `duration + 1 giờ`

### 5.2 Timer authoritative

**Nguyên tắc:** server là single source of truth cho thời gian. Client chỉ dùng để hiển thị.

```
remaining_seconds = max(0, (expires_at - now_utc).total_seconds)
```

- `expires_at` lưu trong `session:{attempt_id}` + Postgres `exam_attempts.expires_at`
- Client heartbeat WebSocket mỗi 10s → server trả `server_time_ms` + `remaining_ms`
- Client điều chỉnh đồng hồ hiển thị theo trả về, không tin `Date.now()` của mình
- Nếu client mất kết nối > 30s → server sẽ **không** pause; user vẫn bị tính giờ
- Pause chỉ xảy ra nếu proctor suspend attempt (status = `suspended`)

### 5.3 Đồng bộ Redis ↔ PostgreSQL

Chiến lược: **Redis = cache write-behind cho đáp án, PostgreSQL = durable store**

```
POST /answers  →  Lưu Redis synchronously (AOF fsync=always)
                 ↓
                 Publish event answer.submitted Kafka
                 ↓
                 AttemptAnswerPersistWorker consumer
                 ↓  UPSERT PostgreSQL attempt_answers (idempotent via submission_id)
                 (độ trễ < 1s trong điều kiện bình thường)

POST /submit   →  Flush Redis → PostgreSQL (batch insert tất cả answers)
                 ↓
                 Đánh dấu attempt.status = 'submitted'
                 ↓
                 Publish exam.completed
```

**Vì sao 2 lớp:** Redis đảm bảo latency < 100ms cho `POST /answers`, PG đảm bảo durable. Nếu Redis chết trước khi flush, consumer Kafka vẫn replay được answer.submitted events từ 7 ngày qua → rebuild state.

### 5.4 Resume attempt sau disconnect

```
GET /attempts/{id}/resume
  ↓
Check session:{attempt_id} tồn tại
  ├─ Có → trả về {remaining_sec, current_q_idx, đáp án đã lưu}
  └─ Không (Redis restart): fallback
       ↓
       Load từ PG: SELECT * FROM exam_attempts WHERE id=?
       IF status='in_progress' AND now < expires_at:
           Rebuild session:{attempt_id} từ PG answers
           Return state
       ELSE:
           Return 410 Gone - attempt expired/submitted
```

---

## VI. HOT PATH FLOWS

### 6.1 Start Attempt (critical)

**Target p99 < 300ms.**

```
POST /exams/{examId}/start
Headers: Authorization: Bearer <JWT>, X-Device-Fingerprint: ...
Body:    { "access_password?": "xxx" }
```

Pseudocode:

```
1. Verify JWT (offline via JWKS cached)
2. Extract user_id, org_id

3. ACQUIRE distributed lock lock:start:{exam_id}:{user_id} TTL 10s
   ├─ Fail → 409 CONFLICT "another start in progress"

4. Load exam (cache exam:config:{exam_id} — Redis String 30m)
   Check:
   ├─ exam.status ∈ {published, active}
   ├─ now ∈ [starts_at, ends_at]
   ├─ user.org_id == exam.org_id OR user được enroll
   ├─ IP ∈ exam.ip_whitelist (nếu có)
   ├─ access_password khớp (nếu có)
   ├─ Số attempt đã dùng < max_attempts
   └─ Không có attempt khác đang in_progress (UNIQUE INDEX PG)

5. Lấy danh sách câu hỏi
   ├─ Cache exam:q_ids:{exam_id} (List Redis 30m)
   │   miss → gRPC Question Service → load + cache
   └─ Xáo trộn nếu exam.shuffle_questions:
       ├─ seed = hash(attempt_id + exam.id)   // deterministic
       └─ Fisher-Yates với seed

6. Tạo record exam_attempts trong PG (transactional)
   - status = 'in_progress'
   - started_at = NOW()
   - expires_at = started_at + duration + grace_period
   - question_order = shuffled list
   - max_score = exam.total_points

7. Ghi Redis:
   HSET session:{attempt_id} ...
   RPUSH q_order:{attempt_id} ...
   EXPIRE session:* duration+30min

8. Nếu adaptive:
   HSET adaptive:{attempt_id} theta=0 se=1.0
   Chọn câu đầu: câu có b gần 0, a cao nhất trong pool

9. Publish Kafka event attempt.started

10. Trả về:
    {
      "attempt_id": "...",
      "expires_at": "ISO-8601",
      "server_time": "ISO-8601",
      "question": { /* câu đầu tiên, không có is_correct */ },
      "total_questions": 15,
      "current_index": 0,
      "ws_url": "wss://api.smartquiz.vn/ws/attempts/{attempt_id}"
    }

11. RELEASE lock
```

**Optimization:** Bước 4-5 (load config + câu hỏi) phải hit cache, cache miss lần đầu bài thi mới publish → warm-up cache khi publish.

### 6.2 Submit Answer (hot path)

**Target p99 < 100ms.** Phải **idempotent**.

```
POST /attempts/{attemptId}/answers
Body: {
  "question_ref_id": "q-...",
  "answer_data": { /* tùy question type */ },
  "submission_id": "uuid-client-generated",
  "client_timestamp": "ISO-8601"
}
```

Pseudocode:

```
1. Verify JWT + check user owns attempt (cache attempt:owner:{id})

2. Idempotency check
   GET idempotency:{submission_id}
   ├─ Hit → trả về cached response ngay (client đang retry)
   └─ Miss → tiếp tục, SET idempotency:{submission_id} EX 1h sau khi xử lý xong

3. Load session HGETALL session:{attempt_id}
   Check:
   ├─ status == 'in_progress'
   ├─ now < expires_at (+5s grace)
   ├─ question_ref_id ∈ q_order:{attempt_id}
   └─ question_ref_id hợp lệ (cho phép trả lời câu bất kỳ, không bắt buộc tuần tự — trừ adaptive)

4. Validate payload shape theo question.type
   (multiple_choice_single phải có "selected_options": [1 element])
   (code_execution phải có "language" và "code")
   ...

5. HSET answers:{attempt_id} {q_ref_id} <JSON payload>

6. Update session:
   HSET session:{attempt_id} current_q_idx {index}
   HSET session:{attempt_id} last_heartbeat {now}

7. Publish Kafka event answer.submitted
   {attempt_id, question_ref_id, submission_id, payload_hash, ts}

8. Trả về:
   { "saved": true, "submission_id": "...", "server_time": "..." }

9. Async (worker consumer):
   UPSERT attempt_answers ON CONFLICT (submission_id) DO NOTHING

10. Cập nhật ES/Analytics — không cần trong hot path
```

**Vì sao `submission_id` unique trong `attempt_answers`?** Chống trùng lặp khi client retry. Nếu worker đã xử lý 1 lần rồi, lần sau UPSERT bị bỏ qua.

### 6.3 Submit Final (critical)

**Target p99 < 500ms.** Đây là điểm **không được phép fail silent**.

```
POST /attempts/{attemptId}/submit
```

```
1. ACQUIRE lock:submit:{attempt_id} TTL 30s

2. Load session + check status = 'in_progress'

3. Flush tất cả answers từ Redis → PG (batch upsert)
   - Đọc HGETALL answers:{attempt_id}
   - So với attempt_answers đã có → bổ sung các missing
   - Batch INSERT ... ON CONFLICT (submission_id) DO NOTHING

4. Auto-grade tất cả câu tự chấm được
   Fan-out theo question.type:
   ├─ multiple_choice_*, true_false, ordering, matching, drag_drop, hotspot
   │   → gọi AutoGrader (sync, < 50ms/question)
   ├─ fill_blank, short_answer
   │   → nếu confidence >= 0.75: auto
   │      nếu không: publish manual.review.required
   └─ essay, code_execution
       → publish grading.request Kafka cho AI Service
       → answer.is_correct = NULL (tạm)

5. Tổng hợp điểm phần đã chấm:
   UPDATE exam_attempts SET
     raw_score = SUM(points_earned WHERE is_correct IS NOT NULL),
     submitted_at = NOW(),
     status = 'submitted',
     time_spent_seconds = NOW() - started_at
   WHERE id = ?

6. Nếu không còn câu pending AI (synchronous đủ hết):
   - Tính percentage_score, passed
   - Set status = 'graded', graded_at = NOW()
   - Publish attempt.graded
   Else:
   - Publish attempt.submitted (không graded)
   - Status chuyển sang 'graded' bởi GradingAggregatorConsumer khi nhận đủ kết quả

7. Remove Redis session keys (đã có snapshot trong PG)
   DEL session:*, answers:*, q_order:*, adaptive:*
   SREM exam:concurrent:{exam_id} {attempt_id}

8. Trả về:
   {
     "status": "submitted",
     "grading": {
       "total_points": 85.0,
       "max_score": 100.0,
       "percentage_score": 85.0,
       "passed": true,
       "pending_ai": 2  // số câu đang chờ AI chấm
     },
     "result_url": "/attempts/{id}/result"
   }

9. RELEASE lock
```

### 6.4 Expire Attempt (background job)

Spring `@Scheduled(fixedDelay = 30_000)` + ShedLock:

```java
@Scheduled(fixedDelay = 30_000)
@SchedulerLock(name = "expire-attempts", lockAtMostFor = "25s")
void expireInProgressAttempts() {
    List<AttemptId> expired = repo.findExpiredInProgress(now());
    for (var id : expired) {
        // Force submit với flag auto_expired=true
        submitUseCase.submit(id, SubmitSource.AUTO_EXPIRED);
    }
}
```

### 6.5 Suspend (nhận từ Cheating Service)

Consumer Kafka topic `cheat.alert.generated`:

```java
@KafkaListener(topics = "cheat.alert.generated")
void onCheatAlert(CheatAlertEvent event) {
    if (event.riskScore() >= 60) {
        var attempt = repo.findById(event.attemptId());
        if (attempt.status() == IN_PROGRESS) {
            attempt.suspendForCheating(event.reason());
            repo.save(attempt);

            // Push WS notification tới proctor + student
            wsPublisher.sendToAttempt(event.attemptId(),
                new SuspendMessage(event.reason()));
        }
    }
}
```

Proctor có thể resume (`POST /proctoring/attempts/{id}/resume`) hoặc xác nhận cheat (`POST /proctoring/attempts/{id}/terminate`) — endpoint thuộc Proctoring Service nhưng update qua Exam Service gRPC.

---

## VII. GRADING PIPELINE

### 7.1 Phân loại theo khả năng auto-grade

| Type | Auto? | Engine | Độ trễ |
| ---- | ----- | ------ | ------ |
| multiple_choice_single | ✅ sync | Equal set check | < 5ms |
| multiple_choice_multi | ✅ sync | Set equality | < 5ms |
| true_false | ✅ sync | Boolean | < 1ms |
| matching | ✅ sync | Pair map | < 10ms |
| ordering | ✅ sync | Array compare | < 10ms |
| drag_drop | ✅ sync | Map compare | < 10ms |
| hotspot | ✅ sync | Point-in-circle | < 10ms |
| fill_blank | ✅ sync (confidence ≥ 0.75) | Regex/fuzzy match | < 20ms |
| short_answer | ⚠️ AI-assist | Embedding + keyword | 200-500ms |
| essay | ❌ async | LLM + rubric | 30-60s |
| code_execution | ❌ async | Sandbox (gVisor) | 2-30s |

### 7.2 Fan-out chấm điểm

```
          POST /submit
              │
              ▼
       ┌──────────────┐
       │ Grader router│
       └──┬─────┬─────┘
    sync  │     │  async
          ▼     ▼
  ┌────────────┐  ┌────────────────────┐
  │AutoGrader  │  │Kafka grading.request│
  │(in-process)│  └─────────┬──────────┘
  └────────────┘            │
                            ▼
                      AI Service / Sandbox
                            │
                            ▼
                   Kafka grading.result
                            │
                            ▼
              GradingAggregatorConsumer
                            │
                            ▼
                 UPDATE attempt_answers +
                 nếu tất cả answer đã graded:
                 UPDATE exam_attempts.status = 'graded'
                 Publish attempt.graded
```

### 7.3 Auto-grader implementations

```java
public interface AutoGrader {
    boolean supports(QuestionType type);
    GradingResult grade(Question question, AnswerPayload payload);
}

@Component
public class MultipleChoiceSingleGrader implements AutoGrader {
    public boolean supports(QuestionType type) { return type == MULTIPLE_CHOICE_SINGLE; }

    public GradingResult grade(Question q, AnswerPayload p) {
        var selected = p.getJson().getAsJsonArray("selected_options");
        if (selected.size() != 1) return GradingResult.incorrect();
        var correctOpt = q.options().stream().filter(Option::isCorrect).findFirst();
        boolean correct = correctOpt.isPresent() &&
            correctOpt.get().id().equals(selected.get(0).getAsString());
        return new GradingResult(
            correct ? q.points() : ZERO,
            correct,
            false,
            "auto"
        );
    }
}
```

Các grader register vào `AutoGraderRegistry`, router chọn grader theo `question.type`.

---

## VIII. ADAPTIVE EXAM (IRT)

### 8.1 Model 3PL

```
P(đúng | θ, a, b, c) = c + (1 - c) * 1/(1 + exp(-a*(θ - b)))
```

- `θ` (theta): năng lực học sinh
- `a`: độ phân biệt
- `b`: độ khó
- `c`: xác suất đoán ngẫu nhiên

### 8.2 Flow

```
Start: θ₀ = 0, SE = 1.0, answered = {}

Mỗi câu:
  1. Chọn câu q* trong pool chưa answer, không vi phạm ràng buộc topic:
     q* = argmax_q { I(θ, q) = a² * P * (1-P) }

  2. Trả câu cho client

  3. Client answer → server grade (auto)

  4. Update θ qua Newton-Raphson MLE:
     - Likelihood L(θ) = Π P(θ,a,b,c)^y * (1-P)^(1-y)
     - Cập nhật: θ_new = θ_old + L'(θ)/L''(θ)

  5. Update SE = 1/sqrt(I_total(θ))

  6. Dừng khi:
     - SE < 0.30 (ước tính đủ tin cậy)
     - Đủ max_questions
     - Hết giờ

  7. Điểm cuối = transform θ → T-score (mean=50, sd=10) hoặc percentile
```

### 8.3 Triển khai

```java
@Component
public class AdaptiveSelector {

    public QuestionRefId nextQuestion(AttemptId attemptId, double theta) {
        var answered = redis.sMembers("adaptive:"+attemptId+":answered");
        var pool     = redis.lRange("adaptive:"+attemptId+":pool", 0, -1);

        return pool.stream()
            .filter(q -> !answered.contains(q))
            .filter(q -> topicConstraintsOk(attemptId, q))
            .max(Comparator.comparingDouble(q -> fisherInfo(theta, q.irt())))
            .orElseThrow(() -> new PoolExhausted());
    }

    double fisherInfo(double theta, IrtParams p) {
        double pTheta = prob3PL(theta, p.a(), p.b(), p.c());
        return Math.pow(p.a(), 2) * pTheta * (1 - pTheta);
    }

    public ThetaUpdate updateTheta(double theta, List<Response> responses) {
        // Newton-Raphson
        ...
    }
}
```

### 8.4 Fallback

Nếu `adaptive:{attempt_id}` Redis bị mất: rebuild từ `attempt_answers` đã có trong PG, tính lại θ từ đầu. Có thể làm cho UX bị giật 1 nhịp — acceptable.

---

## IX. WEBSOCKET PROTOCOL

### 9.1 Connection

```
WSS /ws/attempts/{attempt_id}
Headers: Authorization: Bearer <JWT>
```

Handshake: verify JWT → check user owns attempt_id và status = in_progress → accept.

### 9.2 Message types (STOMP frames, JSON body)

**Server → Client:**

| Type | Payload | Khi nào gửi |
| ---- | ------- | ----------- |
| `timer.sync` | `{remaining_ms, server_time, expires_at}` | Mỗi 10s + khi client subscribe |
| `timer.warning` | `{remaining_ms, level: "5min"|"1min"|"30s"}` | Khi còn 5p/1p/30s |
| `cheat.warning` | `{type, message, severity}` | Khi cheat service phát hiện |
| `attempt.suspended` | `{reason}` | Khi suspend |
| `attempt.auto_submitted` | `{reason}` | Khi server force submit (timeout) |
| `question.force_navigate` | `{question_index}` | Adaptive: server yêu cầu nhảy câu |
| `ping` | `{server_time}` | Mỗi 30s (keepalive) |

**Client → Server:**

| Type | Payload | Tác dụng |
| ---- | ------- | -------- |
| `heartbeat` | `{client_time, current_q_idx}` | Mỗi 10s; cập nhật last_heartbeat |
| `cheat.event` | `{event_type, event_data}` | Forward sang Cheating Service qua Kafka |
| `pong` | `{}` | Response ping |

### 9.3 Scale WebSocket

**Vấn đề:** 100.000 WS đồng thời, 20 pod.

**Giải pháp:**
- **Sticky session** ở ingress (client + attempt_id gắn với 1 pod)
- Mỗi pod giữ local registry `Map<attempt_id, WebSocketSession>`
- Khi pod khác cần gửi message đến attempt ở pod này (ví dụ cheat alert): dùng **Redis pub/sub** channel `ws:exam:{attempt_id}` → pod nào subscribe attempt đó mới nhận
- Pod crash: client auto-reconnect (exp backoff), ingress route sang pod mới, pod mới subscribe lại Redis channel

```
Pod A:
   client1 (attempt X) ─┐
                        ├─► holds WS connections
   client2 (attempt Y) ─┘
   
   SUBSCRIBE ws:exam:X, ws:exam:Y  (Redis)
   
Cheating Service:
   PUBLISH ws:exam:X {"type":"cheat.warning", ...}
   
Pod A receives → forward to client1's WebSocketSession
```

### 9.4 Disconnect handling

- Client lose connection → server giữ session trạng thái unchanged (KHÔNG pause timer)
- Reconnect: client gọi `GET /attempts/{id}/snapshot` trước khi reconnect WS để sync state
- Pod crash: lose all local WS state; clients reconnect qua ingress → pod khác; timer vẫn đúng vì authoritative server-side

---

## X. API ENDPOINTS CHI TIẾT

> **Ghi chú về cột "Role":** Chỉ là **default role** có permission cần thiết (theo seed `role_permissions`). Enforcement thật dùng permission code (xem mục XIII.1). Org có thể gán permission cho role tuỳ biến.

### 10.1 Exam management (instructor/admin)

| Method | Path | Body | Role |
| ------ | ---- | ---- | ---- |
| POST | `/exams` | ExamCreateDto | instructor |
| GET | `/exams?org_id=&status=&q=&page=` | — | instructor, admin |
| GET | `/exams/{id}` | — | authenticated |
| PATCH | `/exams/{id}` | ExamUpdateDto | instructor (owner) |
| POST | `/exams/{id}/publish` | — | instructor (owner), admin |
| POST | `/exams/{id}/archive` | — | instructor (owner), admin |
| DELETE | `/exams/{id}` | — | instructor (owner) soft delete |
| POST | `/exams/{id}/sections` | SectionDto | instructor |
| POST | `/exams/{id}/questions` | `{question_ref_id, section_id?, order_index, points}` | instructor |
| DELETE | `/exams/{id}/questions/{eqId}` | — | instructor |
| POST | `/exams/{id}/enrollments` | `{user_ids: [...]}` (bulk) | instructor |
| DELETE | `/exams/{id}/enrollments/{userId}` | — | instructor |
| GET | `/exams/{id}/analytics` | — | instructor |
| GET | `/exams/{id}/attempts?status=&page=` | — | instructor |

### 10.2 Attempt (student)

| Method | Path | Body | Role |
| ------ | ---- | ---- | ---- |
| POST | `/exams/{id}/start` | `{access_password?}` | student (enrolled) |
| GET | `/attempts/{id}/snapshot` | — | owner (reconnect) |
| POST | `/attempts/{id}/answers` | AnswerDto | owner |
| PATCH | `/attempts/{id}/navigate` | `{current_question_index}` | owner |
| POST | `/attempts/{id}/submit` | — | owner |
| GET | `/attempts/{id}/result` | — | owner (sau submit), instructor |
| WS | `/ws/attempts/{id}` | STOMP | owner |

### 10.3 Admin / proctoring integration

| Method | Path | Role |
| ------ | ---- | ---- |
| POST | `/attempts/{id}/suspend` | proctor, admin |
| POST | `/attempts/{id}/resume` | proctor, admin |
| POST | `/attempts/{id}/terminate` | proctor, admin |
| POST | `/attempts/{id}/regrade` | admin (re-trigger grading) |

### 10.4 gRPC (internal)

```proto
service ExamService {
    rpc GetAttempt(GetAttemptRequest) returns (Attempt);
    rpc SuspendAttempt(SuspendRequest) returns (Empty);
    rpc UpdateRiskScore(RiskUpdate) returns (Empty);  // từ Cheating Service
    rpc StreamAttemptEvents(AttemptFilter) returns (stream AttemptEvent);  // cho Analytics
}
```

---

## XI. EVENTS (KAFKA)

### 11.1 Produced

| Topic | Key | Payload (chính) | Consumer |
| ----- | --- | --------------- | -------- |
| `exam.lifecycle` | exam_id | `{exam_id, status, org_id, ts}` | Analytics, Notification |
| `attempt.started` | attempt_id | `{attempt_id, exam_id, user_id, started_at, ip, geo}` | Analytics, Cheating |
| `answer.submitted` | attempt_id | `{attempt_id, q_ref_id, submission_id, ts}` | AttemptAnswerPersister, Analytics |
| `attempt.submitted` | attempt_id | `{attempt_id, raw_score?, time_spent_sec}` | Analytics, Notification |
| `attempt.graded` | attempt_id | `{attempt_id, raw_score, percentage, passed}` | Analytics, Certificate, Notification |
| `attempt.suspended` | attempt_id | `{attempt_id, reason, risk_score}` | Notification, Audit |
| `grading.request` | attempt_answer_id | `{attempt_id, q_ref_id, type, payload, rubric?}` | AI Service, Code Runner |
| `exam.completed` | exam_id | `{exam_id, total_attempts}` | Analytics |

### 11.2 Consumed

| Topic | Group | Hành động |
| ----- | ----- | --------- |
| `cheat.alert.generated` | exam-service-cheat | Update risk_score, suspend nếu ≥ 60 |
| `grading.result` | exam-service-grading | Update attempt_answers, aggregate điểm, chuyển state |
| `question.updated` | exam-service-questions | Invalidate cache `exam:q_ids`, `question:*` |
| `user.deleted` | exam-service-user-deletion | Anonymize attempt (GDPR) |

### 11.3 Producer config

```yaml
spring.kafka.producer:
  acks: all
  enable.idempotence: true
  max.in.flight.requests.per.connection: 5
  retries: Integer.MAX_VALUE
  compression.type: lz4
  linger.ms: 5
```

---

## XII. INTEGRATION VỚI CÁC SERVICE KHÁC

| Service | Giao tiếp | Use case | Fallback khi lỗi |
| ------- | --------- | -------- | ---------------- |
| Auth Service | gRPC (ValidateToken cache) + JWKS offline | Verify JWT, get user info | Reject request (401) |
| Question Service | gRPC (GetQuestion, BatchGetQuestions) | Load câu hỏi khi start attempt | Cache Redis 2h; nếu cache miss và service down → 503 + retry |
| AI Service | Kafka async | Grade essay/code | Mark answer `grading_method=manual`, cần giáo viên chấm |
| Cheating Service | Kafka consume cheat alerts | Update risk, suspend | Exam vẫn chạy, không suspend (degraded) |
| Analytics Service | Kafka produce events | Dashboard giáo viên | Events đệm vào Kafka, không ảnh hưởng exam |
| Notification | Kafka produce events | Email kết quả | Không critical |
| Proctoring | gRPC bi-directional | Suspend/resume | Không có proctoring vẫn thi được |
| Media | REST (signed URL cho media trong câu hỏi) | Render ảnh/video câu hỏi | Broken image, câu hỏi text vẫn hiển thị |

**Circuit breaker:** Dùng Resilience4j cho mọi sync call:
```
- Question Service: CB mở sau 5 fail liên tiếp trong 10s, half-open 30s sau
- Auth Service: JWKS local cache 1h → service down vẫn verify được
```

---

## XIII. SECURITY & ACCESS CONTROL

### 13.1 Permission-based authorization (KHÔNG hardcode role)

Exam Service **không check role name** mà check **permission code** được Auth Service cấp trong JWT claim `authorities`. Xem chi tiết `auth-service-design.md` mục 3.3.

```java
// Xem bài thi — yêu cầu permission exam.read + policy check org/enrollment
@PreAuthorize("hasAuthority('exam.read') and @examPolicy.canView(authentication, #examId)")
@GetMapping("/exams/{examId}")
public ExamDto getExam(@PathVariable UUID examId) { ... }

// Sửa bài thi mình tạo
@PreAuthorize("hasAuthority('exam.update.own') and @examPolicy.isOwner(authentication, #examId)")
@PatchMapping("/exams/{examId}")
public void updateExam(...) { ... }

// Sửa bất kỳ bài thi trong org (admin)
@PreAuthorize("hasAuthority('exam.update.any') and @examPolicy.isSameOrg(authentication, #examId)")
@PatchMapping("/exams/{examId}/admin-override")
public void updateExamAny(...) { ... }

// Publish
@PreAuthorize("hasAuthority('exam.publish') and @examPolicy.canPublish(authentication, #examId)")
public void publishExam(UUID examId) { ... }

// Suspend attempt (proctor or admin)
@PreAuthorize("hasAuthority('attempt.suspend')")
public void suspendAttempt(UUID attemptId, String reason) { ... }
```

**Nguyên tắc:**
- `hasAuthority('<permission_code>')` thay cho `hasRole('<ROLE_NAME>')`
- `ExamPolicy` bean chỉ kiểm tra scope (own / same-org / enrolled), **không** check role
- Thêm role custom ở Auth Service → không cần đụng code Exam Service

Mapping permission ↔ endpoints xem `auth-service-design.md` mục 3.4 (bảng grant mặc định) và `database/postgresql/seed.sql` (`role_permissions`).

### 13.2 Password-protected exam

```java
if (exam.passwordProtected()) {
    if (!passwordHasher.verify(dto.accessPassword(), exam.accessPasswordHash())) {
        throw new ForbiddenException(EXAM_PASSWORD_INVALID);
    }
}
```

### 13.3 IP whitelist

```java
if (!exam.ipWhitelist().isEmpty()) {
    if (!exam.ipWhitelist().contains(clientIp)) {
        log.warn("Rejected start from non-whitelisted IP {}", clientIp);
        throw new ForbiddenException(EXAM_IP_NOT_ALLOWED);
    }
}
```

### 13.4 Lockdown browser

- Exam Service không tự ép browser — chỉ set flag trong exam config
- Client app (trình duyệt khóa chuyên dụng) gửi header `X-Lockdown-Browser: true` + shared secret
- Exam Service verify header; nếu `exam.lockdown_browser && !header present` → 403

### 13.5 Prevent answer tampering

- Client không bao giờ nhận `is_correct` hoặc points của câu hỏi khi đang làm
- `GET /exams/{id}` khi role = student: trả về payload câu hỏi đã strip:
  - `options` giữ `{id, text}`, bỏ `is_correct`, `explanation`
  - `correct_order`, `correct_zone`, `grading_config`: ẩn hoàn toàn
- DTO serialization dùng Jackson View (view `StudentView` vs `InstructorView`)

### 13.6 Data isolation (multi-tenancy)

PostgreSQL Row-Level Security: mọi query tự động lọc `org_id`:

```sql
ALTER TABLE exams ENABLE ROW LEVEL SECURITY;
CREATE POLICY org_isolation_exams ON exams
  USING (org_id = current_setting('app.current_org_id')::UUID);
```

Trong Spring: `JdbcTemplate` set session var `app.current_org_id` từ JWT claim trước mỗi request (Interceptor).

---

## XIV. OBSERVABILITY

### 14.1 Metrics (Prometheus)

| Metric | Type | Label |
| ------ | ---- | ----- |
| `exam_attempt_started_total` | counter | `exam_id`, `org_id` |
| `exam_attempt_submitted_total` | counter | `result=passed\|failed` |
| `exam_attempt_expired_total` | counter | — |
| `exam_attempt_suspended_total` | counter | `reason` |
| `exam_answer_submitted_total` | counter | `question_type` |
| `exam_active_attempts` | gauge | — (update 10s) |
| `exam_active_ws_connections` | gauge | `pod` |
| `exam_start_duration_seconds` | histogram | — |
| `exam_answer_duration_seconds` | histogram | — |
| `exam_submit_duration_seconds` | histogram | — |
| `exam_grading_duration_seconds` | histogram | `method=auto\|ai\|manual` |
| `exam_redis_flush_duration_seconds` | histogram | — |
| `exam_kafka_producer_queue_size` | gauge | `topic` |

### 14.2 SLO

| SLI | Target |
| --- | ------ |
| `POST /answers` p99 < 100ms | 99% thời gian |
| Submit success rate | > 99.99% |
| WS uptime per attempt | > 99.9% (giữ connection > 99.9% thời gian thi) |
| Data loss (answer mất) | 0 (tuyệt đối) |

### 14.3 Alerts

| Alert | Điều kiện | Severity |
| ----- | --------- | -------- |
| `ExamServiceDown` | up == 0 trong 2 phút | CRITICAL |
| `AnswerLatencyHigh` | p99 > 300ms trong 5 phút | WARNING |
| `SubmitFailureSpike` | fail rate > 0.1% trong 5 phút | CRITICAL |
| `RedisDown` | redis ping fail | CRITICAL (đáp án có nguy cơ mất) |
| `ActiveAttemptsDrop` | active_attempts giảm 50% trong 1 phút | CRITICAL (có thể mất connection hàng loạt) |
| `GradingBacklog` | grading.request topic lag > 1000 | WARNING |
| `KafkaProducerBlocked` | queue_size > 5000 trong 1 phút | WARNING |

### 14.4 Structured logs

```json
{
  "ts": "2026-04-18T10:05:22.123Z",
  "level": "INFO",
  "service": "exam-service",
  "trace_id": "...",
  "span_id": "...",
  "event": "attempt.started",
  "attempt_id": "...",
  "exam_id": "...",
  "user_id": "...",
  "org_id": "...",
  "duration_ms": 184
}
```

**Không log:** đáp án thô (có thể có PII ở essay), JWT, password.

### 14.5 Distributed tracing

OpenTelemetry Java Agent tự instrument Spring, JDBC, Redis, Kafka, gRPC. Flow `POST /answers` hiện span:
```
HTTP POST /answers  (root, 85ms)
 ├─ auth.validate_token     5ms
 ├─ redis.session.load      8ms
 ├─ redis.answer.store      10ms
 ├─ kafka.publish answer.submitted  20ms
 └─ redis.idempotency.set   3ms
```

Jaeger UI cho ops trace khi có incident.

---

## XV. PERFORMANCE & CAPACITY

### 15.1 Profile tải đỉnh (peak)

Giả định kỳ thi lớn: 10.000 học sinh thi đồng thời trong 60 phút.

| Tài nguyên | Tính toán | Giá trị |
| ---------- | --------- | ------- |
| Số câu trả lời | 10.000 × 30 câu = 300.000 trên 60 phút | 83 RPS average |
| Peak answer RPS | Tập trung giữa kỳ | ~200 RPS |
| WS connections | 10.000 | 2 pod × 5k |
| Redis QPS | 200 ghi answer + 1000 heartbeat/WS/10s = 1000 HSET + 200 HGET | ~2k QPS |
| Kafka produce | 200/s × 2 topic = 400/s | trivial |
| Postgres writes | 83 INSERT answer/s (async qua consumer) | trivial |
| Postgres reads | 10 start/s + 10 snapshot/s = 20 QPS | trivial |

**Kết luận:** Bottleneck = Redis CPU + memory. Cần 3-node Redis cluster để HA.

### 15.2 Dung lượng

| Item | Kích thước | Ghi chú |
| ---- | ---------- | ------- |
| 1 session Redis | ~500 bytes | Hash |
| 1 answer Redis entry | ~1KB avg (code_execution có thể 10KB) | |
| 10k concurrent attempts | ~150MB RAM | + overhead |
| attempt_answers PG | ~2KB/row | Partition theo tháng |

Dự báo 2 năm: 50 triệu attempt × 30 câu × 2KB = 3TB attempt_answers. **Bắt buộc partition theo tháng.**

### 15.3 Scale out plan

| Trigger | Hành động |
| ------- | --------- |
| CPU > 70% hoặc > 5000 WS/pod | HPA thêm pod |
| Redis CPU > 75% | Tăng shard Redis cluster |
| Kafka lag > 10k ở grading.result | Tăng replica AI Service |
| PG connections > 80% limit | Tăng connection pool hoặc thêm read replica |

### 15.4 Load testing

Dùng **k6** với kịch bản:

```js
export let options = {
  scenarios: {
    peak_exam: {
      executor: 'ramping-vus',
      stages: [
        { duration: '2m', target: 2000 },
        { duration: '5m', target: 10000 },
        { duration: '60m', target: 10000 },
        { duration: '2m', target: 0 }
      ]
    }
  },
  thresholds: {
    'http_req_duration{scenario:peak_exam,endpoint:answer}': ['p(99)<100'],
    'http_req_duration{scenario:peak_exam,endpoint:submit}': ['p(99)<500'],
    'checks': ['rate>0.999']
  }
};
```

Chạy trên 2-3 node load generator (mỗi node 4 vCPU / 8 GB).

---

## XVI. ERROR HANDLING

### 16.1 Mã lỗi

| Code | HTTP | Ý nghĩa |
| ---- | ---- | ------- |
| `EXAM_NOT_FOUND` | 404 | Exam không tồn tại hoặc đã xoá |
| `EXAM_NOT_OPEN` | 409 | Exam chưa tới starts_at hoặc đã qua ends_at |
| `EXAM_ACCESS_DENIED` | 403 | User không được enroll |
| `EXAM_PASSWORD_INVALID` | 403 | Sai mật khẩu truy cập |
| `EXAM_IP_NOT_ALLOWED` | 403 | IP không nằm trong whitelist |
| `EXAM_MAX_ATTEMPTS_REACHED` | 409 | Dùng hết attempt |
| `ATTEMPT_IN_PROGRESS_EXISTS` | 409 | Đã có attempt đang chạy |
| `ATTEMPT_NOT_FOUND` | 404 | Attempt không tồn tại |
| `ATTEMPT_EXPIRED` | 410 | Attempt đã quá giờ |
| `ATTEMPT_ALREADY_SUBMITTED` | 409 | Đã nộp rồi |
| `ATTEMPT_SUSPENDED` | 423 | Attempt bị suspend, không nộp được |
| `ANSWER_INVALID_PAYLOAD` | 422 | Shape không khớp question.type |
| `ANSWER_UNKNOWN_QUESTION` | 404 | question_ref_id không thuộc attempt |
| `GRADING_IN_PROGRESS` | 202 | Kết quả chưa có (AI đang chấm) |
| `LOCK_ACQUISITION_FAILED` | 409 | Conflict khi start/submit |

### 16.2 Retry guidance (cho client)

| Error | Client nên làm |
| ----- | -------------- |
| 5xx (any) | Retry exponential backoff, max 3 lần |
| 409 `ATTEMPT_IN_PROGRESS_EXISTS` | Navigate resume, không tạo mới |
| 410 `ATTEMPT_EXPIRED` | Hiển thị "Bài thi đã kết thúc", không retry |
| 429 | Honor `Retry-After` header |
| 423 `ATTEMPT_SUSPENDED` | Show suspend banner, wait proctor |

---

## XVII. DEPLOYMENT

### 17.1 Kubernetes

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: exam-service, namespace: smartquiz }
spec:
  replicas: 3
  strategy: { type: RollingUpdate, rollingUpdate: { maxSurge: 2, maxUnavailable: 0 } }
  template:
    spec:
      affinity:
        podAntiAffinity:                       # Spread across AZ
          preferredDuringScheduling...:
            - { topologyKey: topology.kubernetes.io/zone, ... }
      containers:
        - name: exam
          image: registry.smartquiz.vn/exam-service:1.0.0
          ports:
            - { name: http, containerPort: 3002 }
            - { name: grpc, containerPort: 4002 }
            - { name: mgmt, containerPort: 9002 }
          resources:
            requests: { cpu: 1,   memory: 1Gi }
            limits:   { cpu: 4,   memory: 4Gi }
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: mgmt }
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: mgmt }
            periodSeconds: 3
          lifecycle:
            preStop:
              exec:
                command: ["sh","-c","sleep 30"]   # Cho WS clients disconnect sạch
```

### 17.2 HPA

```yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata: { name: exam-hpa }
spec:
  minReplicas: 3
  maxReplicas: 30
  metrics:
    - type: Resource
      resource: { name: cpu, target: { type: Utilization, averageUtilization: 70 } }
    - type: Pods
      pods:
        metric: { name: exam_active_ws_connections }
        target: { type: AverageValue, averageValue: 4000 }
  behavior:
    scaleUp:
      policies: [{ type: Percent, value: 50, periodSeconds: 30 }]
    scaleDown:
      stabilizationWindowSeconds: 300       # Giảm chậm để WS không mất đột ngột
```

### 17.3 PDB

```yaml
apiVersion: policy/v1
kind: PodDisruptionBudget
metadata: { name: exam-pdb }
spec:
  minAvailable: 2
  selector: { matchLabels: { app: exam-service } }
```

### 17.4 Ingress sticky

```yaml
annotations:
  nginx.ingress.kubernetes.io/affinity: cookie
  nginx.ingress.kubernetes.io/session-cookie-name: sq-exam-route
  nginx.ingress.kubernetes.io/session-cookie-max-age: "7200"
  nginx.ingress.kubernetes.io/proxy-read-timeout: "3600"  # WS timeout
```

### 17.5 DR

| Scenario | Impact | Plan |
| -------- | ------ | ---- |
| 1 pod crash | Clients mất WS ~5s, auto reconnect | K8s handle |
| Cả zone down | Clients ở zone đó disconnect, reroute sang zone khác | Multi-AZ, session state ở Redis/PG |
| Redis cluster down | Không ghi được đáp án mới | Fail-fast, return 503; sau khi Redis up: client resume qua snapshot từ PG |
| PG primary fail | Không ghi metadata mới; đáp án vẫn ghi Redis | Patroni auto-failover ~15s |
| Kafka down | Grading bị đóng | Đệm local trong producer buffer; khi Kafka up producer resend |
| Datacenter down | Toàn hệ thống | Multi-region K8s với read replica PG cross-region; RTO < 30 min |

---

## XVIII. TESTING STRATEGY

### 18.1 Test pyramid

```
          E2E (10%)       ← Playwright: start-answer-submit happy path
       Integration (30%)  ← Testcontainers: PG + Redis + Kafka + Mongo
    Unit (60%)            ← Domain logic, state machine, graders, IRT
```

### 18.2 Unit test ví dụ

```java
@Test
void multipleChoiceSingleGrader_returns_correct_when_option_matches() {
    var q = TestQuestion.mcSingle("q1", List.of(
        new Option("a", "A", false),
        new Option("b", "B", true)
    ), 5.0);
    var payload = AnswerPayload.of("""{"selected_options":["b"]}""");

    var result = grader.grade(q, payload);

    assertThat(result.correct()).isTrue();
    assertThat(result.earned()).isEqualTo(new Score(5));
}

@Test
void startAttempt_fails_when_exam_closed() {
    var exam = ExamFixture.closed();
    assertThatThrownBy(() -> useCase.start(exam.id(), user))
        .isInstanceOf(ExamNotOpenException.class);
}
```

### 18.3 Integration test ví dụ

```java
@SpringBootTest
@Testcontainers
class SubmitAnswerIdempotencyIT {
    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
    @Container static GenericContainer<?>    redis = new GenericContainer<>("redis:7");

    @Test
    void second_submit_with_same_submission_id_returns_same_result() {
        var attemptId = startAttempt();
        var dto = new AnswerDto(...);
        var sid = UUID.randomUUID();

        mvc.perform(post("/attempts/{id}/answers", attemptId).content(json(dto.withSid(sid))))
           .andExpect(status().isOk());
        mvc.perform(post("/attempts/{id}/answers", attemptId).content(json(dto.withSid(sid))))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.saved").value(true));

        // Chỉ có 1 record trong DB
        assertThat(repo.countBySubmissionId(sid)).isEqualTo(1);
    }
}
```

### 18.4 Chaos testing

Dùng **Chaos Mesh** trên staging:

- Kill 1 pod exam-service trong lúc 100 user đang thi → verify all answers still saved
- Delay Redis 500ms → verify circuit breaker + degraded mode
- Partition Kafka → verify producer buffer không mất message

### 18.5 Test bắt buộc cho release

- [ ] Idempotency `POST /answers` (same submission_id)
- [ ] Timer không thể thao túng từ client
- [ ] Không thể submit answer sau expires_at
- [ ] Suspend khi risk >= 60 từ Cheating Service
- [ ] Auto-expire in_progress attempts qua cron
- [ ] Resume attempt sau pod crash
- [ ] IRT next question logic trên pool 1000 câu (benchmark)
- [ ] Fan-out grading: 1 attempt có 30 câu thì 30 grading.request publish
- [ ] RLS: user ở org A không đọc được exam org B

---

## XIX. MIGRATION & OPERATION

### 19.1 Seed dữ liệu lúc khởi động

Không cần. Exam Service stateless, data đến từ PG/Mongo/Redis (đã seed ở DB layer).

### 19.2 Backup & restore

Không cần backup riêng; dữ liệu nằm trong PG + Mongo + Redis (đã có backup plan ở `database.md` mục 8.4).

### 19.3 Runbook (on-call)

| Triệu chứng | Kiểm tra | Fix |
| ----------- | --------- | --- |
| Alert `AnswerLatencyHigh` | Grafana: phân tách theo endpoint | Nếu Redis chậm: kiểm tra Redis cluster, scale; nếu gRPC Question chậm: check cache hit rate |
| Alert `RedisDown` | Redis cluster status | Failover, sau đó check AOF log để recover data |
| Học sinh report "đáp án không lưu" | Query `attempt_answers` by attempt_id | So với Redis `answers:{id}` → có đâu? Nếu chỉ Redis: tăng tốc flush consumer |
| Submit 500 error | Log trace_id → Jaeger | Check DB lock, Kafka producer |
| WS mass disconnect | Pod CPU/memory, ingress status | Scale, hoặc đổi sticky affinity |

### 19.4 Feature flags (dev → prod gradual rollout)

Dùng **LaunchDarkly** hoặc self-host **Unleash**:

- `exam.adaptive.enabled`: bật adaptive mode
- `exam.new_grader_v2.enabled`: rollout grader mới 10% → 50% → 100%
- `exam.ws.use_redis_pubsub`: toggle giữa broadcast cách cũ và mới

---

## XX. ROADMAP

### 20.1 MVP (tháng 4-5/2026)

- [x] Schema PG + Mongo
- [ ] Create/publish exam
- [ ] Start attempt + submit answer + submit final
- [ ] Auto-grade các type đơn giản (choice, true_false, ordering, matching)
- [ ] WebSocket timer sync
- [ ] Redis session store + idempotency

### 20.2 Phase 2 (Q3/2026)

- [ ] Adaptive exam (IRT)
- [ ] AI grading integration (essay, code)
- [ ] Suspend flow với Cheating Service
- [ ] Analytics dashboard cho giáo viên
- [ ] Chứng chỉ tự động phát hành

### 20.3 Phase 3 (Q4/2026)

- [ ] Exam chấm theo rubric với human review queue
- [ ] Batch grading (upload đáp án scan OMR)
- [ ] Regrade endpoint + bulk update điểm
- [ ] Exam templates + versioning

### 20.4 Open questions

1. **Adaptive + section có cấu hình cố định** — xử lý thế nào? → Phase 2: adaptive pool = câu từ tất cả section đã config; giữ section order fixed ngoài adaptive
2. **Student muốn xem lại bài (`allow_review`)** — hiển thị chính xác câu hỏi đã xáo, hay thứ tự gốc? → Đã xáo (theo `question_order` lưu trong attempt)
3. **Multi-device thi cùng attempt?** → Cấm: UNIQUE INDEX active attempt + device fingerprint match khi reconnect
4. **Offline mode?** → Không MVP. Phase 3 có thể support download câu hỏi + sync sau (yêu cầu cryptographic integrity)
5. **Proctoring tích hợp sâu hay service riêng?** → Service riêng, communicate qua Kafka + gRPC
6. **Grace period khi browser crash sát submit?** → `grace_period_minutes` đã có trong config; cộng vào `expires_at` lúc tạo attempt

---

_Tài liệu thuộc Hệ Thống Thi Trực Tuyến Thông Minh — Exam Service Design v1.0 — Tháng 4/2026._
