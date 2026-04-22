# AUTH SERVICE - THIẾT KẾ CHI TIẾT

**Hệ Thống Thi Trực Tuyến Thông Minh** | Version 1.5 | Tháng 4/2026

Tài liệu này mở rộng mục "Auth Service" trong `design.md`, mô tả chi tiết ở mức đủ để triển khai code.

**Tài liệu nguồn ràng buộc (phải đọc trước khi thay đổi):**
- `CLAUDE.md` §2 (stack lock), §3 (NFR lock), §9 (prereqs scaffold)
- `docs/adr/ADR-001-sla-rpo-outbox.md` (SLA 99.9% platform / 99.95% Auth, RPO ≤5s, outbox pattern)
- `database/postgresql/schema.sql` §2a (RBAC), §8 (oauth/refresh/password history), §13 (outbox/processed_events)
- `shared-contracts/avro/auth/*.avsc` (event schema — BACKWARD compat)

**Changelog v1.5 (2026-04-22) — API contract best practices:**
- §12.0 expand thành full "API conventions": content-type, naming (snake_case/kebab), status code (200/201/202/204/400/401/403/409/410/422/429/503), RFC 7807 error với `errors[]` field-level (422), **Idempotency-Key** header, **rate limit headers** (X-RateLimit-*, Retry-After), **cursor-based pagination**, standard request/response headers, no envelope rule
- §12.7 template: login + refresh với **JSON Schema đầy đủ** (OpenAPI 3.1 style) làm reference cho BE/FE/QA codegen + error matrix per-endpoint
- §15.2: thêm error codes `AUTH_MALFORMED_REQUEST` (400 syntax), `AUTH_VALIDATION_FAILED` (422 semantic), `IDEMPOTENCY_KEY_REUSE_DIFFERENT_BODY` (409); rate limit body schema chuẩn

**Changelog v1.4 (2026-04-22) — silent-landmine fixes:**
- §12.0 + §13.1: rule shorthand vs literal — `Path=` cookie, `redirect_uri` OAuth phải literal `/api/v1/` (shorthand gây cookie mismatch / OAuth redirect_uri error)
- §6.0: register flow sync TX (domain + outbox) + async email; thêm 2 event `auth.registration.attempt_on_existing.v1` và `auth.verify_email.resent.v1` trong §11.1
- §11.2: `AuthOutboxPublisher` propagation `MANDATORY` (fail-fast) thay vì REQUIRED; UseCase bắt buộc `@Transactional(REQUIRED)`; unit test enforcement rule

**Changelog v1.3 (2026-04-22) — post multi-agent review batch 1:**
- §1.2 + §16.3: xoá claim "Virtual Threads cho Argon2" (sai — Argon2 CPU-bound); thêm `ThreadPoolTaskExecutor` bounded cho hash pool, VT chỉ cho I/O
- §3.3: thêm `SecurityConfig` snippet `setAuthoritiesClaimName("authorities")` + `setAuthorityPrefix("")` — tránh silent deny bug với prefix mặc định `SCOPE_`
- §5.1.1: **2-tier sudo** — tier A time-based (`auth_time` 5m) cho ops thường + **tier B per-action step-up token** bound request body hash cho highest-risk (impersonate, jwks rotate)
- §5.1.1: fix `SudoCheck` dùng `getClaimAsInstant` thay raw cast `long` (ClassCastException)
- §5.2.1: algorithm hoàn chỉnh — `SELECT FOR UPDATE` explicit, `pg_advisory_xact_lock(family_id)` chống deadlock, double-check pattern chống race attacker + victim
- §7.2: MFA login response contract rõ — FE discriminate bằng key `mfa_challenge`, KHÔNG dùng HTTP status/error code cho challenge
- §10.3: xoá JWT example duplicate; reference §5.1 làm nguồn truth; bổ sung ghi chú `authorities` size cap 50
- §11.2: relayer rewrite — async callback, per-row error isolation, batch budget 3s, `SmartLifecycle` cho graceful SIGTERM
- §4.*: sửa schema.sql line refs (83, 157, 176, 194, 503, 513, 529, 542)
- §15.2: thêm error codes `AUTH_STEPUP_REQUIRED`, `AUTH_STEPUP_INVALID`

**Changelog v1.2 (2026-04-22) — production hardening:**
- §12.0 API versioning convention (`/api/v1/` prefix, Sunset header cho breaking changes)
- §5.2.1 Refresh token stolen detection via **family tree** (RFC 6819) + schema delta `family_id`, `rotated_to_id`
- §5.1 JWT bổ sung claims: `nbf` (clock skew), `auth_time` (sudo mode), `dvh` (device-hint hash) — §5.1.2
- §5.1.1 **Sudo mode** — endpoint nhạy cảm yêu cầu recent-auth ≤ 5 phút; §12.5 reauth endpoint
- §8.3 rewrite OAuth linking — email-match verification + confirm email đến provider address chống takeover
- §6.0 Register flow chống email enumeration — luôn 202, side effect qua email; timing-equal hash giả
- Bổ sung error codes: `AUTH_SUDO_REQUIRED`, `AUTH_DEVICE_MISMATCH`, `AUTH_OAUTH_ALREADY_LINKED`, `AUTH_OAUTH_LAST_METHOD`
- **§IV rewrite — chống drift**: bỏ DDL duplicate với `schema.sql`, chỉ giữ invariant + business rule
- **§18.1 consolidate "Schema delta v1.2"** thành single block — mọi schema change sống ở 1 nơi
- **§XI nguồn truth topic**: reference `shared-contracts/avro/TOPICS.md`, CI check đồng bộ

**Changelog v1.1 (2026-04-22):**
- Align stack theo CLAUDE.md §2 (Spring Boot 3.3+, Flyway, Apicurio+Avro, Spotless/Checkstyle/JaCoCo, Loki/OTLP)
- Section XI rewrite: Transactional Outbox cho event critical (ADR-001)
- Bổ sung section 2.3 build quality gate
- Bổ sung observability tracing OTel + MDC masking filter
- Bổ sung integration test outbox + coverage target JaCoCo
- Roadmap 18.1 liệt kê gate prereqs theo CLAUDE.md §9

---

## I. TỔNG QUAN

### 1.1 Vai trò

Auth Service là **cổng danh tính duy nhất** (single source of identity) cho toàn hệ thống. Các service khác không tự quản lý user/password — chỉ **xác minh token** do Auth Service cấp thông qua JWKS.

| Trách nhiệm | Không thuộc phạm vi |
| ----------- | ------------------- |
| Đăng ký, đăng nhập, đăng xuất | Quản lý hồ sơ người dùng chi tiết (ảnh, địa chỉ — thuộc User Profile Service sau này) |
| Xác thực 2 yếu tố (TOTP) | Gửi email/SMS (uỷ quyền Notification Service) |
| OAuth2/SSO (Google, Microsoft, GitHub) | Thanh toán, gói dịch vụ |
| RBAC permission-based (4 system role mặc định + custom role per org) | Phân quyền ở mức cột (row-level nằm ở từng service domain) |
| Cấp JWT access + refresh token | Business logic bài thi / câu hỏi |
| Thu hồi token tức thì | Log audit chi tiết (chỉ publish event, consumer là Analytics) |
| Rate limit chống brute-force | Anti-bot (Turnstile / reCAPTCHA do Gateway xử lý) |

### 1.2 Stack công nghệ

> Bản này đã lock theo `CLAUDE.md §2`. Đổi công nghệ phải viết ADR mới — đừng tự ý
> thay trong design doc.

| Thành phần | Chọn | Lý do |
| ---------- | ---- | ----- |
| Runtime | **Java 21 LTS + Spring Boot 3.3+** | Thống nhất với các service Java khác (CLAUDE.md §2). `spring.threads.virtual.enabled=true` cho I/O path (JDBC, Redis, OAuth WebClient). **KHÔNG áp dụng cho Argon2 hash pool** — Argon2 CPU-bound + memory-bound, virtual threads pin carrier thread trọn ~250ms như platform thread, không có lợi gì (có thể còn hại nếu carrier pool nhỏ). Hash pool dùng bounded `ThreadPoolTaskExecutor` (§16.3). |
| Framework | Spring Security 6 + Spring Web MVC | OAuth2 Resource Server, filter chain trưởng thành |
| ORM | Spring Data JPA + Hibernate 6 | Truy cập PostgreSQL |
| Migration | **Flyway** (`src/main/resources/db/migration`) | Versioned SQL, chạy cùng service startup; naming `V{epoch}__{desc}.sql` |
| Redis client | Lettuce (async, non-blocking) | Tích hợp Spring Data Redis |
| JWT lib | `com.nimbusds:nimbus-jose-jwt` | Ký/verify RS256, JWKS export |
| Password hash | `de.mkammerer:argon2-jvm` | Wrapper Argon2id chuẩn |
| TOTP | `dev.samstevens.totp:totp` | RFC 6238 |
| HTTP client OAuth | Spring `WebClient` | Non-blocking, retry, metrics |
| Event bus | Spring Kafka + **Transactional Outbox** (ADR-001) | Đảm bảo at-least-once + idempotent, không phát trực tiếp từ service thread |
| Schema contracts | **Apicurio Schema Registry + Avro**, BACKWARD compat (CLAUDE.md §2) | Event versioning an toàn giữa producer/consumer |
| Observability | **Micrometer → Prometheus**, **OpenTelemetry OTLP** (traces+metrics), **Loki** (log push) | Stack chuẩn repo (CLAUDE.md §2) |
| Logging | SLF4J + Logback JSON encoder (logstash-logback-encoder) + **MDC** (`trace_id`, `user_id`, `org_id`) | Format AI-friendly, tracing Claude Code debug được |
| Secret store | HashiCorp Vault (Kubernetes auth) — MVP: K8s Secret (xem §18.5 OQ-4) | JWT signing key, AES key, OAuth client secret |
| Build | **Gradle** (wrapper pinned) + **Spotless** (google-java-format) + **Checkstyle** + **JaCoCo** | Quality gate CI bắt buộc |
| Test | JUnit 5 + AssertJ + **Testcontainers** (PG 16, Redis 7, Kafka, Apicurio) + WireMock (OAuth) | CLAUDE.md §2 mandate; integration hit container thật |

### 1.3 Cổng & giao thức

| Giao thức | Port | Mục đích |
| --------- | ---- | -------- |
| HTTP/1.1 + HTTP/2 (REST) | `3001` | Client-facing qua API Gateway |
| gRPC | `4001` | Internal service-to-service (ValidateToken, GetUser) |
| JWKS public endpoint | `3001/.well-known/jwks.json` | Cho các service verify JWT offline |
| OpenAPI spec | `3001/v3/api-docs` (JSON) · `3001/swagger-ui.html` (UI) | Contract FE + các service consumer (CLAUDE.md §9 — gate trước khi FE code login) |
| Actuator (health, metrics) | `9001/actuator/*` | Prometheus scraping + K8s probes |

---

## II. KIẾN TRÚC BÊN TRONG

### 2.1 Sơ đồ lớp (layered architecture)

```
┌────────────────────────────────────────────────────────────┐
│  Controllers (REST + gRPC)                                 │
│  ─ AuthController, MfaController, OAuthController,         │
│    AccountController, AdminUserController                  │
├────────────────────────────────────────────────────────────┤
│  Application Services (use cases)                          │
│  ─ RegisterUserUseCase, LoginUseCase, RefreshTokenUseCase, │
│    SetupMfaUseCase, OAuthCallbackUseCase, RevokeUseCase    │
├────────────────────────────────────────────────────────────┤
│  Domain Services                                           │
│  ─ PasswordHasher, TokenIssuer, TotpGenerator,             │
│    RateLimiter, RolePolicyEvaluator                        │
├────────────────────────────────────────────────────────────┤
│  Repositories                                              │
│  ─ UserRepo, RefreshTokenRepo (PG)                         │
│  ─ SessionCache, TokenBlacklist, LoginAttemptCounter (Redis)│
├────────────────────────────────────────────────────────────┤
│  Integrations                                              │
│  ─ GoogleOAuthClient, MicrosoftOAuthClient, GitHubClient   │
│  ─ OutboxPublisher (TX-bound), KafkaRelayer (leader-elect) │
│  ─ VaultSecretLoader, NotificationGrpc                     │
└────────────────────────────────────────────────────────────┘
```

### 2.2 Module Gradle multi-project

Nằm trong root `services/auth/` (xem CLAUDE.md §4). Gradle wrapper pin (`gradle-wrapper.properties`),
version catalog dùng chung ở `/gradle/libs.versions.toml`.

```
services/auth/
├── settings.gradle.kts        # include: app, api-grpc, domain-test-fixtures
├── build.gradle.kts           # root — convention plugins (spotless, checkstyle, jacoco)
├── gradle/                    # wrapper (pinned)
├── api-grpc/                  # .proto + generated stubs (publish → mavenLocal để service khác consume)
│   ├── src/main/proto/
│   └── build.gradle.kts
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/java/vn/smartquiz/auth/
│       │   ├── AuthServiceApplication.java
│       │   ├── config/        # SecurityConfig, RedisConfig, KafkaConfig, JwkConfig, OutboxConfig
│       │   ├── controller/    # @RestController + @ControllerAdvice (RFC 7807)
│       │   ├── grpc/          # gRPC server
│       │   ├── application/   # UseCase (LoginUseCase, ...)
│       │   ├── domain/
│       │   │   ├── user/      # User, Role, OrgMembership (aggregate)
│       │   │   ├── token/     # AccessToken, RefreshToken (value)
│       │   │   └── mfa/       # MfaSecret, BackupCode
│       │   ├── infrastructure/
│       │   │   ├── persistence/   # JPA entities + repo
│       │   │   ├── redis/
│       │   │   ├── oauth/         # Google/Microsoft/GitHub client
│       │   │   ├── kafka/         # Outbox relayer + Avro serde (Apicurio)
│       │   │   └── vault/
│       │   └── common/        # Exception, ErrorCode, MdcFilter
│       ├── main/resources/
│       │   ├── application.yml
│       │   ├── logback-spring.xml       # JSON encoder + mask filter (mật khẩu, token, mfa_secret)
│       │   ├── db/migration/            # Flyway V{epoch}__*.sql — chỉ patch delta cho service này
│       │   └── static/openapi.yaml      # OpenAPI 3.1 spec
│       └── test/java/...
└── README.md
```

**Rule Flyway:** schema master ở `database/postgresql/schema.sql` (nhiều service share).
Auth Service chỉ commit migration delta riêng của mình vào `app/src/main/resources/db/migration`
với prefix `V{yyyymmddhhmm}__auth_*.sql`. Không được đổi migration đã release (immutable).

### 2.3 Build quality gate

| Tool | Cấu hình | Gate fail khi |
| ---- | -------- | ------------- |
| Spotless | `googleJavaFormat('1.19.2')` + `removeUnusedImports()` + `trimTrailingWhitespace()` | Format lệch → CI fail, gợi ý `./gradlew spotlessApply` |
| Checkstyle | `config/checkstyle/checkstyle.xml` (Google style + project override) | Bất kỳ error → CI fail |
| JaCoCo | Report HTML + XML → Codecov | `domain/` line coverage < **80%**, `application/` < **70%** |
| OWASP dependency-check | `./gradlew dependencyCheckAggregate` trong CI nightly | CVSS ≥ 7.0 trên compile deps |
| Error Prone | Google static analysis — bật cho `main` | Any new warning → review block |

---

## III. DOMAIN MODEL

### 3.1 Aggregate: `User`

