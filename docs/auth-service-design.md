# AUTH SERVICE - THIẾT KẾ CHI TIẾT

**Hệ Thống Thi Trực Tuyến Thông Minh** | Version 1.0 | Tháng 4/2026

Tài liệu này mở rộng mục "Auth Service" trong `design.md`, mô tả chi tiết ở mức đủ để triển khai code.

---

## I. TỔNG QUAN

### 1.1 Vai trò

Auth Service là **cổng danh tính duy nhất** (single source of identity) cho toàn hệ thống. Các service khác không tự quản lý user/password — chỉ **xác minh token** do Auth Service cấp thông qua JWKS.

| Trách nhiệm | Không thuộc phạm vi |
| ----------- | ------------------- |
| Đăng ký, đăng nhập, đăng xuất | Quản lý hồ sơ người dùng chi tiết (ảnh, địa chỉ — thuộc User Profile Service sau này) |
| Xác thực 2 yếu tố (TOTP) | Gửi email/SMS (uỷ quyền Notification Service) |
| OAuth2/SSO (Google, Microsoft, GitHub) | Thanh toán, gói dịch vụ |
| RBAC — phân quyền theo 4 role | Phân quyền ở mức cột (row-level nằm ở từng service domain) |
| Cấp JWT access + refresh token | Business logic bài thi / câu hỏi |
| Thu hồi token tức thì | Log audit chi tiết (chỉ publish event, consumer là Analytics) |
| Rate limit chống brute-force | Anti-bot (Turnstile / reCAPTCHA do Gateway xử lý) |

### 1.2 Stack công nghệ

| Thành phần | Chọn | Lý do |
| ---------- | ---- | ----- |
| Runtime | Java 21 (LTS) + Spring Boot 3.2 | Thống nhất với các service Java khác |
| Framework | Spring Security 6 + Spring Web MVC | OAuth2 Resource Server, filter chain trưởng thành |
| ORM | Spring Data JPA + Hibernate 6 | Truy cập PostgreSQL |
| Redis client | Lettuce (async, non-blocking) | Tích hợp Spring Data Redis |
| JWT lib | `com.nimbusds:nimbus-jose-jwt` | Ký/verify RS256, JWKS export |
| Password hash | `de.mkammerer:argon2-jvm` | Wrapper Argon2id chuẩn |
| TOTP | `dev.samstevens.totp:totp` | RFC 6238 |
| HTTP client OAuth | Spring `WebClient` | Non-blocking, retry, metrics |
| Event bus | Spring Kafka | Phát event sang Analytics, Audit |
| Observability | Micrometer + OpenTelemetry Java Agent | Metrics + distributed tracing |
| Secret store | HashiCorp Vault (Kubernetes auth) | JWT signing key, AES key, OAuth client secret |

### 1.3 Cổng & giao thức

| Giao thức | Port | Mục đích |
| --------- | ---- | -------- |
| HTTP/1.1 + HTTP/2 (REST) | `3001` | Client-facing qua API Gateway |
| gRPC | `4001` | Internal service-to-service (ValidateToken, GetUser) |
| JWKS public endpoint | `3001/.well-known/jwks.json` | Cho các service verify JWT offline |
| Actuator (health, metrics) | `9001` | Prometheus scraping + K8s probes |

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
│  ─ KafkaEventPublisher, VaultSecretLoader, NotificationGrpc│
└────────────────────────────────────────────────────────────┘
```

### 2.2 Module Gradle

```
auth-service/
├── build.gradle.kts
├── api-grpc/              # .proto + generated stubs (shared với service khác)
├── src/main/java/vn/smartquiz/auth/
│   ├── AuthServiceApplication.java
│   ├── config/            # SecurityConfig, RedisConfig, KafkaConfig, JwkConfig
│   ├── web/               # @RestController
│   ├── grpc/              # gRPC server
│   ├── application/       # UseCase
│   ├── domain/
│   │   ├── user/          # User, Role, OrgMembership (aggregate)
│   │   ├── token/         # AccessToken, RefreshToken (value)
│   │   └── mfa/           # MfaSecret, BackupCode
│   ├── infrastructure/
│   │   ├── persistence/   # JPA entities + repo
│   │   ├── redis/
│   │   ├── oauth/
│   │   ├── kafka/
│   │   └── vault/
│   └── common/            # Exception, ErrorCode
└── src/test/java/...
```

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
    private String tokenHash;          // SHA-256, không lưu plaintext
    private String deviceFingerprint;
    private String userAgent;
    private InetAddress ipAddress;
    private Instant expiresAt;
    private boolean revoked;
    private Instant revokedAt;
    private Instant createdAt;
    private UUID rotatedFromId;        // link đến token trước khi xoay
}
```

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
| `user.impersonate` / `ai.prompt.manage` / `cheat.config.weights` | **platform-scope** — chỉ platform_admin | | | |

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

## IV. DATA MODEL (chi tiết bổ sung schema)

> **Mapping với schema thực tế:** Tất cả bảng trong section này đã có trong `database/postgresql/schema.sql` (mục 7 "Auth Service mở rộng").

Ngoài các bảng đã có trong `database.md` (`users`, `user_organizations`, `oauth_providers`, `refresh_tokens`), Auth Service dùng thêm:

### 4.1 `password_history` (chống tái sử dụng mật khẩu)

| Cột | Kiểu | Ghi chú |
| --- | ---- | ------- |
| id | UUID PK | |
| user_id | UUID FK → users | |
| password_hash | VARCHAR(255) | Argon2id |
| changed_at | TIMESTAMPTZ DEFAULT NOW() | |

Policy: cấm dùng lại 5 mật khẩu gần nhất.

### 4.2 `mfa_backup_codes`

| Cột | Kiểu | Ghi chú |
| --- | ---- | ------- |
| id | UUID PK | |
| user_id | UUID FK | |
| code_hash | VARCHAR(64) | SHA-256 |
| used_at | TIMESTAMPTZ NULL | khi nào dùng |
| created_at | TIMESTAMPTZ | |

10 code/user, mỗi code chỉ dùng 1 lần, có thể regenerate.

### 4.3 `email_verification_tokens`

| Cột | Kiểu | Ghi chú |
| --- | ---- | ------- |
| token_hash | VARCHAR(64) PK | SHA-256 của token 32 bytes random |
| user_id | UUID FK | |
| purpose | VARCHAR(20) | `verify_email` / `reset_password` |
| expires_at | TIMESTAMPTZ | 24h verify, 1h reset |
| used_at | TIMESTAMPTZ NULL | |

### 4.4 `audit_log_auth` (ghi đè ngoài log stdout)

Chỉ ghi 7 loại event quan trọng: `login_success`, `login_failed`, `password_changed`, `mfa_enabled`, `mfa_disabled`, `role_changed`, `account_locked`. Table partition theo tháng, giữ 12 tháng.

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
  "exp": 1713420900,
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
  "token_type": "access"
}
```

- `exp - iat = 900` (15 phút)
- `org_id` là org active tại thời điểm phát hành (user có thể switch bằng endpoint `/auth/switch-org` → phát token mới)
- `mfa_passed` = true nếu user đã pass MFA trong session; cho phép service nhạy cảm (Exam) từ chối nếu `mfa_passed=false` ở các thao tác quan trọng

**Signing key lifecycle:**
- 1 active key + 1 previous key (grace period) đăng trên JWKS
- Rotate mỗi **90 ngày** (cron job)
- Private key lưu ở Vault Transit Engine (không bao giờ rời Vault — Auth Service gọi Vault để ký); hoặc lưu ở K8s Secret mount file (ít an toàn hơn — chỉ dùng MVP)

### 5.2 Refresh Token — Opaque

- Sinh: 64 byte random base64url-encoded (`openssl rand -base64 48`)
- Lưu: **hash SHA-256** vào PG (`refresh_tokens.token_hash`) + Redis (cho check revoked nhanh)
- TTL: **7 ngày**, **sliding** (mỗi lần refresh, đổi token mới + TTL reset)
- Rotation policy: mỗi lần `/auth/refresh` gọi thành công → **cũ bị thu hồi**, cấp mới. Nếu cùng 1 refresh token dùng 2 lần → **stolen detection** → thu hồi toàn bộ refresh token của user đó + bắt login lại.

### 5.3 Token Revocation

| Scenario | Cơ chế |
| -------- | ------ |
| Logout 1 device | Set `revoked=true` trên `refresh_tokens` + thêm `jti` access token vào Redis blacklist với TTL = thời gian còn lại |
| Logout tất cả device | UPDATE `refresh_tokens SET revoked=true WHERE user_id=?` + publish event `auth.all_sessions_revoked` (các service có thể bỏ qua nếu muốn) |
| Password change | Revoke tất cả refresh token + yêu cầu login lại |
| Account bị lock | Như trên |
| Stolen refresh detect | Như trên + flag user để review thủ công |

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
   → Verify token (hash, chưa dùng, chưa hết hạn)
   → Check policy
   → Cập nhật password_hash + insert vào password_history
   → Mark token used_at
   → Revoke tất cả refresh token của user (force re-login)
   → Publish event auth.password.changed
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
   → Verify TOTP với secret tạm thời (window = ±1 × 30s)
   → Nếu đúng: set mfa_enabled=true, insert 10 backup codes (hashed) vào mfa_backup_codes
   → Publish event auth.mfa.enabled
```