```java
public class User {
    private UUID id;
    private Email email;                    // Value object, unique
    private Username username;              // Optional, unique nếu có
    private FullName fullName;
    private PasswordHash passwordHash;      // NULL nếu chỉ dùng OAuth
    private MfaSecret mfaSecret;            // NULL nếu chưa bật MFA; lưu mã hóa AES-256-GCM
    private boolean mfaEnabled;
    private boolean emailVerified;
    private Locale locale;
    private ZoneId timezone;
    private Instant lastLoginAt;
    private InetAddress lastLoginIp;
    private int failedLoginCount;
    private Instant lockedUntil;
    private boolean active;
    private Set<OrgMembership> memberships; // load lazy qua repo
    private List<OAuthLink> oauthLinks;
    private Instant createdAt;
    private Instant updatedAt;

    // Domain operations
    public void recordFailedLogin(Clock clock, LockoutPolicy policy) { ... }
    public void resetFailedCounter() { this.failedLoginCount = 0; this.lockedUntil = null; }
    public boolean isLocked(Clock clock) { ... }
    public void enableMfa(MfaSecret secret) { ... }
    public void disableMfa() { ... }
    public void changePassword(RawPassword raw, PasswordHasher hasher, PasswordPolicy policy) { ... }
}
```

### 3.2 Aggregate: `RefreshToken`

```java
public class RefreshToken {
    private UUID id;
    private UUID userId;
    private UUID familyId;             // cùng session login → cùng family; xoay tạo row mới giữ nguyên familyId
    private String tokenHash;          // SHA-256, không lưu plaintext
    private String deviceFingerprint;
    private String userAgent;
    private InetAddress ipAddress;
    private Instant expiresAt;
    private boolean revoked;
    private Instant revokedAt;
    private Instant createdAt;
    private UUID rotatedFromId;        // link đến token trước khi xoay (parent trong chain)
    private UUID rotatedToId;          // link đến token xoay ra từ token này (child) — null nếu chưa xoay
}
```

Chain rotation: `login → token_A (family=F) → refresh → token_B (family=F, from=A, A.to=B)
→ refresh → token_C (family=F, from=B, B.to=C) ...`. Stolen detection dựa vào family, xem §5.2.1.

> **Schema**: `family_id`, `rotated_from_id`, `rotated_to_id` đã merge vào `schema.sql:194-214`
> (2026-04-22).

### 3.3 Value object: `Role` + `Permission` (dynamic RBAC)

> **LƯU Ý QUAN TRỌNG:** Hệ thống **KHÔNG hardcode role** dưới dạng Java enum. Role và permission được lưu ở bảng `roles`, `permissions`, `role_permissions` (xem `database/postgresql/schema.sql` mục 2a).
>
> - **4 role mặc định** (`student`, `instructor`, `admin`, `proctor`) là **system roles** (`is_system=true`, `org_id=NULL`) — seed 1 lần, không xoá được.
> - **Custom role** per org (Phase 2): org admin có thể tạo role mới (vd `custom.grading_assistant`) và gán permissions.
> - **Service check bằng permission code**, không bằng role name → code không phụ thuộc vào tên role.

```java
// Không dùng enum — load từ DB
public record Role(
    UUID id,
    UUID orgId,              // null = system role
    String code,             // "student", "custom.grading_assistant"
    String name,
    boolean isSystem,
    Set<Permission> permissions   // preload eager
) { }

public record Permission(
    UUID id,
    String code,             // "exam.update.own", "question.approve"
    String resource,
    String action,
    String scope             // "own" | "org" | "platform"
) { }

public record OrgMembership(UUID orgId, UUID roleId, String roleCode, Instant joinedAt, boolean active) { }
```

**Enforcement pattern:**

```java
// ❌ KHÔNG làm thế này
@PreAuthorize("hasRole('INSTRUCTOR')")
public void publishExam(UUID id) { ... }

// ✅ Làm thế này — permission-based
@PreAuthorize("hasAuthority('exam.publish') and @examPolicy.isOwner(authentication, #id)")
public void publishExam(UUID id) { ... }
```

Spring Security cấu hình `JwtAuthenticationConverter` extract permission từ claim `authorities` vào `GrantedAuthority`. JWT có cả role (debug/audit) và permission list (authorization decision).

> **⚠️ Scaffolding gotcha**: default `JwtGrantedAuthoritiesConverter` đọc claim `scope`/`scp`
> và tự prefix `SCOPE_` vào từng value. Nếu dùng nguyên bản, `hasAuthority('exam.publish')`
> sẽ **không bao giờ match** (silent fail — authz decision đều deny, dễ phát hiện khi test,
> nhưng tốn thời gian). Phải config tường minh:

```java
// SecurityConfig.java
@Bean
JwtAuthenticationConverter jwtAuthConverter() {
    var authoritiesConverter = new JwtGrantedAuthoritiesConverter();
    authoritiesConverter.setAuthoritiesClaimName("authorities");  // không phải scope/scp
    authoritiesConverter.setAuthorityPrefix("");                  // permission code dùng raw, không SCOPE_
    var converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
    converter.setPrincipalClaimName("sub");
    return converter;
}

@Bean
SecurityFilterChain api(HttpSecurity http, JwtAuthenticationConverter conv) throws Exception {
    return http
        .authorizeHttpRequests(a -> a
            .requestMatchers("/.well-known/**", "/actuator/health/**").permitAll()
            .requestMatchers("/api/v1/auth/login", "/api/v1/auth/register",
                             "/api/v1/auth/refresh", "/api/v1/auth/password/forgot",
                             "/api/v1/auth/password/reset", "/api/v1/auth/oauth/**").permitAll()
            .anyRequest().authenticated())
        .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(conv)))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .csrf(CsrfConfigurer::disable)  // stateless; cookie mode override — xem §13.3
        .build();
}
```

### 3.4 Ma trận quyền mặc định (4 system roles)

Bảng dưới **không phải enforcement** — chỉ là **cấu hình seed** của `role_permissions` với 4 role mặc định. Org admin có thể thay đổi grant hoặc tạo role mới.

Permission code dùng cấu trúc `{resource}.{action}[.{scope}]`:

| Permission code | `student` | `instructor` | `admin` | `proctor` |
| --------------- | --------- | ------------ | ------- | --------- |
| `exam.read` | — | ✔ | ✔ | ✔ |
| `exam.create` | ✖ | ✔ | ✔ | ✖ |
| `exam.update.own` | ✖ | ✔ | ✔ | ✖ |
| `exam.update.any` | ✖ | ✖ | ✔ | ✖ |
| `exam.publish` | ✖ | ✔ | ✔ | ✖ |
| `exam.enroll.students` | ✖ | ✔ | ✔ | ✖ |
| `exam.analytics` | ✖ | ✔ | ✔ | ✔ |
| `user.read.org` | ✖ | ✔ | ✔ | ✔ |
| `user.invite` | ✖ | ✖ | ✔ | ✖ |
| `user.update.role` | ✖ | ✖ | ✔ | ✖ |
| `attempt.start` / `.submit` / `.read.own` | ✔ | — | — | — |
| `attempt.read.org` | ✖ | ✔ | ✔ | ✔ |
| `attempt.grade` | ✖ | ✔ | ✔ | ✖ |
| `attempt.suspend` / `.resume` / `.terminate` | ✖ | ✖ | ✔ | ✔ |
| `cheat.review` / `.decide` / `.video.view` | ✖ | ✔ | ✔ | ✔ |
| `cheat.appeal.submit` | ✔ | ✖ | ✖ | ✖ |
| `cheat.appeal.resolve` | ✖ | ✔ | ✔ | ✖ |
| `certificate.read.own` | ✔ | ✖ | ✖ | ✖ |
| `certificate.read.org` / `.revoke` | ✖ | ✔ | ✔ | ✖ |
| `question.create` / `.update.own` / `.approve` | ✖ | ✔ | ✔ | ✖ |
| `question.report` | ✔ | ✔ | ✔ | ✔ |
| `ai.generate` / `.grade.essay` / `.embed` | ✖ | ✔ | ✔ | ✖ |
| `ai.budget.manage` / `ai.cost.view` | ✖ | ✖ | ✔ | ✖ |
| `analytics.self` | ✔ | ✔ | ✔ | ✔ |
| `analytics.exam` / `.export` | ✖ | ✔ | ✔ | ✔ |
| `analytics.org` | ✖ | ✖ | ✔ | ✖ |
| `user.impersonate` / `ai.prompt.manage` / `cheat.config.weights` / `analytics.experiment` | **platform-scope** — chỉ platform_admin | | | |

> **Platform admin** không phải role trong `user_organizations`. Được định nghĩa riêng qua biến env `PLATFORM_ADMIN_EMAILS` (MVP) hoặc bảng `platform_admins` (Phase 2). JWT có thêm claim `platform_role: "super_admin"` — nếu có → bypass org scope cho các permission `scope=platform`.

### 3.5 Cách org admin tạo custom role (Phase 2)

```
POST /admin/roles
{
  "code": "custom.grading_assistant",
  "name": "Trợ giảng chấm bài",
  "description": "Chấm essay/short_answer không tạo bài thi",
  "permissions": ["user.read.org","question.read.org","exam.read",
                   "attempt.read.org","attempt.grade","ai.grade.essay"]
}
```

Validate:
- `code` không trùng với system role
- Permissions phải thuộc catalog (không tự phát sinh)
- `is_system=false`, `org_id=<current>`

---

## IV. DATA MODEL — invariants & business rules