### 7.2 Login flow có MFA

```
1. POST /auth/login { email, password }
   → Nếu user.mfa_enabled:
       Return 200 với partial response:
       { "mfa_required": true, "mfa_token": "short-lived-token-5min" }
   → Nếu không:
       Return tokens luôn

2. POST /auth/login/mfa { mfa_token, code }
   → Verify mfa_token (short-lived JWT, chỉ dùng cho MFA step)
   → Verify TOTP code HOẶC backup code
     (nếu backup code: mark used_at, giảm count)
   → Nếu < 3 backup codes còn: include warning trong response
   → Return full access + refresh token (claim mfa_passed=true)
```

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

### 8.3 Link nhiều provider

User đang login có thể POST `/auth/oauth/{provider}/link` → same flow nhưng finaly thêm vào `oauth_providers` thay vì đăng nhập.

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

```json
{
  "sub": "...",
  "org_id": "...",
  "orgs": [{ "id": "...", "role_code": "instructor" }],
  "authorities": [
    "exam.read","exam.create","exam.update.own","exam.publish",
    "question.create","question.update.own","question.approve",
    "attempt.grade","analytics.exam", ...
  ],
  "platform_role": null
}
```

Khi user có custom role: `authorities` tự động phản ánh permission của role đó — không cần deploy lại service.

### 10.3 Platform admin

- Không trong `user_organizations`, mà trong claim `platform_role: "super_admin"` hoặc trong bảng `platform_admins(user_id, created_at)`
- Khi có claim này: bỏ qua org scope, xem được toàn hệ thống
- Mọi action phải log audit kèm `actor=platform_admin, impersonated=<user_id>` (nếu có)

### 10.4 Impersonation (dành cho support)

Platform admin có thể POST `/admin/impersonate { target_user_id, reason, duration_minutes }`:
- Cấp JWT đặc biệt với claims `sub = target_user, impersonator = <admin_id>, imp_exp = now+15m`
- Mọi service khi thấy claim `impersonator` → log mọi request vào audit stream riêng
- Action nhạy cảm (đổi password, thu hồi cert) bị chặn khi đang impersonate

---

## XI. EVENTS PUBLISH LÊN KAFKA

| Topic | Key | Payload | Consumer |
| ----- | --- | ------- | -------- |
| `auth.user.registered` | user_id | `{user_id, email, org_id, source: "email"|"oauth", provider?}` | Notification (welcome email), Analytics |
| `auth.user.deleted` | user_id | `{user_id, reason}` | Exam (archive attempt), GDPR export |
| `auth.login.success` | user_id | `{user_id, ip, user_agent, mfa_used, provider}` | Analytics, Fraud detection |
| `auth.login.failed` | email (hash) | `{email_hash, ip, reason, consecutive_count}` | Fraud detection (IP reputation) |
| `auth.password.changed` | user_id | `{user_id, triggered_by: "user"|"reset"|"admin"}` | Notification, Audit |
| `auth.mfa.enabled` / `.disabled` | user_id | `{user_id}` | Notification |
| `auth.role.changed` | user_id | `{user_id, org_id, old_role, new_role, changed_by}` | Audit, Cache invalidation |
| `auth.account.locked` | user_id | `{user_id, until, reason}` | Notification |

Producer config: `acks=all`, `enable.idempotence=true`, `max.in.flight=5`.

---

## XII. API ENDPOINTS CHI TIẾT

### 12.1 Public endpoints

| Method | Path | Body | Response | Rate limit |
| ------ | ---- | ---- | -------- | ---------- |
| POST | `/auth/register` | `{email, password, full_name, org_slug?, invite_token?}` | `201 {user_id, email_verification_sent: true}` | 5/hr/IP |
| POST | `/auth/login` | `{email, password}` | `200 {access_token, refresh_token, expires_in, mfa_required?}` | 10/15min/IP |
| POST | `/auth/login/mfa` | `{mfa_token, code}` | `200 {access_token, refresh_token, expires_in}` | 5/10min |
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

| Method | Path | Quyền |
| ------ | ---- | ----- |
| GET | `/admin/users?org_id=&q=&role=&page=` | org `admin` |
| POST | `/admin/users/{id}/invite` | org `admin` |
| PATCH | `/admin/users/{id}/role` | org `admin` |
| POST | `/admin/users/{id}/lock` | org `admin` |
| POST | `/admin/users/{id}/unlock` | org `admin` |
| POST | `/admin/users/{id}/mfa/disable` | org `admin` (kèm audit) |
| DELETE | `/admin/users/{id}` | org `admin` (soft delete) |

### 12.4 Platform admin endpoints

| Method | Path | Quyền |
| ------ | ---- | ----- |
| POST | `/admin/impersonate` | platform_admin |
| GET | `/admin/audit/auth?user_id=&from=&to=` | platform_admin |
| POST | `/admin/jwks/rotate` | platform_admin |

### 12.5 gRPC (internal)

```proto
service AuthService {
    rpc ValidateToken(ValidateTokenRequest) returns (ValidateTokenResponse);
    rpc GetUser(GetUserRequest) returns (User);
    rpc BatchGetUsers(BatchGetUsersRequest) returns (BatchGetUsersResponse);
    rpc CheckPermission(CheckPermissionRequest) returns (CheckPermissionResponse);
}
```

Dùng cho các service cần enrich user info ngoài JWT (vd: Exam Service hiển thị tên học sinh khi grade). Cache response Redis 60s.

---

## XIII. SECURITY HARDENING

### 13.1 Cookies (nếu dùng cookie-based auth cho web)

| Cookie | Nội dung | Thuộc tính |
| ------ | -------- | ---------- |
| `sq_access` | JWT | `HttpOnly; Secure; SameSite=Strict; Path=/` |
| `sq_refresh` | refresh token | `HttpOnly; Secure; SameSite=Strict; Path=/auth/refresh` |

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

### 14.2 SLO

| SLI | Target |
| --- | ------ |
| Login success latency p99 | < 400ms |
| Token refresh latency p99 | < 100ms |
| Availability | 99.95% (≤ 22 phút downtime / tháng) |
| Failed login rate (non-attack) | < 5% |

Error budget 22 phút/tháng → PagerDuty ping nếu tiêu >50% trong 24h.

### 14.3 Logs (JSON structured)

```json
{
  "ts": "2026-04-18T10:05:22.123Z",
  "level": "INFO",
  "service": "auth-service",
  "trace_id": "...",
  "event": "login.success",
  "user_id": "...",
  "org_id": "...",
  "ip": "1.2.3.4",
  "ua": "Mozilla/...",
  "mfa_used": true
}
```

Log email, password, token, mfa_secret → **cấm tuyệt đối**. Dùng masking filter ở Logback.

### 14.4 Alerts

| Alert | Điều kiện | Severity |
| ----- | --------- | -------- |
| `AuthServiceDown` | up == 0 trong 2 phút | critical |
| `HighLoginFailRate` | rate(auth_login_total{result="failed"}[5m]) / rate(auth_login_total[5m]) > 0.3 | warning |
| `JWKSRotationOverdue` | signing_key_age_days > 100 | warning |
| `MFAFailSpike` | failed MFA > 10/min cho 1 user | critical (possible attack) |
| `DatabaseLatencyHigh` | p99 > 200ms trong 5 phút | warning |

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
| `AUTH_INVALID_CREDENTIALS` | 401 | Sai email/password | Login fail (cố tình không phân biệt nguyên nhân → chống enum) |
| `AUTH_MFA_REQUIRED` | 200 | Cần MFA (kèm mfa_token) | — |
| `AUTH_MFA_INVALID` | 401 | Sai TOTP code | — |
| `AUTH_ACCOUNT_LOCKED` | 423 | Bị khóa tạm thời | — |
| `AUTH_EMAIL_NOT_VERIFIED` | 403 | Chưa verify email | — |
| `AUTH_TOKEN_EXPIRED` | 401 | JWT hết hạn | — |
| `AUTH_TOKEN_INVALID` | 401 | JWT sai chữ ký / format | — |
| `AUTH_TOKEN_REVOKED` | 401 | Token đã bị thu hồi | — |
| `AUTH_RATE_LIMIT` | 429 | Vượt quota | `Retry-After` header |
| `AUTH_WEAK_PASSWORD` | 422 | Password không đạt policy | — |
| `AUTH_EMAIL_EXISTS` | 409 | Email đã đăng ký | — |
| `AUTH_OAUTH_STATE_MISMATCH` | 400 | state không khớp Redis | — |
| `AUTH_FORBIDDEN` | 403 | Thiếu permission | — |
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