> **⚠️ DDL là nguồn truth duy nhất ở `database/postgresql/schema.sql` §10 "Auth mở rộng".**
> Section này **KHÔNG copy cột** — chỉ mô tả invariant, retention, policy, usage pattern
> mà DDL không thể diễn đạt. Nếu bạn cần thêm/đổi cột: sửa `schema.sql` trước, rồi update
> Flyway migration delta (§18.1), rồi mới động tới prose ở đây.
>
> **Schema delta chưa merge vào `schema.sql`** (v1.2, do fix #2 + #5): xem §18.1 checklist —
> đừng copy-paste vào đây.

Auth Service thao tác các bảng sau (đường dẫn `schema.sql` tính theo section header, line có thể
dịch khi schema edit):

### 4.1 Core identity (schema §3, §5)

`users`, `oauth_providers`, `refresh_tokens`, `user_organizations` — DDL ở `schema.sql:83` (users), `:157` (user_organizations), `:176` (oauth_providers), `:194` (refresh_tokens). Line numbers có thể dịch khi schema edit — nếu mismatch, `grep -n '^CREATE TABLE <name>' schema.sql`.

**Invariants business-level (không thấy từ DDL):**
- `users.password_hash` NULL = user OAuth-only. Nếu sau này set password → vẫn giữ OAuth links.
- `refresh_tokens` row soft-revoke (`revoked=true`), không DELETE. Giữ 30 ngày để audit / stolen-chain
  investigation rồi batch job xóa.
- `refresh_tokens` xoay = INSERT row mới + UPDATE row cũ (revoke + link `rotated_to_id`).
  Family chain dùng cho stolen detection — xem §5.2.1.
- `oauth_providers` unique `(provider, provider_user_id)`. Attacker không attach cùng Google
  account vào 2 SmartQuiz user khác nhau.

### 4.2 `password_history` (schema §10)

DDL: `schema.sql:503`. Business rule:
- **Policy**: cấm dùng lại **5 mật khẩu gần nhất**. Insert sau mỗi `password_hash` thay đổi
  thành công; check `SELECT password_hash FROM password_history WHERE user_id=? ORDER BY changed_at DESC LIMIT 5`
  trước khi accept password mới.
- **Retention**: giữ mãi (giá trị thấp — mỗi user ~1-5 row/năm). Xóa khi user bị hard-delete (GDPR).

### 4.3 `mfa_backup_codes` (schema §10)

DDL: `schema.sql:513`. Business rule:
- **10 code/user**, mỗi code 12 ký tự alphanumeric (format `abcd-efgh-ijkl` hyphenated).
- `code_hash = Argon2id(code)` — không lưu plaintext. Dùng Argon2id (không SHA-256) vì
  backup code = secret value cao, nếu DB leak thì SHA-256 brute-force được; Argon2id chậm
  có chủ đích. Verify cost ~250ms chấp nhận được (MFA step rare).
- **Mỗi code dùng 1 lần** (set `used_at` khi verify success). Không cho xoay code đã dùng.
- `POST /mfa/backup/regenerate` = DELETE all + INSERT 10 mới (không merge).
- Warning user khi còn < 3 code chưa dùng.

### 4.4 `email_verification_tokens` (schema §10)

DDL: `schema.sql:529`. Business rule:
- `purpose` enum values hiện dùng (enforce ở tầng app, DDL là VARCHAR):
  - `verify_email` — TTL 24h, sau khi register (§6.0)
  - `reset_password` — TTL 1h (§6.3)
  - `link_oauth` — TTL 15 phút (§8.3)
  - `change_email` — TTL 24h (OQ-5 §18.5)
- `token_hash = SHA-256(token_32_bytes_random)` — không lưu plaintext.
- Mỗi token dùng 1 lần (set `used_at`). Duplicate submit → `410 Gone`.
- Cleanup job mỗi đêm xóa row `expires_at < NOW() - interval '7 days'`.

### 4.5 `audit_log_auth` (schema §10)

DDL: `schema.sql:542` (partition parent) + partitions `y2026m04 → y2026m12` (schema `§10`).

**Business rule:**
- **Chỉ ghi 7 event**: `login_success`, `login_failed`, `password_changed`, `mfa_enabled`,
  `mfa_disabled`, `role_changed`, `account_locked`. Các event khác đi log stdout → Loki.
- **Tại sao ghi DB thay vì chỉ Loki?** Loki retention 14 ngày không đủ cho compliance audit
  (thường ≥ 1 năm). `audit_log_auth` là nguồn truth cho đơn kiện / investigation.
- **Partition theo tháng**, **giữ 12 tháng** — partition cũ detach + dump vào S3 Glacier cho
  > 1 năm (cron job).
- Insert **ngoài transaction** của UseCase chính (bất-đồng-bộ qua outbox — tránh chậm login flow).
  Event `auth.audit.*.v1` consume bởi chính Auth Service writer.

---

## V. TOKEN STRATEGY

### 5.1 Access Token — JWT RS256

**Vì sao RS256 (asymmetric), không HS256?**
HS256 buộc mọi service cần secret để verify → mỗi lần rotate phải đồng bộ đến 7 service. RS256: các service chỉ cần public key qua JWKS → xoay private key không ảnh hưởng consumer.

**Claims:**

```json
{
  "iss": "https://auth.smartquiz.vn",
  "aud": ["smartquiz-api"],
  "sub": "a0000000-0000-0000-0000-000000000004",
  "iat": 1713420000,
  "nbf": 1713419940,
  "exp": 1713420900,
  "auth_time": 1713418800,
  "jti": "d7e4c2a1-...",
  "email": "hs.le@hust.edu.vn",
  "email_verified": true,
  "org_id": "11111111-1111-1111-1111-111111111111",
  "orgs": [
    { "id": "11111111-...", "role_code": "student", "role_id": "70000000-...-001" }
  ],
  "authorities": [
    "attempt.start","attempt.submit","attempt.read.own",
    "certificate.read.own","cheat.appeal.submit",
    "question.report","analytics.self"
  ],
  "platform_role": null,
  "mfa_passed": true,
  "dvh": "b7a1c3...",
  "token_type": "access"
}
```

- `iat` — issued at; `nbf = iat - 60` tolerate clock skew giữa pods 60s
- `exp - iat = 900` (15 phút)
- `auth_time` — timestamp password/MFA login gần nhất (KHÔNG reset khi refresh). Dùng cho **sudo mode** (§5.1.1)
- `org_id` là org active tại thời điểm phát hành (user có thể switch bằng endpoint `/api/v1/auth/switch-org` → phát token mới)
- `mfa_passed` = true nếu user đã pass MFA trong session; cho phép service nhạy cảm (Exam) từ chối nếu `mfa_passed=false` ở các thao tác quan trọng
- `dvh` — device-hint hash (§5.1.2)

### 5.1.1 Sudo mode — `auth_time` + recent-auth requirement

**Vấn đề**: admin login đầu ngày → 7 ngày sau session vẫn hoạt động → nếu laptop admin bị
chiếm quyền ngắn (lab, ATM session), attacker có thể `/api/v1/admin/users/{id}/role` promote mình
thành admin. Bản thân token 15 phút không giúp — attacker có access token trong tay cũng vẫn
gọi được.

**Giải pháp**: các endpoint nhạy cảm yêu cầu **"recent auth"** — user phải nhập lại password
(+ MFA nếu có) trong N phút qua.

```java
@PreAuthorize("hasAuthority('user.update.role') and @sudoCheck.recentAuth(authentication, 300)")
@PatchMapping("/api/v1/admin/users/{id}/role")
public void updateRole(@PathVariable UUID id, @RequestBody RoleDto dto) { ... }

// Bean
@Component
class SudoCheck {
    public boolean recentAuth(Authentication auth, int maxAgeSec) {
        if (!(auth.getPrincipal() instanceof Jwt jwt)) return false;

        // getClaimAsInstant handles Integer/Long/Date claim type safely — raw
        // (long) cast throws ClassCastException khi Nimbus deserialize claim
        // nhỏ thành Integer.
        Instant authTime = jwt.getClaimAsInstant("auth_time");
        if (authTime == null) return false;   // token cũ không có claim → phải reauth

        return Duration.between(authTime, Instant.now()).getSeconds() <= maxAgeSec;
    }
}
```

Nếu fail → `403 AUTH_SUDO_REQUIRED` kèm body `{ "reauth_url": "/api/v1/auth/reauth" }`.
FE mở modal nhập password + MFA → `POST /api/v1/auth/reauth` → Auth phát token mới với
`auth_time = now` → FE retry action.

**2 tier sudo** — khác nhau về bypass resistance:

**Tier A — time-based sudo** (threshold 5 phút, `auth_time` claim): cho **phần lớn** ops nhạy cảm.
Dễ dùng cho admin workflow nhiều step. Trade-off: nếu attacker steal access token trong 5 phút
ngay sau khi victim sudo, attacker thừa hưởng elevated state.

- `/api/v1/admin/users/{id}/role`, `.../lock`, `.../unlock`, `.../delete`
- `/api/v1/admin/users/{id}/mfa/disable`
- `/api/v1/auth/mfa/disable` (user tự disable MFA)
- `/api/v1/auth/password/change`

**Tier B — per-action step-up token** (challenge-response, chống token-theft-post-elevation):
cho **highest-risk** ops. Mỗi action phát riêng 1 token bound với **body hash** — attacker
steal token general không replay được trên action cụ thể.

- `/api/v1/admin/impersonate`
- `/api/v1/admin/jwks/rotate`
- `/api/v1/admin/platform_admins/*` (bootstrap platform_admin table — §18.5)

**Flow tier B:**

```text
1. FE: POST /api/v1/auth/step-up/init
       body: { action: "admin.impersonate",
               body_hash: SHA256(canonicalize({target_user_id: "...", duration_minutes: 15})),
               audience: "/api/v1/admin/impersonate" }
       response 200: { challenge_token: "<short-lived JWT 60s, claims: {action, body_hash, aud, jti}>" }
       (backend verify user đã pass password + MFA trong 60s qua trước khi phát)

2. FE: POST /api/v1/admin/impersonate
       header: X-Step-Up-Token: <challenge_token>
       body: { target_user_id: "...", duration_minutes: 15 }
       → Server verify:
         - challenge_token hợp lệ + chưa hết hạn + jti chưa dùng (Redis dedupe TTL 60s)
         - claims.aud == request path
         - claims.body_hash == SHA256(canonicalize(request.body))
         - claims.action == "admin.impersonate"
       → Nếu pass: thực thi + mark jti used
       → Nếu fail: 403 AUTH_STEPUP_INVALID
```

Attacker bypass: phải steal challenge_token **trong 60s** + điều khiển được body_hash chính xác
(body bị attacker chọn) + aud khớp endpoint. Thêm 2 class constraint vs tier A.

Thêm error code: `AUTH_STEPUP_REQUIRED` (400) + `AUTH_STEPUP_INVALID` (403) ở §15.2.

### 5.1.2 Device binding — `dvh` claim (defense in depth)

**Purpose**: nếu access token bị sniff/XSS → attacker dùng từ máy khác. `dvh` cho Exam Service
+ Gateway spot check không match → reject. Đây **KHÔNG phải security boundary** tuyệt đối
(attacker spoof header được) — chỉ raise cost.

```text
dvh = SHA256(
   user_agent        ||  "\n" ||
   accept_language   ||  "\n" ||
   sec_ch_ua         ||  "\n" ||
   sec_ch_ua_platform
)[:16]   -- 16 hex chars, truncated (không cần full 256-bit)
```

- Tính lúc phát access token (dựa trên header request login/refresh).
- Gateway verify: so match `dvh` với hash header hiện tại của request. Không match → `401`.
- Không dùng IP làm input (mobile roam, VPN làm noise cao → FP).
- **Phase 3** nâng cấp: thay bằng DPoP (RFC 9449) — PoP token với private key trong IndexedDB
  non-extractable. Lúc đó `dvh` deprecated.

**Signing key lifecycle:**
- 1 active key + 1 previous key (grace period) đăng trên JWKS
- Rotate mỗi **90 ngày** (cron job)
- 2 option lưu private key:
  - **MVP (Phase 1-2)**: K8s Secret mount file → `Nimbus JOSE JWT` load vào RAM, sign local. Latency ~1ms.
    Trade-off: key có trong memory pod, log dump / core dump nguy cơ leak. Mitigation: Pod SecurityContext
    `readOnlyRootFilesystem`, không ship log verbose.
  - **Phase 3**: Vault Transit Engine — key **không rời Vault**, Auth Service POST payload hash lên
    `/transit/sign/smartquiz-jwt`, Vault trả signature. Latency ~5-20ms/sign → giảm SLO login
    (nhưng nhanh hơn Argon2 ~250ms nên OK). Phụ thuộc Vault uptime — cần Vault HA 3 node.
- Quyết định stage: xem §18.5 OQ-4.

### 5.2 Refresh Token — Opaque

- Sinh: 64 byte random base64url-encoded (`openssl rand -base64 48`)
- Lưu: **hash SHA-256** vào PG (`refresh_tokens.token_hash`) + Redis (cho check revoked nhanh)
- TTL: **7 ngày web / 30 ngày mobile**, **sliding** (mỗi lần refresh, đổi token mới + TTL reset)
- Rotation: mỗi lần `/auth/refresh` gọi thành công → cũ bị thu hồi (set `revoked=true`, ghi
  `rotated_to_id` chỉ đến token mới), cấp mới. Token mới có `rotated_from_id` trỏ ngược về cũ.
- **Family ID**: mỗi lần login cấp `family_id` UUID mới. Tất cả token rotation từ cùng session
  giữ nguyên `family_id` — đây là chain để detect stolen.

### 5.2.1 Stolen detection — family tree, không phải single token

Bản MVP "dùng 2 lần cùng token" **không đủ** (RFC 6819 §5.2.2): attacker xoay thành công 1 lần,
có token mới hợp lệ — khi nạn nhân vô lại dùng token cũ đã revoke, hệ thống chỉ revoke token
cũ, attacker vẫn sống. Chuẩn: revoke **cả family**.

```text
Algorithm on POST /api/v1/auth/refresh { refresh_token }:

BEGIN;  -- all in one transaction

1. hash := SHA256(refresh_token)
2. row  := SELECT * FROM refresh_tokens WHERE token_hash = hash FOR UPDATE
     -- FOR UPDATE row-level lock đảm bảo 2 thread concurrent dùng
     -- cùng token → chỉ 1 đi qua bước 6, thread kia thấy revoked=true
     -- ở lần retry

3. IF row IS NULL:
     ROLLBACK; → 401 AUTH_TOKEN_INVALID

4. IF row.revoked = true:
     -- REUSE DETECTED — có ít nhất 1 clone token đã qua rotation rồi
     -- Phải lock family TRƯỚC khi UPDATE để tránh deadlock với thread
     -- đang rotate token khác trong cùng family
     PERFORM pg_advisory_xact_lock(hashtext(row.family_id::text));

     UPDATE refresh_tokens SET revoked=true, revoked_at=NOW()
       WHERE family_id = row.family_id AND revoked=false;
     INSERT INTO outbox (...) VALUES (... auth.account.locked.v1,
       {reason="refresh_reuse", user_id=row.user_id, locked_until=NULL} ...);
     COMMIT;
     notify user email async (out-of-band)
     → 401 AUTH_TOKEN_REVOKED

5. IF row.expires_at < NOW():
     ROLLBACK; → 401 AUTH_TOKEN_EXPIRED

6. -- Happy path: rotate
   new_token := random(64)
   INSERT refresh_tokens (family_id=row.family_id, rotated_from_id=row.id, ...)
     RETURNING id INTO new_id;
   UPDATE refresh_tokens
     SET revoked=true, revoked_at=NOW(), rotated_to_id=new_id
     WHERE id=row.id;
   COMMIT;
   → 200 { access_token, refresh_token=new_token }
```

**Concurrency analysis:**

- **Race 1 — FE double-click cùng token** (legitimate): 2 thread gọi `SELECT FOR UPDATE` trên
  cùng `row.id` → 1 thread lock được, 1 chờ. Thread đầu rotate (bước 6) + commit → row.revoked=true.
  Thread sau vào critical section, thấy `row.revoked=true` → bước 4 → revoke family (harmless vì
  family giờ chỉ có new_token + old row revoked) → user vẫn dùng được new_token. UX cost: thread
  sau trả 401, FE nhận token mới từ thread đầu. **Mitigation UX**: FE phải single-flight refresh
  (mutex in-memory) để tránh revoke family oan.

- **Race 2 — attacker dùng stolen token sau khi victim đã xoay**: token thief có `row_old`,
  victim đã rotate sang `row_new`. Thief gọi refresh → `SELECT FOR UPDATE row_old` → thấy
  `revoked=true` → revoke family → cả `row_new` bị revoke → victim phải login lại. Đúng ý đồ
  stolen detection (RFC 6819 §5.2.2).

- **Race 3 — attacker + victim đồng thời refresh 2 token khác nhau cùng family**: 2 thread
  `SELECT FOR UPDATE` trên 2 row khác → đều qua bước 2. Thread A (legit, chưa revoke) đi bước 6
  rotate. Thread B (attacker clone) đi bước 4 revoke family → race. Lock `pg_advisory_xact_lock(family_id)`
  ở **cả 2 path** (bước 4 VÀ bước 6) → serialize. Đổi bước 6 thành:

  ```text
  6a. PERFORM pg_advisory_xact_lock(hashtext(row.family_id::text));
  6b. ... INSERT ... UPDATE ... (như cũ)
  ```

  Nếu thread attacker lock trước → revoke family → thread victim wake up, re-SELECT row.id →
  thấy revoked=true → re-evaluate. Nếu victim lock trước → rotate xong → attacker vào, SELECT
  row_attacker vẫn thấy revoked=false (vì đã lưu từ lần trước), đi bước 6, INSERT xong mới UPDATE
  row_attacker=revoked → KHÔNG detect được! Fix: trong bước 6 sau lock family, **SELECT lại row.id**
  kiểm tra lần 2: nếu `revoked=true` → thoát bước 4.

  Final loop:
  ```text
  6a. pg_advisory_xact_lock(family_id);
  6b. row_recheck := SELECT revoked FROM refresh_tokens WHERE id=row.id;
  6c. IF row_recheck.revoked = true: GOTO step 4;
  6d. ELSE: INSERT new + UPDATE old (như cũ);
  ```

**Implementation note**: wrap trong `@Transactional(isolation = REPEATABLE_READ, rollbackFor = Exception.class)`.
Retry 3 lần cho `SQLException` với SQLState `40001` (serialization failure) — exponential backoff 10-50ms.

**Schema**: `refresh_tokens.family_id`, `rotated_from_id`, `rotated_to_id`, index
`idx_refresh_family` đã có trong `schema.sql:194-214` (merged 2026-04-22). Flyway delta cho
prod migration: `V{epoch}__auth_refresh_family.sql` khi scaffold app.

### 5.3 Token Revocation

| Scenario | Cơ chế | Event outbox |
| -------- | ------ | ------------ |
| Logout 1 device | Set `revoked=true` trên `refresh_tokens` + thêm `jti` access token vào Redis blacklist với TTL = thời gian còn lại | — (local) |
| Logout tất cả device | UPDATE `refresh_tokens SET revoked=true WHERE user_id=?` | — (chỉ local) |
| Password change | Revoke tất cả refresh token + yêu cầu login lại | `auth.password.changed.v1` (critical, outbox) |
| Account bị lock | Như trên | `auth.account.locked.v1` (critical, outbox) |
| Role change | Revoke nếu role downgrade (mất permission) | `auth.role.changed.v1` (critical, outbox → tất cả service invalidate cache) |
| Stolen refresh detect | Revoke all + flag user review + gửi email cảnh báo | `auth.account.locked.v1` (reason=`stolen_token`, locked_until=NULL nghĩa là chờ user verify email trước khi unlock) |

### 5.4 JWKS endpoint

```
GET /.well-known/jwks.json
Cache-Control: public, max-age=3600

{
  "keys": [
    { "kty": "RSA", "kid": "2026-04-key1", "use": "sig", "alg": "RS256", "n": "...", "e": "AQAB" },
    { "kty": "RSA", "kid": "2026-01-key0", "use": "sig", "alg": "RS256", "n": "...", "e": "AQAB" }
  ]
}
```

Các service khác (Exam, Question...) dùng `spring-security-oauth2-resource-server` + cấu hình `jwk-set-uri` → tự động verify offline, refresh cache mỗi giờ.

---

## VI. PASSWORD HANDLING

### 6.0 Register flow — chống email enumeration

**Vấn đề**: nếu `POST /register` trả `201 created` với email mới và `409 AUTH_EMAIL_EXISTS`
với email tồn tại, attacker chạy script brute-force 1M emails để liệt kê user — dùng cho
targeted phishing hoặc credential stuffing.

**Giải pháp**: endpoint luôn trả cùng response shape và status code, **bất kể email tồn tại**.
Side effect khác nhau xảy ra **qua email** (user hợp lệ sẽ nhận, attacker không).

**Sync/async split — quan trọng**: domain mutation (create user, insert token, insert outbox row)
**phải sync cùng 1 TX** (ADR-001 transactional outbox). Chỉ phần **email dispatch** là async — và
nó đã async tự nhiên qua Kafka → Notification Service consumer. Service trả 202 ngay sau khi
commit TX xong, không chờ email gửi thật.

```
POST /api/v1/auth/register { email, password, full_name, invite_token? }

BEGIN TX                                                   -- sync
  lookup := SELECT id, email_verified FROM users WHERE email = ? FOR UPDATE
  -- Argon2 hash LUÔN chạy (kể cả skip create) để timing đồng nhất ~250ms
  pw_hash := argon2id(password)

  IF lookup IS NULL:
      INSERT users (is_active=false, email_verified=false, password_hash=pw_hash)
      INSERT email_verification_tokens (purpose='verify_email', TTL=24h)
      INSERT outbox (auth.user.registered.v1, ...)         -- critical-outbox
      -- Payload event có flag send_welcome=true

  ELSIF lookup.email_verified = true:
      -- Không tạo user. Nhưng vẫn publish event notify chủ
      INSERT outbox (auth.registration.attempt_on_existing.v1, user_id=lookup.id, ip, ua)
      -- Consumer Notification gửi email "có người thử đăng ký bằng email của bạn"

  ELSIF lookup.email_verified = false:
      -- User pending verify, idempotent re-send
      INSERT email_verification_tokens (purpose='verify_email', TTL=24h)
      INSERT outbox (auth.verify_email.resent.v1, user_id=lookup.id)
      -- KHÔNG update password_hash (tránh attacker override pw user thật đang pending)
COMMIT

→ 202 Accepted { email_sent: true, email_masked: "h***@hust.edu.vn", request_id }

-- Relayer (async ~100-200ms sau) publish Kafka → Notification Service gửi email thực.
```

**Event mới** (add vào §11.1 table — critical-outbox): `auth.registration.attempt_on_existing.v1`,
`auth.verify_email.resent.v1`.

**Rate limit 2 lớp** (§9.1):
- `rate:register:{ip}` 5/hr — chống spam tạo account mới
- `rate:register_notify:{email_hash}` 3/day — chống attacker spam "có người đăng ký" email cho victim

**Timing attack mitigation**: đường "email tồn tại" và "email mới" đều phải qua Argon2 hash
~250ms. Nếu skip hash trong branch "existing" → timing difference lộ enumeration. Code:
```java
String pwHash = hasher.hash(password);  // ALWAYS, kể cả không dùng
if (existing == null) { user.setPasswordHash(pwHash); ... }
// else: pwHash bị drop, nhưng time elapsed đã tương đương
```

### 6.1 Hash

- **Argon2id** với params theo OWASP 2024:
  - `memory = 65536` KB (64 MB)
  - `iterations = 3`
  - `parallelism = 4`
  - `salt = 16 bytes` random
  - `hashLength = 32 bytes`
- Thời gian hash target: **~250ms trên server 4 vCPU** (đủ chậm để chống brute-force, đủ nhanh để UX)
- Rehash tự động khi phát hiện params cũ (user login thành công → nếu hash format không match current params → rehash)

### 6.2 Policy

| Yêu cầu | Giá trị |
| ------- | ------- |
| Độ dài tối thiểu | 12 ký tự |
| Độ dài tối đa | 128 ký tự (Argon2 xử lý được nhưng cap để tránh DoS) |
| Phức tạp | Ít nhất 3/4 nhóm: chữ hoa, chữ thường, số, ký tự đặc biệt |
| Chống password phổ biến | Check với danh sách 10k password rò rỉ (list offline) |
| Chống password cá nhân | Không chứa email/username/fullname |
| Lịch sử | Không trùng 5 lần gần nhất |
| Hết hạn | Không ép đổi định kỳ (NIST SP 800-63B hiện khuyến nghị **không**) — trừ khi phát hiện breach |

### 6.3 Reset password flow

```
1. POST /auth/password/forgot { email }
   → Nếu email tồn tại: tạo token random 32 bytes, lưu hash vào email_verification_tokens (purpose=reset_password, TTL=1h)
   → Gửi email qua Notification Service với link /auth/reset?token=xxx
   → Trả 200 OK luôn (không leak user có/không — chống enumeration)

2. POST /auth/password/reset { token, new_password }
   → BEGIN TX
   → Verify token (hash, chưa dùng, chưa hết hạn)
   → Check policy
   → Cập nhật password_hash + insert vào password_history
   → Mark token used_at
   → Revoke tất cả refresh token của user (force re-login)
   → INSERT INTO outbox (auth.password.changed.v1, triggered_by="reset")  -- §11
   → COMMIT
   → Relayer publish Kafka (~100-200ms sau)
```

---

## VII. MFA — TOTP (RFC 6238)

### 7.1 Setup flow

```
1. POST /auth/mfa/setup (JWT authenticated, chưa bật MFA)
   → Sinh secret 160 bit (20 bytes, base32 encoded)
   → Lưu secret ENCRYPTED (AES-256-GCM, key từ Vault) vào users.mfa_secret TẠM THỜI
   → Return:
     {
       "secret": "JBSWY3DPEHPK3PXP",
       "qr_code_url": "otpauth://totp/SmartQuiz:hs.le@hust.edu.vn?secret=JBSWY3DPEHPK3PXP&issuer=SmartQuiz",
       "backup_codes": ["abcd-efgh-ijkl", ...]  // 10 codes, mỗi code 12 ký tự
     }

2. POST /auth/mfa/verify { code: "123456" }
   → BEGIN TX
   → Verify TOTP với secret tạm thời (window = ±1 × 30s)
   → Nếu đúng: set mfa_enabled=true, insert 10 backup codes (hashed) vào mfa_backup_codes
   → INSERT INTO outbox (auth.mfa.enabled.v1)  -- §11
   → COMMIT
```

### 7.2 Login flow có MFA

**Response contract** (pick 1 shape, enforce throughout):

**Bước 1: `POST /api/v1/auth/login { email, password }`** — 2 khả năng:

Nếu `user.mfa_enabled = false` → **200 OK**, body **token response**:
```json
{
  "access_token": "eyJ...",
  "refresh_token": "AbCd...",
  "expires_in": 900,
  "token_type": "Bearer"
}
```

Nếu `user.mfa_enabled = true` → **200 OK**, body **challenge response** (shape khác hẳn — FE
discriminate bằng sự hiện diện của `mfa_challenge` key, KHÔNG qua HTTP status / error code):
```json
{
  "mfa_challenge": {
    "mfa_token": "eyJ... (short-lived JWT 5 phút, claims chỉ cho MFA step)",
    "methods": ["totp", "backup_code"],
    "expires_in": 300
  }
}
```

FE logic: `if ("mfa_challenge" in response.body) showMfaModal(); else storeTokens();`

**Lý do không dùng HTTP 401/403**: MFA challenge là **success state có điều kiện**, không phải
authentication failure. Dùng 401 gây confusion với token invalid; 403 với permission denied.
`AUTH_MFA_REQUIRED` trong §15.2 chỉ dùng cho trường hợp gRPC internal trả về "endpoint này
yêu cầu user có `mfa_passed=true`" — khác context.

**Bước 2: `POST /api/v1/auth/login/mfa { mfa_token, code }`** → **200 OK**:
```json
{
  "access_token": "eyJ...",
  "refresh_token": "AbCd...",
  "expires_in": 900,
  "token_type": "Bearer",
  "backup_codes_remaining": 2,
  "warning": "low_backup_codes"
}
```
- `backup_codes_remaining`: int, có mặt chỉ khi user login bằng backup code
- `warning`: string enum, chỉ khi < 3 backup codes → `"low_backup_codes"`; còn lại absent
- Claim `mfa_passed=true` trong access_token mới

**Error**: sai code → `401 AUTH_MFA_INVALID` (tăng `rate:mfa:{user_id}` counter — §9.1).

### 7.3 Disable MFA

Yêu cầu re-authentication: phải nhập mật khẩu + 1 TOTP code hợp lệ trước khi disable.

### 7.4 Lost device

User liên hệ admin org → admin dùng endpoint admin `/admin/users/{id}/mfa/disable` (cần MFA của chính admin + log audit).

---

## VIII. OAUTH2 / SSO

### 8.1 Provider supported

| Provider | Auth URL | Token URL | UserInfo URL | Scopes |
| -------- | -------- | --------- | ------------ | ------ |
| Google | `https://accounts.google.com/o/oauth2/v2/auth` | `https://oauth2.googleapis.com/token` | `https://www.googleapis.com/oauth2/v3/userinfo` | `openid email profile` |
| Microsoft | `https://login.microsoftonline.com/common/oauth2/v2.0/authorize` | `https://login.microsoftonline.com/common/oauth2/v2.0/token` | `https://graph.microsoft.com/oidc/userinfo` | `openid email profile User.Read` |
| GitHub | `https://github.com/login/oauth/authorize` | `https://github.com/login/oauth/access_token` | `https://api.github.com/user` | `read:user user:email` |

### 8.2 Flow: Authorization Code + PKCE

```
[Client]                [Auth Service]          [Provider]
  │                          │                      │
  │ GET /auth/oauth/google   │                      │
  │ ──────────────────────►  │                      │
  │                          │ Sinh state + code_verifier
  │                          │ Lưu vào Redis (TTL 10 phút)
  │                          │                      │
  │ 302 Redirect với state + │                      │
  │ code_challenge (S256)    │                      │
  │ ◄──────────────────────  │                      │
  │                          │                      │
  │  ─────► User login Google ──────►                │
  │                                                  │
  │ 302 callback?code=...&state=...                  │
  │ ◄──────────────────────────────                  │
  │                          │                      │
  │ GET /auth/oauth/google/callback?code=...&state=..│
  │ ──────────────────────►  │                      │
  │                          │ Verify state khớp Redis
  │                          │ POST token exchange với code_verifier
  │                          │ ────────────────────►│
  │                          │                      │
  │                          │ ◄─── access_token ───│
  │                          │                      │
  │                          │ GET userinfo         │
  │                          │ ────────────────────►│
  │                          │                      │
  │                          │ ◄─── email, name ────│
  │                          │                      │
  │                          │ Find-or-create user:
  │                          │   - Nếu email đã có user → link thêm oauth_providers
  │                          │   - Nếu chưa → tạo user mới, email_verified=true
  │                          │ Cấp access + refresh token
  │ Set cookie + 302 về app  │                      │
  │ ◄──────────────────────  │                      │
```

**Ghi chú bảo mật:**
- `state` chống CSRF; `code_verifier` (PKCE) chống code interception
- Nếu email từ provider không `verified`: bắt user verify qua email thường trước khi link
- Khi tạo user mới qua OAuth: `password_hash = NULL`; user phải set password riêng nếu muốn đăng nhập bằng email/password
- **`redirect_uri` đăng ký với provider phải là literal full path** `https://auth.smartquiz.vn/api/v1/auth/oauth/{provider}/callback` — không dùng shorthand (§12.0). Provider compare exact string; `/auth/...` vs `/api/v1/auth/...` → redirect_uri_mismatch error.

### 8.3 Link nhiều provider — với email-match verification

**Rủi ro nếu làm ngây thơ**: attacker tạo Google account `victim+evil@gmail.com` (Gmail ignore
dấu `+`) → user-victim đang login SmartQuiz bằng password → attacker gửi phishing link
`/api/v1/auth/oauth/google/link` cho victim click trong khi đang login → Google account của
attacker được attach vào tài khoản victim → từ nay attacker login bằng Google = takeover.

**Flow an toàn:**

```
1. User đang login (JWT active)
2. POST /api/v1/auth/oauth/{provider}/link  →  302 provider OAuth
3. Callback /api/v1/auth/oauth/{provider}/callback:
     - Verify state khớp Redis (CSRF)
     - Token exchange với PKCE
     - Fetch userinfo → provider_email
     - CASE A: provider_email == current_user.email AND provider reports email_verified
         → attach oauth_providers row ngay
         → 200 { linked: true, provider }
     - CASE B: provider_email != current_user.email  OR  email_verified=false
         → DO NOT attach
         → Sinh token random 32b, lưu hash vào email_verification_tokens
           (purpose='link_oauth', payload={provider, provider_user_id, provider_email})
         → Gửi email CONFIRM đến provider_email (không phải current_user.email)
           với link: /api/v1/auth/oauth/link/confirm?token=xxx
         → Trả 202 { pending_verification: true, email_masked: "a***@gmail.com" }
4. GET /api/v1/auth/oauth/link/confirm?token=xxx:
     - User phải đang login (cùng session khởi tạo link — so match user_id)
     - Verify token chưa hết hạn (15 phút) + chưa dùng
     - Attach oauth_providers row
     - Publish auth.oauth.linked.v1 (outbox)
     - Return 200 redirect về account settings
```

**Rule bổ sung:**
- Không cho link nếu `oauth_providers` đã có row với cùng `(provider, provider_user_id)`
  thuộc user khác → `409 AUTH_OAUTH_ALREADY_LINKED`.
- Không cho link nếu `provider_email` đã là email chính của user khác (conflict potential
  takeover) → bắt user khác detach trước hoặc từ chối thẳng.
- Mỗi lần link thành công → gửi email thông báo đến `current_user.email` (kể cả đã verified),
  cho phép user "không phải tôi" → unlink + lock account.

### 8.4 Unlink provider

DELETE `/api/v1/auth/oauth/{provider}`:
- Nếu user **không có password** AND đây là OAuth provider duy nhất → từ chối `409 AUTH_OAUTH_LAST_METHOD`
  (bắt set password trước). Tránh user tự khóa mình ra.
- Publish `auth.oauth.unlinked.v1` (outbox).

---

## IX. RATE LIMITING & ACCOUNT LOCKOUT

### 9.1 Rate limit rules (Redis sliding window)

| Endpoint | Key | Giới hạn | Hành động khi vượt |
| -------- | --- | -------- | ------------------ |
| POST /auth/login | `rate:login:{ip}` | 10 / 15 phút | 429 + block 15 phút |
| POST /auth/login (per user) | `rate:login_user:{email}` | 5 / 15 phút | Same |
| POST /auth/password/forgot | `rate:forgot:{ip}` | 3 / hour | 429 |
| POST /auth/register | `rate:register:{ip}` | 5 / hour | 429 |
| POST /auth/mfa/verify | `rate:mfa:{user_id}` | 5 / 10 phút | Disable MFA tạm thời, yêu cầu reset |
| POST /auth/refresh | `rate:refresh:{user_id}` | 30 / phút | 429 |

Implementation: Lua script atomic trên Redis:

```lua
-- KEYS[1]=rate key, ARGV[1]=now_ms, ARGV[2]=window_ms, ARGV[3]=limit
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, tonumber(ARGV[1]) - tonumber(ARGV[2]))
local count = redis.call('ZCARD', KEYS[1])
if count < tonumber(ARGV[3]) then
    redis.call('ZADD', KEYS[1], ARGV[1], ARGV[1]..':'..math.random())
    redis.call('PEXPIRE', KEYS[1], ARGV[2])
    return 1
end
return 0
```

### 9.2 Account lockout (lớp 2)

Độc lập rate limit theo IP: theo dõi `users.failed_login_count`.
- Sau 5 lần thất bại liên tiếp: lock 15 phút (`locked_until = now + 15m`)
- Sau 10 lần: lock 1 giờ
- Sau 20 lần: lock vĩnh viễn đến khi admin unlock (email cảnh báo user)
- Reset counter khi login thành công

---

## X. RBAC — CHI TIẾT PHÂN QUYỀN

### 10.1 Cách hoạt động

1. Auth Service phát JWT chứa `orgs: [{id, role}]` (tất cả membership) + `org_id` active
2. API Gateway forward JWT nguyên văn
3. Mỗi service verify JWT (offline qua JWKS) và trích ra `org_id + role`
4. Service tự enforce policy theo domain của mình

### 10.2 Policy expression (permission-based, KHÔNG hardcode role)

```java
// Check permission code, không check role name
@PreAuthorize("hasAuthority('exam.update.own') and @examPolicy.isOwner(authentication, #examId)")
public void updateExam(UUID examId, ExamDto dto) { ... }

// Yêu cầu 1 trong 2 permission (own hoặc any-in-org)
@PreAuthorize("hasAnyAuthority('exam.update.own','exam.update.any') and @examPolicy.isSameOrg(authentication, #examId)")
public void updateExamAny(UUID examId, ExamDto dto) { ... }

@PreAuthorize("hasAuthority('exam.publish')")
public void publishExam(UUID examId) { ... }
```

`@examPolicy.isOwner` / `isSameOrg` là bean tự viết, dùng user_id + org_id từ JWT + truy vấn DB (cache Redis 60s). **Check ownership tách khỏi check permission** — code sạch hơn, test từng mảnh dễ hơn.

### 10.3 JWT authorities claim

Nguồn truth: **§5.1** (full claim set). Ở đây chỉ nói thêm hành vi `authorities` claim:

- **Composition**: union tất cả permission của `role_code` active (từ `org_id`) + permission
  trực tiếp gán cho user (nếu Phase 3 hỗ trợ). Resolve tại thời điểm cấp token; không re-resolve
  trong window 15 phút của access token.
- **Ordering**: sorted alphabetical (deterministic — giúp cache header size, CDN dedup).
- **Custom role**: user có custom role → `authorities` tự động phản ánh permission của role đó,
  không cần deploy lại consumer service. Consumer dùng `hasAuthority('...')`, không care role_code.
- **Invalidation**: event `auth.role.changed.v1` (§11.1) — consumer invalidate cache user→perms
  60s (§10.2). Access token cũ trong 15 phút vẫn carry permission cũ; service có thể opt-in
  call `CheckPermission` gRPC (§12.6) cho hành động critical cần fresh state.
- **Size cap**: nếu `authorities.length > 50` → JWT có thể > 8KB → gateway reject. Khi gần cap:
  fallback claim `perm_version: <int>` + service lookup qua gRPC (Phase 2 — xem §XVIII gap #7 từ ultrareview).

### 10.4 Platform admin

- Không trong `user_organizations`, mà trong claim `platform_role: "super_admin"` hoặc trong bảng `platform_admins(user_id, created_at)`
- Khi có claim này: bỏ qua org scope, xem được toàn hệ thống
- Mọi action phải log audit kèm `actor=platform_admin, impersonated=<user_id>` (nếu có)

### 10.5 Impersonation (dành cho support)

Platform admin có thể POST `/admin/impersonate { target_user_id, reason, duration_minutes }`:
- Cấp JWT đặc biệt với claims `sub = target_user, impersonator = <admin_id>, imp_exp = now+15m`
- Mọi service khi thấy claim `impersonator` → log mọi request vào audit stream riêng
- Action nhạy cảm (đổi password, thu hồi cert) bị chặn khi đang impersonate

---

## XI. EVENTS — TRANSACTIONAL OUTBOX + AVRO

> **⚠️ Nguồn truth topic name + schema: `shared-contracts/avro/TOPICS.md`** (catalog tất cả
> topic của repo) và `shared-contracts/avro/auth/*.avsc` (schema từng event). Bảng ở §11.1
> là **view tóm tắt cho người đọc doc Auth** — khi có lệch, file shared-contracts thắng. PR
> đổi topic **phải cập nhật cả 2 nơi + CLAUDE.md §8** trong cùng commit (CI grep check:
> `shared-contracts/avro/TOPICS.md` phải chứa mọi topic xuất hiện ở `docs/*-service-design.md`).

Tuân thủ **ADR-001** (`docs/adr/ADR-001-sla-rpo-outbox.md`) và CLAUDE.md §3:

- **Không** gọi `kafkaTemplate.send()` trực tiếp từ UseCase. Mọi state change → insert vào
  bảng `outbox` trong **cùng transaction** (bảng đã có, xem `database/postgresql/schema.sql` §13).
- Relayer (leader-elected qua `pg_try_advisory_lock`) poll `SELECT ... FOR UPDATE SKIP LOCKED`,
  publish Kafka, `UPDATE published_at`. Mỗi instance pod chạy relayer thread nhưng chỉ 1 leader thắng lock.
- Payload encode **Avro**, schema publish lên **Apicurio Schema Registry** với compat mode
  `BACKWARD` (CLAUDE.md §2). Schema đặt trong `shared-contracts/avro/auth/*.avsc`.

### 11.1 Phân loại event theo độ quan trọng (ADR-001 §3)

**Critical — BẮT BUỘC qua outbox** (drive state change ở service khác, mất event = sai logic):

| Topic (v1) | Aggregate key | Payload (Avro record) | Consumer |
| ---------- | ------------- | --------------------- | -------- |
| `auth.user.registered.v1` | `user_id` | `{user_id, email, org_id, source, provider?, registered_at}` | Notification (welcome email), Analytics |
| `auth.user.deleted.v1` | `user_id` | `{user_id, anonymized: bool, reason, deleted_at}` | Exam (archive attempt), Question (reassign ownership), GDPR export |
| `auth.registration.attempt_on_existing.v1` | `sha256(email)` | `{email_hash, existing_user_id, ip, ua, attempt_at}` | Notification: gửi "ai đó thử đăng ký bằng email của bạn" cho chủ (§6.0) |
| `auth.verify_email.resent.v1` | `user_id` | `{user_id, token_expires_at}` | Notification: re-send verify email |
| `auth.password.changed.v1` | `user_id` | `{user_id, triggered_by: enum{user,reset,admin}, changed_at}` | **Exam/Question**: revoke session cache. Notification: email alert. |
| `auth.role.changed.v1` | `user_id` | `{user_id, org_id, old_role_code, new_role_code, changed_by, changed_at}` | **Tất cả service**: invalidate Redis permission cache (60s TTL). Audit. |
| `auth.account.locked.v1` | `user_id` | `{user_id, locked_until, reason, locked_at}` | Notification. Exam: terminate active attempt nếu có. |
| `auth.mfa.enabled.v1` / `.mfa.disabled.v1` | `user_id` | `{user_id, by_admin: bool, at}` | Notification. Audit. |

**Fire-and-forget — KHÔNG qua outbox** (analytics / audit log, mất vài event chấp nhận được):

| Topic (v1) | Key | Payload | Consumer |
| ---------- | --- | ------- | -------- |
| `auth.login.success.v1` | `user_id` | `{user_id, ip, user_agent, mfa_used, provider, ts}` | Analytics, Fraud detection |
| `auth.login.failed.v1` | `sha256(email)` | `{email_hash, ip, reason, consecutive_count, ts}` | Fraud detection (IP reputation). Không dùng email raw để tránh leak PII qua Kafka retention. |

Lý do tách: `auth.login.success` có thể cao 10k/phút lúc peak — tốn write amp trên `outbox`.
Audit stream chấp nhận gap; `audit_log_auth` table trong PG (§4.4) vẫn là truth.

### 11.2 Code pattern — outbox publisher

**Encoding strategy:** payload lưu **JSON (JSONB)** ở bảng `outbox` (dễ debug, schema đã có cột
`payload JSONB` — xem `schema.sql` §13). **Relayer** mới là component encode Avro trước khi
publish Kafka, dùng Apicurio `SerdeConfig` để resolve schema theo `topic + eventType + schema_version`.
Lý do: nếu encode Avro ngay khi insert outbox thì debug DB phải decode, rất phiền.

**⚠️ Propagation rule — critical cho đúng outbox invariant:**

- **UseCase** (caller): `@Transactional(propagation = REQUIRED)` — mở TX domain change
- **AuthOutboxPublisher**: `@Transactional(propagation = MANDATORY)` — **bắt buộc đã có TX**,
  **throws** `IllegalTransactionStateException` nếu caller gọi ngoài TX

Tại sao MANDATORY không phải REQUIRED? Nếu dùng REQUIRED: caller quên `@Transactional` →
publisher tự mở TX riêng → outbox row commit trước/sau domain change → **half-write bug**,
exactly cái outbox cần tránh. MANDATORY fail-fast ngay lúc dev, tránh silent bug prod.

Enforcement: unit test `testPublisherThrowsWithoutTx()` assert `IllegalTransactionStateException`
khi gọi outside `@Transactional`.

```java
@Service
@Transactional(propagation = Propagation.MANDATORY)   // ❗ fail-fast nếu caller không có TX
class AuthOutboxPublisher {
    private final OutboxRepository repo;
    private final ObjectMapper jsonMapper;  // Jackson — chỉ cho lưu DB

    public void publish(String topic, String eventType,
                        String aggregateType, String aggregateId,
                        Object payload, String partitionKey) {
        UUID eventId = UUID.randomUUID();
        OutboxRow row = new OutboxRow(
            eventId, aggregateType, aggregateId,
            topic, eventType,
            jsonMapper.valueToTree(payload),   // JSONB — human-readable
            Map.of("trace_id", MDC.get("trace_id"),
                   "schema_version", "1"),
            partitionKey
        );
        repo.save(row);
    }
}

// UseCase — BẮT BUỘC @Transactional (nếu thiếu, publisher throws)
@Service
class ChangePasswordUseCase {
    @Transactional  // REQUIRED default
    public void execute(UUID userId, RawPassword pw) {
        User u = userRepo.findById(userId).orElseThrow();
        u.changePassword(pw, hasher, policy);
        userRepo.save(u);
        pwHistoryRepo.insert(userId, u.getPasswordHash());
        refreshTokenRepo.revokeAllForUser(userId);
        outbox.publish(...);   // OK — trong TX của UseCase
    }
}

// Relayer — chạy scheduled thread, LEADER-only (pg_try_advisory_lock)
// Async publish + per-row error isolation + batch time budget
@Component
class OutboxRelayer implements SmartLifecycle {
    private static final long BATCH_BUDGET_MS = 3_000;   // < poll interval 100ms × 30
    private static final int  BATCH_SIZE      = 500;
    private final KafkaTemplate<String, GenericRecord> kafka;
    private volatile boolean running;

    @Scheduled(fixedDelay = 100)
    void pollAndPublish() {
        if (!running || !leaderLock.tryAcquire()) return;
        try {
            long deadline = System.currentTimeMillis() + BATCH_BUDGET_MS;
            List<OutboxRow> batch = repo.claimPending(BATCH_SIZE);  // FOR UPDATE SKIP LOCKED
            List<CompletableFuture<Void>> inflight = new ArrayList<>(batch.size());

            for (OutboxRow row : batch) {
                if (System.currentTimeMillis() > deadline) break;   // leave rest cho poll tiếp
                inflight.add(publishOne(row));
            }
            // Wait up to remaining budget, không block vô hạn
            long waitMs = Math.max(0, deadline - System.currentTimeMillis());
            CompletableFuture.allOf(inflight.toArray(new CompletableFuture[0]))
                .orTimeout(waitMs, MILLISECONDS)
                .exceptionally(ex -> { log.warn("batch partial timeout", ex); return null; })
                .join();
        } finally {
            leaderLock.release();   // release trước commit để pod khác có thể pick
        }
    }

    private CompletableFuture<Void> publishOne(OutboxRow row) {
        try {
            GenericRecord avroPayload = avroMapper.toAvro(row);
            ProducerRecord<String, GenericRecord> rec = new ProducerRecord<>(
                row.topic(), row.partitionKey(), avroPayload);
            rec.headers().add("trace_id", row.headers().get("trace_id").getBytes(UTF_8));
            rec.headers().add("event_id", row.eventId().toString().getBytes(UTF_8));

            return kafka.send(rec).completable()
                .thenAccept(meta -> repo.markPublished(row.eventId()))
                .exceptionally(ex -> {
                    // Per-row error isolation — KHÔNG throw
                    repo.markFailure(row.eventId(), trim(ex.getMessage(), 500));
                    metrics.publishFailed.increment(row.topic(), classify(ex));
                    return null;
                });
        } catch (Exception serializeErr) {
            // Avro encoding fail → "poison row", quarantine nhưng không block queue
            repo.markFailure(row.eventId(), "serde: " + trim(serializeErr.getMessage(), 400));
            metrics.publishFailed.increment(row.topic(), "serde");
            return CompletableFuture.completedFuture(null);
        }
    }

    @Override public void stop()  { running = false; kafka.flush(); }  // graceful SIGTERM
    @Override public void start() { running = true; }
    @Override public boolean isRunning() { return running; }
}
```

### 11.3 Avro schema convention

- File: `shared-contracts/avro/auth/auth.password.changed.v1.avsc`
- Namespace: `vn.smartquiz.auth.v1`
- Rule BACKWARD compat: **chỉ được add field với default**, **không** remove/rename field, **không** đổi type.
  Nếu breaking change → topic `.v2` mới, service tự migrate dần.
- CI gate: `./gradlew :shared-contracts:avroCompatCheck` chạy Apicurio compat check vs schema đang live.

### 11.4 Producer/Relayer config

| Setting | Value | Lý do |
| ------- | ----- | ----- |
| `acks` | `all` | Đợi ISR replicate — không mất event |
| `enable.idempotence` | `true` | Chống duplicate do retry |
| `max.in.flight.requests.per.connection` | `5` | Cùng với idempotence vẫn giữ order per partition |
| `compression.type` | `zstd` | Giảm network cost |
| `delivery.timeout.ms` | `30000` | Broker slow → fail fast, relayer mark last_error, pick rows khác |
| `request.timeout.ms` | `5000` | Mỗi request không chờ quá 5s |
| Poll interval relayer | `100ms` fixedDelay | RPO target 5s — dư dả |
| Batch budget relayer | `3000ms` wall-clock | Nếu batch vượt budget, leave rest cho poll tiếp (§11.2 code) |
| Batch size relayer | `500 rows / poll` max | `claimPending` giới hạn; thực tế publish theo async callback |
| `spring.lifecycle.timeout-per-shutdown-phase` | `30s` | SIGTERM → drain inflight + `kafka.flush()` trước khi JDBC pool close |

### 11.5 Metric outbox bắt buộc (ADR-001 §impl)

| Metric | Alert |
| ------ | ----- |
| `auth_outbox_pending_size` (gauge) | warning > 1k, critical > 10k |
| `auth_outbox_publish_lag_seconds` (histogram) | p99 > 5s = critical (vi phạm RPO) |
| `auth_outbox_publish_failed_total{reason}` (counter) | spike > 10/min → page |

---

## XII. API ENDPOINTS CHI TIẾT

### 12.0 API conventions

Bảng §12.1-12.4 chỉ là tóm tắt. **Contract đầy đủ ở OpenAPI spec** `app/src/main/resources/static/openapi.yaml`
(§18.1 prereq). Mọi endpoint **phải** tuân theo convention dưới. §12.7 có template 2 endpoint
điển hình (login + refresh) kèm JSON Schema đầy đủ để generate OpenAPI.

#### 12.0.1 Versioning

- **Tất cả endpoint REST** ở §12.1-12.4 có prefix `/api/v1/` (trong **bảng dưới** viết tắt
  `/auth/login` = `/api/v1/auth/login` để bảng ngắn gọn).
- **Rule shorthand vs literal**: bảng + prose trong section flow (§5-§11) có thể dùng shorthand
  `/auth/xxx`. **NHƯNG** mọi chỗ literal path vào config / HTTP header / cookie attribute
  (`Path=`, `Origin=`, redirect URI, CORS, OpenAPI spec) **phải dùng full `/api/v1/auth/xxx`**
  — nếu không sẽ break runtime (cookie scope mismatch, redirect 404, CORS deny).
- **Endpoint chuẩn công khai** không versioned (client/proxy OAuth expect path cố định):
  `/.well-known/jwks.json`, `/.well-known/openid-configuration`, `/actuator/*`
- **Breaking change policy**: bump major → `/api/v2/`, giữ `/api/v1/` tối thiểu 6 tháng
  song song + header `Sunset: <date>` (RFC 8594). Backward-compat trong cùng major: add field
  tùy chọn OK, **remove/rename field KHÔNG**, đổi type KHÔNG.
- **Content negotiation**: `Accept: application/json` (default) hoặc
  `Accept: application/vnd.smartquiz.v1+json` cho client muốn lock version tường minh.
- **gRPC** (§12.6) dùng proto package `vn.smartquiz.auth.v1` — bump package khi breaking.

#### 12.0.2 Content-Type & encoding

- Request: `Content-Type: application/json; charset=utf-8`. Từ chối mọi content-type khác
  với `415 Unsupported Media Type`, trừ endpoint OAuth callback (form-encoded theo RFC 6749)
  và `/actuator/*` (text/plain OK).
- Response: luôn `application/json; charset=utf-8` — kể cả error (RFC 7807).
- Không nhận trailing slash — `/api/v1/auth/login/` → `308 Permanent Redirect` về canonical.
- Request body size cap: **1 MB** (Spring `spring.servlet.multipart.max-request-size` + filter).
  Vượt → `413 Payload Too Large`.

#### 12.0.3 Naming + formats

| Thành phần | Rule | Ví dụ |
| ---------- | ---- | ----- |
| Path segment | lowercase, hyphen-separated | `/auth/switch-org`, `/mfa/backup/regenerate` |
| Query param | lowercase, hyphen-separated | `?org-id=...&page-size=50` |
| JSON field | **snake_case** | `access_token`, `email_verified`, `created_at` |
| JSON enum value | lowercase snake_case | `"triggered_by": "admin_reset"` |
| HTTP header custom | `X-` prefix, kebab-case | `X-Request-Id`, `X-Rate-Limit-Remaining` |
| UUID | canonical hyphenated, lowercase | `a0000000-0000-0000-0000-000000000004` |
| Timestamp | **ISO 8601 UTC with Z** | `"2026-04-22T10:05:22.123Z"` |
| Duration trong body | seconds (int) hoặc ISO 8601 duration | `"expires_in": 900` hoặc `"ttl": "PT15M"` |
| Bool | native JSON `true`/`false`, không dùng `"true"` | — |
| Missing vs null | **Missing field** = "không nói về field này"; **`null`** = "field tồn tại với giá trị unset". Không dùng empty string `""` làm placeholder | — |

#### 12.0.4 Status code conventions

| Code | Dùng khi | Body |
| ---- | -------- | ---- |
| `200 OK` | GET thành công, POST/PATCH/PUT mutation trả resource | JSON resource |
| `201 Created` | POST tạo resource mới kèm identifier | JSON resource + header `Location: /api/v1/...` |
| `202 Accepted` | Async/deferred — server nhận nhưng chưa hoàn tất (vd `/auth/register` §6.0) | JSON `{request_id, status}` |
| `204 No Content` | Mutation thành công, không trả body (DELETE, logout) | — (body rỗng) |
| `400 Bad Request` | **Malformed**: JSON parse fail, required header thiếu | RFC 7807 |
| `401 Unauthorized` | Authentication thiếu/sai (không có token, token invalid) | RFC 7807 + `WWW-Authenticate: Bearer ...` |
| `403 Forbidden` | Authenticated nhưng **thiếu quyền** — không đủ permission/sudo/step-up | RFC 7807 |
| `404 Not Found` | Resource không tồn tại | RFC 7807 |
| `409 Conflict` | State conflict — unique constraint violated (vd email đã link OAuth khác) | RFC 7807 |
| `410 Gone` | Resource đã tồn tại nhưng hết hiệu lực (token used_at, expired) | RFC 7807 |
| `415` | Content-Type không hỗ trợ | RFC 7807 |
| `422 Unprocessable Entity` | **Semantic validation fail** (JSON parse OK nhưng business rule fail — password quá yếu, email format sai) | RFC 7807 + `errors[]` field-level |
| `429 Too Many Requests` | Rate limit hit | RFC 7807 + header `Retry-After` + `X-RateLimit-*` (§12.0.7) |
| `500` | Unhandled server error | RFC 7807 chỉ có `trace_id`, KHÔNG leak stack |
| `503 Service Unavailable` | Upstream dep down (Kafka, Vault), circuit breaker open | RFC 7807 + `Retry-After` |

**400 vs 422**: 400 cho syntax (JSON malformed, header missing); 422 cho semantic (business rule
fail). Test QA giúp phân biệt: `body="{"` → 400; `body={"email":"notanemail"}` → 422.

#### 12.0.5 Error response — RFC 7807 Problem Details

Tất cả status ≥ 400 trả body theo §15.1 (đã có). Bổ sung field cho 422:

```json
{
  "type": "https://smartquiz.vn/errors/validation-failed",
  "title": "Dữ liệu không hợp lệ",
  "status": 422,
  "code": "AUTH_VALIDATION_FAILED",
  "trace_id": "abc123",
  "timestamp": "2026-04-22T10:05:22Z",
  "errors": [
    { "field": "password", "code": "WEAK_PASSWORD", "message": "Mật khẩu phải ≥ 12 ký tự" },
    { "field": "email", "code": "INVALID_FORMAT", "message": "Email không đúng định dạng" }
  ]
}
```

FE có thể tra `errors[].field` để highlight field sai trên form.

#### 12.0.6 Idempotency

POST endpoint mutation **phải hỗ trợ** `Idempotency-Key` header (RFC draft-ietf-httpapi-idempotency-key):

- Format: UUID v4 client sinh.
- Server cache response (status + body) trong Redis `idempotency:{key}:{user_id|ip}` TTL **24h**.
- Replay cùng key trong 24h → trả response đã cache, **không** re-execute.
- Conflict (cùng key, body khác) → `409 Conflict` với code `IDEMPOTENCY_KEY_REUSE_DIFFERENT_BODY`.

Endpoint bắt buộc idempotency: `/auth/register`, `/auth/password/reset`, `/auth/mfa/setup`,
`/auth/mfa/disable`, `/admin/users/{id}/role`, `/admin/impersonate`.

Endpoint KHÔNG cần (natively idempotent): `/auth/login` (đã có rate limit), `/auth/refresh`
(token rotation tự dedupe qua `FOR UPDATE`), `/auth/logout` (set revoked = idempotent).

#### 12.0.7 Rate limit headers

Mọi response (thành công hoặc 429) của endpoint có rate limit (§9.1) trả header:

```
X-RateLimit-Limit: 10
X-RateLimit-Remaining: 7
X-RateLimit-Reset: 2026-04-22T10:20:00Z      # khi bucket reset
Retry-After: 900                              # chỉ khi 429, unit = seconds
```

Body 429:
```json
{
  "type": "https://smartquiz.vn/errors/rate-limit",
  "title": "Quá nhiều request",
  "status": 429,
  "code": "AUTH_RATE_LIMIT",
  "retry_after": 900,
  "limit": 10,
  "window": "15m",
  "trace_id": "...",
  "timestamp": "..."
}
```

#### 12.0.8 Pagination (cho list endpoints)

Convention: **cursor-based** (stable under concurrent insert). Query: `?cursor=<opaque>&limit=50`.

- `limit` max **100**, default **20**. Vượt max → cap silently hoặc `422` (chọn 422 để rõ ràng).
- Response:
  ```json
  {
    "items": [ ... ],
    "page_info": {
      "next_cursor": "eyJpZCI6IjEyMzQifQ==",   // null nếu hết trang
      "has_next": true
    }
  }
  ```
- Không dùng offset-based — trùng/thiếu khi concurrent insert/delete.
- Hiện tại Auth có list endpoints: `/admin/users`, `/admin/audit/auth`, `/auth/sessions`.

#### 12.0.9 Standard request headers

Client **nên** gửi:

| Header | Purpose | Required |
| ------ | ------- | -------- |
| `Authorization: Bearer <jwt>` | Authenticated endpoint | Có — trừ §12.1 public |
| `X-Request-Id: <uuid>` | Client request tracking; echo về response | Không — server sinh nếu thiếu |
| `Idempotency-Key: <uuid>` | POST mutation (§12.0.6) | Có — endpoint liệt kê §12.0.6 |
| `Accept-Language: vi,en;q=0.8` | i18n error messages | Không — default `vi` |
| `User-Agent` | Audit + device fingerprint (`dvh`) | Luôn gửi (browser auto) |
| `Sec-Ch-Ua*` | Client hints cho `dvh` (§5.1.2) | Browser hỗ trợ |

Server **luôn** trả:

| Header | Nội dung |
| ------ | -------- |
| `X-Request-Id` | Echo hoặc mới sinh |
| `X-Trace-Id` | OTel trace id (§14.2) |
| `Content-Type: application/json; charset=utf-8` | — |
| `Cache-Control: no-store` | Default cho mọi endpoint mutation/auth (trừ `/.well-known/*`) |
| `Strict-Transport-Security`, `X-Content-Type-Options`, … | §13.7 |

#### 12.0.10 No response envelope

Response **không wrap** trong `{data: ...}` envelope. GET resource trả resource trực tiếp;
error trả RFC 7807. Lý do: envelope thừa, FE parse 2 tầng, RFC 7807 đã chuẩn.

Ngoại lệ: list endpoint có `items + page_info` (§12.0.8) — đây không phải envelope mà là
resource "Page<T>".

### 12.1 Public endpoints

| Method | Path | Body | Response | Rate limit |
| ------ | ---- | ---- | -------- | ---------- |
| POST | `/auth/register` | `{email, password, full_name, org_slug?, invite_token?}` | `202 {email_sent: true, email_masked}` — **luôn** 202 bất kể email tồn tại hay chưa (xem §6.0) | 5/hr/IP |
| POST | `/auth/login` | `{email, password}` | `200` — token response HOẶC `{mfa_challenge}` (xem §7.2) | 10/15min/IP |
| POST | `/auth/login/mfa` | `{mfa_token, code}` | `200` — token response + `backup_codes_remaining?` + `warning?` (xem §7.2) | 5/10min |
| POST | `/auth/refresh` | `{refresh_token}` | `200 {access_token, refresh_token, expires_in}` | 30/min |
| POST | `/auth/password/forgot` | `{email}` | `200 {}` (luôn OK) | 3/hr/IP |
| POST | `/auth/password/reset` | `{token, new_password}` | `200 {}` | 5/hr/IP |
| POST | `/auth/email/verify` | `{token}` | `200 {}` | 10/hr/IP |
| GET | `/auth/oauth/{provider}` | — | `302` | 20/hr/IP |
| GET | `/auth/oauth/{provider}/callback` | `?code=&state=` | `302` | — |
| GET | `/.well-known/jwks.json` | — | `200 {keys: [...]}` (Cache 1h) | — |
| GET | `/.well-known/openid-configuration` | — | `200` | — |

### 12.2 Authenticated endpoints (yêu cầu JWT)

| Method | Path | Body | Response | Quyền |
| ------ | ---- | ---- | -------- | ----- |
| GET | `/auth/me` | — | `200 {user, orgs, mfa_enabled, ...}` | Bất kỳ |
| POST | `/auth/logout` | — | `204` | Bất kỳ |
| POST | `/auth/logout-all` | — | `204` | Bất kỳ |
| POST | `/auth/password/change` | `{old_password, new_password}` | `204` | Bất kỳ |
| POST | `/auth/mfa/setup` | — | `200 {secret, qr, backup_codes}` | mfa_enabled=false |
| POST | `/auth/mfa/verify` | `{code}` | `204` | mfa setup trước đó |
| POST | `/auth/mfa/disable` | `{password, code}` | `204` | mfa_enabled=true |
| POST | `/auth/mfa/backup/regenerate` | `{password, code}` | `200 {backup_codes}` | mfa_enabled=true |
| POST | `/auth/switch-org` | `{org_id}` | `200 {access_token, refresh_token}` | Member của org |
| GET | `/auth/sessions` | — | `200 [{id, device, ip, last_used, current}]` | Bất kỳ |
| DELETE | `/auth/sessions/{id}` | — | `204` | Bất kỳ |
| POST | `/auth/oauth/{provider}/link` | — | `302` | Bất kỳ |
| DELETE | `/auth/oauth/{provider}` | — | `204` | Bất kỳ (nếu còn cách đăng nhập khác) |

### 12.3 Admin endpoints (org-scoped)

🔒 = **sudo mode required** (recent-auth ≤ 5 phút, §5.1.1)

| Method | Path | Quyền | Sudo |
| ------ | ---- | ----- | ---- |
| GET | `/admin/users?org_id=&q=&role=&page=` | org `admin` | — |
| POST | `/admin/users/{id}/invite` | org `admin` | — |
| PATCH | `/admin/users/{id}/role` | org `admin` | 🔒 |
| POST | `/admin/users/{id}/lock` | org `admin` | 🔒 |
| POST | `/admin/users/{id}/unlock` | org `admin` | 🔒 |
| POST | `/admin/users/{id}/mfa/disable` | org `admin` (kèm audit) | 🔒 |
| DELETE | `/admin/users/{id}` | org `admin` (soft delete) | 🔒 |

### 12.4 Platform admin endpoints

| Method | Path | Quyền | Sudo |
| ------ | ---- | ----- | ---- |
| POST | `/admin/impersonate` | platform_admin | 🔒 |
| GET | `/admin/audit/auth?user_id=&from=&to=` | platform_admin | — |
| POST | `/admin/jwks/rotate` | platform_admin | 🔒 |

### 12.5 Sudo reauth & step-up endpoints

| Method | Path | Body | Response | Dùng cho |
| ------ | ---- | ---- | -------- | -------- |
| POST | `/auth/reauth` | `{password, mfa_code?}` | `200 {access_token, refresh_token}` — token mới có `auth_time = now` | Sudo tier A |
| POST | `/auth/step-up/init` | `{action, body_hash, audience}` | `200 {challenge_token}` — JWT TTL 60s, bound to body_hash + aud | Sudo tier B — xem §5.1.1 |

Rate limit:
- `/auth/reauth`: 5/10 phút/user. Fail 3 lần → buộc login lại hoàn toàn.
- `/auth/step-up/init`: 20/giờ/user (step-up workflow nhiều lần trong 1 session admin).

### 12.6 gRPC (internal)

```proto
service AuthService {
    rpc ValidateToken(ValidateTokenRequest) returns (ValidateTokenResponse);
    rpc GetUser(GetUserRequest) returns (User);
    rpc BatchGetUsers(BatchGetUsersRequest) returns (BatchGetUsersResponse);
    rpc CheckPermission(CheckPermissionRequest) returns (CheckPermissionResponse);
}
```

Dùng cho các service cần enrich user info ngoài JWT (vd: Exam Service hiển thị tên học sinh khi grade). Cache response Redis 60s.

### 12.7 Endpoint contract template — reference cho OpenAPI generation

Hai endpoint điển hình dưới làm **template** cho tất cả endpoint còn lại. Convention §12.0 apply.
OpenAPI spec đầy đủ cho mọi endpoint nằm ở `app/src/main/resources/static/openapi.yaml`
(§18.1 prereq) — là **nguồn truth** cho BE/FE codegen.

#### 12.7.1 POST /api/v1/auth/login

**Request:**

```http
POST /api/v1/auth/login HTTP/2
Host: auth.smartquiz.vn
Content-Type: application/json; charset=utf-8
Accept: application/json
X-Request-Id: 7c9e6f8b-2a3d-4e5f-b1a0-1234567890ab

{
  "email": "hs.le@hust.edu.vn",
  "password": "S3cureP@ssw0rd!"
}
```

**Request JSON Schema:**

```yaml
LoginRequest:
  type: object
  required: [email, password]
  additionalProperties: false
  properties:
    email:
      type: string
      format: email
      maxLength: 254                   # RFC 5321
      example: "hs.le@hust.edu.vn"
    password:
      type: string
      minLength: 12
      maxLength: 128
      writeOnly: true                  # không trả lại ở response
      example: "S3cureP@ssw0rd!"
```

**Response — 200 token (no MFA):**

```http
HTTP/2 200
Content-Type: application/json; charset=utf-8
X-Request-Id: 7c9e6f8b-2a3d-4e5f-b1a0-1234567890ab
X-Trace-Id: 4bf92f3577b34da6a3ce929d0e0e4736
X-RateLimit-Limit: 10
X-RateLimit-Remaining: 9
X-RateLimit-Reset: 2026-04-22T10:20:00Z
Cache-Control: no-store

{
  "access_token": "eyJhbGciOiJSUzI1NiIsImtpZCI6IjIwMjYtMDQtMDEifQ...",
  "refresh_token": "AbCd-64-char-random-base64url...",
  "token_type": "Bearer",
  "expires_in": 900,
  "refresh_expires_in": 604800
}
```

**Response — 200 MFA challenge (xem §7.2):**

```json
{
  "mfa_challenge": {
    "mfa_token": "eyJ...",
    "methods": ["totp", "backup_code"],
    "expires_in": 300
  }
}
```

**Response JSON Schema (union qua `oneOf`):**

```yaml
LoginResponse:
  oneOf:
    - $ref: '#/components/schemas/TokenResponse'
    - $ref: '#/components/schemas/MfaChallengeResponse'

TokenResponse:
  type: object
  required: [access_token, refresh_token, token_type, expires_in, refresh_expires_in]
  additionalProperties: false
  properties:
    access_token:     { type: string, description: "JWT RS256" }
    refresh_token:    { type: string, description: "Opaque 64-byte base64url" }
    token_type:       { type: string, enum: ["Bearer"] }
    expires_in:       { type: integer, format: int32, minimum: 1, description: "seconds" }
    refresh_expires_in: { type: integer, format: int32, minimum: 1 }

MfaChallengeResponse:
  type: object
  required: [mfa_challenge]
  additionalProperties: false
  properties:
    mfa_challenge:
      type: object
      required: [mfa_token, methods, expires_in]
      properties:
        mfa_token:  { type: string }
        methods:    { type: array, items: { type: string, enum: [totp, backup_code] }, minItems: 1 }
        expires_in: { type: integer, format: int32, minimum: 1 }
```

**Error responses:**

| Status | Code | Khi nào |
| ------ | ---- | ------- |
| 400 | `AUTH_MALFORMED_REQUEST` | JSON parse fail, content-type sai |
| 401 | `AUTH_INVALID_CREDENTIALS` | Sai email/password (cố tình không phân biệt) |
| 422 | `AUTH_VALIDATION_FAILED` | Email format sai, password < 12 ký tự |
| 423 | `AUTH_ACCOUNT_LOCKED` | User bị lock (§9.2) |
| 429 | `AUTH_RATE_LIMIT` | Vượt 10/15min/IP hoặc 5/15min/email |
| 500 | `AUTH_INTERNAL` | — |

Tất cả error body theo RFC 7807 (§12.0.5, §15.1).

#### 12.7.2 POST /api/v1/auth/refresh

**Request:**

```http
POST /api/v1/auth/refresh HTTP/2
Content-Type: application/json; charset=utf-8

{
  "refresh_token": "AbCd-64-char-..."
}
```

hoặc (cookie mode — §13.1):

```http
POST /api/v1/auth/refresh HTTP/2
Cookie: sq_refresh=AbCd-64-char-...
Content-Type: application/json; charset=utf-8

{}
```

**Request JSON Schema:**

```yaml
RefreshRequest:
  type: object
  additionalProperties: false
  properties:
    refresh_token:
      type: string
      minLength: 40
      maxLength: 120
      description: "Omit if using cookie mode; server reads sq_refresh cookie"
```

**Response 200:** giống `TokenResponse` §12.7.1.

Cookie mode: response đồng thời `Set-Cookie: sq_refresh=<new>; ...` (§13.1).

**Error responses:**

| Status | Code | Khi nào |
| ------ | ---- | ------- |
| 401 | `AUTH_TOKEN_INVALID` | Token không match DB |
| 401 | `AUTH_TOKEN_EXPIRED` | `expires_at < NOW()` |
| 401 | `AUTH_TOKEN_REVOKED` | Token đã revoked (xem §5.2.1 stolen detection → revoke family) |
| 429 | `AUTH_RATE_LIMIT` | 30/min/user |

#### 12.7.3 Pattern tổng hợp (cho các endpoint còn lại)

Mỗi endpoint OpenAPI spec **phải** có:

1. **Operation ID** `{verb}{Resource}` — vd `login`, `refreshToken`, `registerUser`
2. **Summary** 1 dòng + **Description** chi tiết link sang design doc section
3. **Request body schema** với `additionalProperties: false`, `required`, constraints (min/max, pattern, format)
4. **Response schema** per status code — 2xx + tất cả error 4xx áp dụng
5. **Examples** cho cả happy path và error
6. **Security requirements** — `security: [{ bearerAuth: [] }]` hoặc `[]` cho public
7. **`x-codeSamples`** (optional) cho curl/JS/Python
8. **Rate limit**: note trong description hoặc `x-ratelimit` extension

Gợi ý tooling:
- BE: `springdoc-openapi` tự-gen từ `@RestController` + `@Operation` annotations, merge với `openapi.yaml` static
- FE: `openapi-typescript-codegen` → TS client tự động (§18.5 OQ-8)
- Contract test: Spring Cloud Contract hoặc Pact — consumer-driven

---

## XIII. SECURITY HARDENING

### 13.1 Cookies (nếu dùng cookie-based auth cho web)

| Cookie | Nội dung | Thuộc tính |
| ------ | -------- | ---------- |
| `sq_access` | JWT | `HttpOnly; Secure; SameSite=Strict; Path=/api/v1` |
| `sq_refresh` | refresh token | `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth/refresh` |

> **⚠️** `Path=` là literal không có shorthand (§12.0). Nếu set `Path=/auth/refresh` mà real
> endpoint là `/api/v1/auth/refresh` → browser sẽ không gửi cookie → refresh fail silently.

Nếu dùng Bearer header (mobile / SPA không cookie) thì bỏ phần này.

### 13.2 CORS

- Chỉ cho phép origin trong whitelist (ConfigMap)
- `Access-Control-Allow-Credentials: true` nếu dùng cookie
- Preflight cache 1h

### 13.3 CSRF

Với cookie-based: double-submit pattern (token trong cookie `sq_csrf` + header `X-CSRF-Token`). Với Bearer header: không cần (không auto-send).

### 13.4 TLS

- TLS 1.3 only ở API Gateway
- Service-to-service: mTLS qua Istio
- HSTS: `max-age=31536000; includeSubDomains; preload`

### 13.5 Encryption at rest

| Dữ liệu | Cách | Key |
| ------- | ---- | --- |
| `users.mfa_secret` | AES-256-GCM (ở tầng app) | Vault `transit/auth_mfa` |
| `oauth_providers.access_token_enc` | AES-256-GCM | Vault `transit/auth_oauth` |
| PostgreSQL disk | LUKS / RDS storage encryption | AWS KMS |
| Redis RDB/AOF | Disk encryption + TLS in-transit | — |

### 13.6 Secrets rotation

| Secret | Tần suất | Cơ chế |
| ------ | -------- | ------ |
| JWT signing key | 90 ngày | Cron + dual-key overlap |
| MFA AES key | 1 năm | Re-encrypt toàn bảng (batch job ban đêm) |
| OAuth client secret | Theo provider | Manual (Vault) |
| DB password | 30 ngày | Vault Dynamic Secrets (Postgres plugin) |

### 13.7 Header an toàn (mặc định)

```
X-Content-Type-Options: nosniff
X-Frame-Options: DENY
Referrer-Policy: strict-origin-when-cross-origin
Permissions-Policy: geolocation=(), camera=()
Content-Security-Policy: default-src 'self'  (cho admin UI nếu host cùng domain)
```

---

## XIV. OBSERVABILITY

Tuân thủ stack CLAUDE.md §2: **Micrometer → Prometheus**, **OpenTelemetry OTLP** (collector
chung của cluster), **Loki** cho log push.

### 14.1 Metrics (Prometheus, expose qua `/actuator/prometheus`)

| Metric | Type | Label |
| ------ | ---- | ----- |
| `auth_login_total` | counter | `result=success\|failed\|locked`, `method=password\|oauth\|mfa` |
| `auth_login_duration_seconds` | histogram | — |
| `auth_token_issued_total` | counter | `type=access\|refresh` |
| `auth_token_revoked_total` | counter | `reason=logout\|stolen\|password_change` |
| `auth_mfa_verify_total` | counter | `result=success\|failed` |
| `auth_rate_limit_hit_total` | counter | `endpoint` |
| `auth_password_hash_duration_seconds` | histogram | — |
| `auth_oauth_callback_duration_seconds` | histogram | `provider` |
| `auth_active_sessions` | gauge | (cập nhật 30s) |
| `auth_outbox_pending_size` | gauge | — (xem §11.5) |
| `auth_outbox_publish_lag_seconds` | histogram | — (xem §11.5) |
| `auth_jwt_signing_key_age_days` | gauge | `kid` |

### 14.2 Tracing (OpenTelemetry)

- Instrument qua `io.opentelemetry.instrumentation:opentelemetry-spring-boot-starter`.
- Export OTLP gRPC → collector `otel-collector:4317`. Collector fanout sang Tempo/Jaeger.
- Span quan trọng phải set attribute: `auth.user_id`, `auth.org_id`, `auth.method`, `auth.result`.
- **Cấm** set raw email, password, token, mfa_secret làm attribute.
- Trace propagation qua gateway: header `traceparent` (W3C). Filter MDC set `trace_id` = current span trace id.

### 14.3 SLO (reference ADR-001 §1)

| SLI | Target | Ghi chú |
| --- | ------ | ------- |
| Login success latency p99 | < 400ms | Argon2id target ~250ms + overhead |
| Token refresh latency p99 | < 100ms | Không hash, chỉ verify + rotate |
| `/auth/validate` gRPC latency p99 | < 20ms | JWT verify offline |
| JWKS endpoint latency p99 | < 50ms | Cache 1h phía consumer |
| Availability | **99.95%** (ADR-001 §1: Auth là blocking dep của mọi service, SLA cao hơn platform 99.9%) | ≤ 22 phút downtime/tháng |
| Failed login rate (non-attack baseline) | < 5% | Trigger attack alert nếu > 30% |

Error budget 22 phút/tháng → PagerDuty ping nếu tiêu >50% trong 24h.

### 14.4 Logs — structured JSON + MDC

Logback encoder `net.logstash.logback:logstash-logback-encoder`. Mỗi request qua filter
`MdcFilter` set MDC keys: `trace_id`, `span_id`, `user_id` (nếu authenticated), `org_id`,
`request_id`, `client_ip`.

```json
{
  "ts": "2026-04-18T10:05:22.123Z",
  "level": "INFO",
  "service": "auth-service",
  "trace_id": "7c9e6f8b2a3d4e5f...",
  "span_id": "1a2b3c4d...",
  "request_id": "req-abc123",
  "user_id": "a0000000-0000-0000-0000-000000000004",
  "org_id": "11111111-1111-1111-1111-111111111111",
  "event": "login.success",
  "method": "password",
  "mfa_used": true,
  "client_ip": "1.2.3.4",
  "ua": "Mozilla/..."
}
```

**Masking filter bắt buộc** (Logback `MaskingPatternLayout`):
- Regex mask: `password`, `new_password`, `old_password`, `access_token`, `refresh_token`, `mfa_secret`, `code` (TOTP), `client_secret` → `***REDACTED***`
- Email → mask phần local: `h***@hust.edu.vn` (chỉ giữ domain cho debug)
- Log raw body field chứa các key trên → bị filter chặn ngay tại encoder.

Log ship qua promtail → Loki (retention 14 ngày). Index theo `service`, `level`, `event`, `user_id`.

### 14.5 Alerts

| Alert | Điều kiện | Severity |
| ----- | --------- | -------- |
| `AuthServiceDown` | up == 0 trong 2 phút | critical |
| `HighLoginFailRate` | rate(auth_login_total{result="failed"}[5m]) / rate(auth_login_total[5m]) > 0.3 | warning |
| `JWKSRotationOverdue` | `auth_jwt_signing_key_age_days` > 100 | warning |
| `MFAFailSpike` | failed MFA > 10/min cho 1 user | critical (possible attack) |
| `DatabaseLatencyHigh` | p99 > 200ms trong 5 phút | warning |
| `OutboxBacklog` | `auth_outbox_pending_size` > 10k | critical (ADR-001 RPO violation) |
| `OutboxPublishLag` | `auth_outbox_publish_lag_seconds` p99 > 5s | critical |
| `SLOBurnRate` | Error budget tiêu > 2%/hour (fast burn) | critical |

---

## XV. ERROR HANDLING

### 15.1 Format lỗi chuẩn (RFC 7807 Problem Details)

```json
{
  "type": "https://smartquiz.vn/errors/invalid-credentials",
  "title": "Email hoặc mật khẩu không đúng",
  "status": 401,
  "code": "AUTH_INVALID_CREDENTIALS",
  "trace_id": "abc123",
  "timestamp": "2026-04-18T10:05:22Z"
}
```

### 15.2 Bảng mã lỗi

| Code | HTTP | Ý nghĩa | Khi nào dùng |
| ---- | ---- | ------- | ------------ |
| `AUTH_MALFORMED_REQUEST` | 400 | JSON parse fail, header missing, content-type sai | Không fail validation semantic — xem `AUTH_VALIDATION_FAILED` |
| `AUTH_VALIDATION_FAILED` | 422 | Semantic validation fail (email format, password policy…) | Body có `errors[]` field-level — §12.0.5 |
| `AUTH_INVALID_CREDENTIALS` | 401 | Sai email/password | Login fail (cố tình không phân biệt nguyên nhân → chống enum) |
| `AUTH_MFA_REQUIRED` | 403 | Endpoint yêu cầu `mfa_passed=true` trong claim nhưng token không có | **KHÔNG** dùng cho `/auth/login` (xem §7.2 response contract). Chỉ cho endpoint service khác check step-up MFA, vd `attempt.submit` khi exam yêu cầu MFA. |
| `AUTH_MFA_INVALID` | 401 | Sai TOTP code | — |
| `AUTH_ACCOUNT_LOCKED` | 423 | Bị khóa tạm thời | — |
| `AUTH_EMAIL_NOT_VERIFIED` | 403 | Chưa verify email | — |
| `AUTH_TOKEN_EXPIRED` | 401 | JWT hết hạn | — |
| `AUTH_TOKEN_INVALID` | 401 | JWT sai chữ ký / format | — |
| `AUTH_TOKEN_REVOKED` | 401 | Token đã bị thu hồi | — |
| `AUTH_RATE_LIMIT` | 429 | Vượt quota | Header `Retry-After`, `X-RateLimit-*`. Body kèm `retry_after, limit, window` — §12.0.7 |
| `IDEMPOTENCY_KEY_REUSE_DIFFERENT_BODY` | 409 | `Idempotency-Key` đã dùng với body khác | §12.0.6 |
| `AUTH_WEAK_PASSWORD` | 422 | Password không đạt policy | — |
| `AUTH_EMAIL_EXISTS` | 409 | Email đã đăng ký | **Chỉ dùng nội bộ** (admin invite, account merge). KHÔNG trả cho `/auth/register` — xem §6.0 chống enumeration |
| `AUTH_OAUTH_STATE_MISMATCH` | 400 | state không khớp Redis | — |
| `AUTH_OAUTH_ALREADY_LINKED` | 409 | Provider account đã link với user khác | — |
| `AUTH_OAUTH_LAST_METHOD` | 409 | Không thể unlink — đây là phương thức đăng nhập duy nhất | FE gợi ý set password trước |
| `AUTH_FORBIDDEN` | 403 | Thiếu permission | — |
| `AUTH_SUDO_REQUIRED` | 403 | Cần re-auth gần đây (sudo mode tier A) | Body kèm `reauth_url`. FE mở modal nhập password + MFA |
| `AUTH_STEPUP_REQUIRED` | 400 | Cần step-up challenge token (sudo mode tier B) | Body kèm `init_url = /api/v1/auth/step-up/init` |
| `AUTH_STEPUP_INVALID` | 403 | Step-up token sai/expired/body_hash mismatch/jti reused | — |
| `AUTH_DEVICE_MISMATCH` | 401 | `dvh` claim không match header request | Có thể do user đổi browser profile / header spoof |
| `AUTH_INTERNAL` | 500 | Lỗi hệ thống | Log chi tiết, trace_id |

---

## XVI. DEPLOYMENT & INFRASTRUCTURE

### 16.1 Kubernetes manifest (tóm tắt)

```yaml
apiVersion: apps/v1
kind: Deployment
metadata: { name: auth-service, namespace: smartquiz }
spec:
  replicas: 3
  strategy: { type: RollingUpdate, rollingUpdate: { maxSurge: 1, maxUnavailable: 0 } }
  template:
    spec:
      containers:
        - name: auth
          image: registry.smartquiz.vn/auth-service:1.0.0
          ports:
            - { name: http, containerPort: 3001 }
            - { name: grpc, containerPort: 4001 }
            - { name: mgmt, containerPort: 9001 }
          env:
            - { name: SPRING_PROFILES_ACTIVE, value: prod }
            - { name: VAULT_ADDR, value: "https://vault:8200" }
          envFrom:
            - configMapRef: { name: auth-config }
            - secretRef:    { name: auth-secrets }
          resources:
            requests: { cpu: 500m, memory: 512Mi }
            limits:   { cpu: 2,    memory: 1Gi }
          livenessProbe:
            httpGet: { path: /actuator/health/liveness, port: mgmt }
            initialDelaySeconds: 30
          readinessProbe:
            httpGet: { path: /actuator/health/readiness, port: mgmt }
            periodSeconds: 5
          startupProbe:
            httpGet: { path: /actuator/health/readiness, port: mgmt }
            failureThreshold: 30
```

+ `HorizontalPodAutoscaler` (CPU 70% / memory 80%, min 3, max 20)
+ `PodDisruptionBudget` minAvailable=2
+ `NetworkPolicy` chỉ cho ingress từ API Gateway + egress đến Postgres/Redis/Kafka/Vault

### 16.2 Cấu hình môi trường

| Key | Prod | Dev |
| --- | ---- | --- |
| `DB_URL` | Vault secret | `jdbc:postgresql://localhost:5432/smartquiz` |
| `REDIS_URL` | `redis://redis:6379/0` | `redis://localhost:6379/0` |
| `KAFKA_BROKERS` | `kafka-0:9092,kafka-1:9092,kafka-2:9092` | `localhost:9092` (hoặc disabled) |
| `JWT_KEY_VAULT_PATH` | `transit/smartquiz-jwt` | file local `./dev-jwt.pem` |
| `MFA_AES_KEY_VAULT_PATH` | `transit/smartquiz-mfa` | env `MFA_AES_KEY` |
| `OAUTH_GOOGLE_CLIENT_ID` | Vault | env |
| `OAUTH_GOOGLE_CLIENT_SECRET` | Vault | env |
| `PLATFORM_ADMIN_EMAILS` | CSV | CSV |

### 16.3 Scaling & thread model

- Stateless service (trạng thái ở PG + Redis) → scale horizontal thoải mái
- **I/O path** (HTTP request, Redis, JDBC, Kafka, OAuth WebClient): Virtual Threads via
  `spring.threads.virtual.enabled=true`. Không cần tuning pool size.
- **Argon2 hash pool** (CPU-bound): bounded platform threads, **không** dùng VT. Cấu hình:

  ```yaml
  auth:
    hash:
      pool-size: 4          # = vCPU count (match container limit)
      queue-capacity: 256   # overflow → 503 Service Unavailable
      reject-policy: caller-runs  # backpressure
  ```

  ```java
  @Bean(name = "argon2Executor")
  ThreadPoolTaskExecutor argon2Executor(@Value("${auth.hash.pool-size}") int cores,
                                        @Value("${auth.hash.queue-capacity}") int queue) {
      var exec = new ThreadPoolTaskExecutor();
      exec.setCorePoolSize(cores);
      exec.setMaxPoolSize(cores);             // cap = vCPU
      exec.setQueueCapacity(queue);
      exec.setThreadNamePrefix("argon2-");
      exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
      return exec;
  }
  ```

  Login UseCase: `CompletableFuture.supplyAsync(() -> hasher.verify(pw, hash), argon2Executor)`.

- Peak load dự kiến: 10k login/phút lúc thi cao điểm → ~167 hash/s × 250ms = cần ~42 CPU-second/s
  → **~8 pod × 2 vCPU** (half capacity cho hash + half cho I/O + overhead).
- HPA theo CPU 70% → scale out tự động khi peak thi.

### 16.4 Disaster recovery

| Scenario | RPO | RTO | Biện pháp |
| -------- | --- | --- | --------- |
| Mất 1 pod | 0 | < 5s | K8s reschedule |
| Mất cả cluster | < 1 phút | < 15 phút | Multi-region K8s, Vault sync |
| Mất Postgres | < 1 phút | < 15 phút | Patroni + streaming replication + PITR |
| Mất Redis | 0 (critical data vẫn ở PG) | < 2 phút | Redis Sentinel/Cluster |
| Mất JWT private key | N/A | < 30 phút | Rotate key khẩn cấp, revoke tất cả active token |

---

## XVII. TESTING STRATEGY

### 17.1 Pyramid + coverage gate (JaCoCo)

```
          E2E (10%)  ← login flow đầy đủ, OAuth mocking
       Integration (30%)  ← Testcontainers: PG + Redis + Kafka + Apicurio
   Unit tests (60%)  ← domain logic, validation, hash, TOTP
```

| Layer | JaCoCo gate (line coverage) |
| ----- | --------------------------- |
| `domain/*` (pure logic, no IO) | ≥ **80%** |
| `application/*` (UseCase) | ≥ **70%** |
| `infrastructure/*` | best-effort (integration test phủ) |
| Global | ≥ **75%** |

CI fail nếu coverage regress > 2% so với baseline branch `main`.

### 17.2 Test tools

| Layer | Tool |
| ----- | ---- |
| Unit | JUnit 5, AssertJ, Mockito |
| Integration | **Testcontainers** (PG 16, Redis 7, Confluent Kafka, Apicurio), **WireMock** (OAuth stub) |
| Contract | **Spring Cloud Contract** (producer-side stub cho service consumer) + Avro compat check qua Apicurio |
| Security | OWASP ZAP baseline scan trong CI; Burp Suite định kỳ; `@security-engineer` review trước merge |
| Load | k6 (target 5k RPS login, 20k RPS token refresh) |

### 17.3 Integration test bắt buộc — outbox + Kafka (ADR-001 §impl.5)

```java
@SpringBootTest
@Testcontainers
class PasswordChangeOutboxIntegrationTest {
    @Container static PostgreSQLContainer<?> pg = new PostgreSQLContainer<>("postgres:16");
    @Container static KafkaContainer kafka = new KafkaContainer(...);
    @Container static GenericContainer<?> apicurio = ...;

    @Test
    void password_change_publishes_event_even_if_relayer_crashes_mid_flight() {
        // given: user logged in
        userService.changePassword(userId, "NewStr0ng!Pass");

        // when: simulate relayer crash before publish
        relayerTestHook.pauseBefore(PublishStage.KAFKA_SEND);
        Thread.sleep(500);
        relayerTestHook.resume();

        // then: event eventually on Kafka exactly once
        await().atMost(10, SECONDS).untilAsserted(() -> {
            List<ConsumerRecord<String, GenericRecord>> records =
                kafkaConsumer.poll("auth.password.changed.v1");
            assertThat(records).hasSize(1);
            assertThat(records.get(0).key()).isEqualTo(userId.toString());
        });
        // outbox row marked published_at NOT NULL
        assertThat(outboxRepo.pendingCount()).isZero();
    }
}
```

### 17.4 Security test cases bắt buộc

- [ ] SQL injection qua email/password field
- [ ] Timing attack: login thành công vs thất bại (dùng `assertThat(duration).isCloseTo(..., within(20ms))`)
- [ ] User enumeration qua `/auth/password/forgot`
- [ ] JWT với `alg:none`, `alg:HS256` (confused deputy)
- [ ] JWT signed bằng public key (confused deputy)
- [ ] Refresh token reuse detection (fire 2 concurrent refresh với cùng token → chỉ 1 success, revoke all)
- [ ] OAuth state/nonce bypass
- [ ] OAuth PKCE downgrade (thiếu `code_verifier`)
- [ ] TOTP replay (dùng lại code trong window)
- [ ] TOTP brute-force (rate limit bắt trong 5 lần)
- [ ] Race condition đổi password trong khi refresh
- [ ] CSRF với form login (nếu cookie-based)
- [ ] Clickjacking trên UI OAuth consent
- [ ] Outbox poisoning: payload malformed → relayer retry N lần rồi mark last_error, không block queue
- [ ] Consumer idempotency: re-deliver cùng event 3 lần → `processed_events` dedup OK

---

## XVIII. ROADMAP & OPEN QUESTIONS

### 18.1 Gate trước khi scaffold (CLAUDE.md §9)

**Phải xong trước khi bắt tay code UseCase đầu tiên:**

- [x] Schema PostgreSQL (`database/postgresql/schema.sql` §2a, §8, §13)
- [x] ADR-001 (SLA + RPO + outbox)
- [ ] **OpenAPI 3.1 spec** (`app/src/main/resources/static/openapi.yaml`) — đủ endpoint MVP §18.2, reviewed trước khi FE code login (CLAUDE.md §9.3)
- [ ] **`ops/gen-jwt-keypair.sh`** sinh RSA 4096 keypair local dev (CLAUDE.md §9.5)
- [ ] Avro schema MVP trong `shared-contracts/avro/auth/`: `auth.user.registered.v1`, `auth.password.changed.v1`, `auth.role.changed.v1`, `auth.account.locked.v1`
- [ ] Register Avro schema lên Apicurio dev instance (BACKWARD compat)
- [ ] `shared-outbox-starter` Gradle plugin stub (helper dùng chung 5 service Java — ADR-001 §consequences)
- [ ] **Schema master sync** — apply block "Schema delta v1.2" bên dưới.

#### Schema delta v1.2 — đã merge vào `database/postgresql/schema.sql` (2026-04-22)

> Đây là **single source** cho mọi schema change thuộc Auth v1.2. Khi có thêm thay đổi, APPEND
> vào block này, đừng rải rác ở các section khác của doc.

1. **`refresh_tokens`** (schema `§5`, line 194-214) — family-tree stolen detection (§5.2.1): ✅ merged
   - `+ family_id UUID NOT NULL`
   - `+ rotated_from_id UUID REFERENCES refresh_tokens(id) ON DELETE SET NULL`
   - `+ rotated_to_id   UUID REFERENCES refresh_tokens(id) ON DELETE SET NULL`
   - `+ CREATE INDEX idx_refresh_family ON refresh_tokens(family_id, revoked)`
   - Flyway migration delta cho prod: `V{epoch}__auth_refresh_family.sql` (khi scaffold app).

2. **`email_verification_tokens`** (schema `§10`, line 529-538) — OAuth link + change email (§8.3, OQ-5): ✅ merged
   - `+ payload JSONB NULL`
   - Extend `purpose` values (app-level enum, DDL giữ VARCHAR(20) để extend dễ): `link_oauth`, `change_email`
   - Flyway migration delta cho prod: `V{epoch}__auth_evt_payload.sql` (khi scaffold app).

3. **Correction `mfa_backup_codes.code_hash`** — schema master đã khuyến nghị **Argon2id**, doc đã sync (§4.3).

4. **Không cần đổi** (đã đủ v1.2): `users`, `oauth_providers`, `password_history`, `audit_log_auth`,
   `outbox`, `processed_events`.

Schema master version tương ứng: `database/postgresql/schema.sql` (2026-04-22 post auth v1.2 merge).
Khi prod đã migrate lần đầu, các item delta trên phải biến thành Flyway file _immutable_ — KHÔNG sửa row 1-2 trong block này nữa, thay vào đó append item mới cho v1.3.

### 18.2 MVP (tháng 4-5/2026)

- [ ] Flyway migration V1 cho delta schema (nếu có — schema master đã merge)
- [ ] Register / login / refresh / logout (email + password)
- [ ] JWT RS256 + JWKS (key từ file local MVP, Vault Phase 2)
- [ ] Rate limit Redis (Lua atomic)
- [ ] Email verify flow (qua Notification Service stub)
- [ ] **Outbox relayer** chạy inside pod với advisory-lock leader election
- [ ] Publish `auth.user.registered.v1`, `auth.password.changed.v1` qua outbox
- [ ] gRPC `ValidateToken` + `GetUser` cho Exam/Question consume

### 18.3 Phase 2 (tháng 6/2026)

- [ ] MFA TOTP + backup codes (AES-256-GCM secret, key Vault)
- [ ] OAuth Google + Microsoft (PKCE + state)
- [ ] Admin endpoints org-scoped (`/admin/users/*`)
- [ ] Publish nốt event critical: `auth.role.changed.v1`, `auth.account.locked.v1`, `auth.mfa.*.v1`
- [ ] Fire-and-forget publisher cho `auth.login.success/failed.v1`
- [ ] Vault integration (Transit engine cho JWT sign + AES MFA)

### 18.4 Phase 3 (Q3/2026)

- [ ] SSO SAML cho enterprise
- [ ] WebAuthn / Passkey
- [ ] Risk-based auth (IP geoloc, device fingerprint → step-up MFA)
- [ ] Audit log export (SIEM, Splunk)
- [ ] Custom role per org (Phase 2 của RBAC — §3.5)

### 18.5 Open questions

1. **Single sign-on giữa web + mobile:** dùng chung refresh token hay tách? → Tách (mobile dài hạn 30 ngày, web 7 ngày)
2. **Session list có cần show trên UI?** → Có (Phase 2), để user thấy và revoke
3. **Auto email verify khi invite từ admin:** có skip verification không? → Có (invite token đã pre-verify)
4. **Tích hợp Vault hay dùng K8s Secret MVP?** → K8s Secret cho MVP, migrate Vault Phase 2
5. **Cho phép user đổi email?** → Có, nhưng phải verify email mới trước khi apply
6. **Xử lý user xoá tài khoản (GDPR):** anonymize vs hard delete? → Anonymize (giữ `attempt_answers` để báo cáo). Event `auth.user.deleted.v1` có flag `anonymized=true`.
7. **Outbox relayer leader election:** `pg_try_advisory_lock` (đã chọn) vs Kafka consumer group. Nếu scale > 10 pod cần xem lại — Phase 2.
8. **OpenAPI codegen cho FE:** generate TS client tự động từ `openapi.yaml` hay viết tay? → Generate (`openapi-typescript-codegen`), lock version.

---

_Tài liệu thuộc Hệ Thống Thi Trực Tuyến Thông Minh — Auth Service Design v1.5 — Tháng 4/2026._