### 16.3 Scaling

- Stateless service (trạng thái ở PG + Redis) → scale horizontal thoải mái
- Bottleneck phổ biến: password hashing (Argon2 CPU-heavy)
  - Mitigation: HPA theo CPU; separate thread pool cho hashing (không block HTTP thread)
- Peak load dự kiến: 10k login/phút lúc thi cao điểm → cần ~8 pod 2vCPU

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

### 17.1 Pyramid

```
          E2E (10%)  ← login flow đầy đủ, OAuth mocking
       Integration (30%)  ← Testcontainers: PG + Redis + Kafka
   Unit tests (60%)  ← domain logic, validation, hash, TOTP
```

### 17.2 Test tools

| Layer | Tool |
| ----- | ---- |
| Unit | JUnit 5, AssertJ, Mockito |
| Integration | Testcontainers (PG 16, Redis 7, Kafka), WireMock (OAuth stub) |
| Security | OWASP ZAP baseline scan trong CI; Burp Suite định kỳ |
| Load | k6 (target 5k RPS login) |
| Contract | Spring Cloud Contract (với các service consumer) |

### 17.3 Security test cases bắt buộc

- [ ] SQL injection qua email/password field
- [ ] Timing attack: login thành công vs thất bại có thời gian khác nhau không?
- [ ] User enumeration qua `/auth/password/forgot`
- [ ] JWT với `alg:none`
- [ ] JWT signed bằng public key (confused deputy)
- [ ] Refresh token reuse detection
- [ ] OAuth state/nonce bypass
- [ ] TOTP replay (dùng lại code trong window)
- [ ] Race condition đổi password trong khi refresh
- [ ] CSRF với form login (nếu cookie-based)
- [ ] Clickjacking trên UI OAuth consent

---

## XVIII. ROADMAP & OPEN QUESTIONS

### 18.1 MVP (tháng 4-5/2026)

- [x] Schema PostgreSQL
- [ ] Register / login / refresh / logout (email + password)
- [ ] JWT RS256 + JWKS
- [ ] Rate limit Redis
- [ ] Email verify flow (qua Notification Service stub)

### 18.2 Phase 2 (tháng 6/2026)

- [ ] MFA TOTP + backup codes
- [ ] OAuth Google + Microsoft
- [ ] Admin endpoints org-scoped
- [ ] Kafka events

### 18.3 Phase 3 (Q3/2026)

- [ ] SSO SAML cho enterprise
- [ ] WebAuthn / Passkey
- [ ] Risk-based auth (IP geoloc, device fingerprint → step-up MFA)
- [ ] Audit log export (SIEM, Splunk)

### 18.4 Open questions

1. **Single sign-on giữa web + mobile:** dùng chung refresh token hay tách? → Tách (mobile dài hạn 30 ngày, web 7 ngày)
2. **Session list có cần show trên UI?** → Có (Phase 2), để user thấy và revoke
3. **Auto email verify khi invite từ admin:** có skip verification không? → Có (invite token đã pre-verify)
4. **Tích hợp Vault hay dùng K8s Secret MVP?** → K8s Secret cho MVP, migrate Vault Phase 2
5. **Cho phép user đổi email?** → Có, nhưng phải verify email mới trước khi apply
6. **Xử lý user xoá tài khoản (GDPR):** anonymize vs hard delete? → Anonymize (giữ `attempt_answers` để báo cáo)

---

_Tài liệu thuộc Hệ Thống Thi Trực Tuyến Thông Minh — Auth Service Design v1.0 — Tháng 4/2026._
