# Auth Service — Design (DATN)

> **Port**: 8101 · **Ngôn ngữ**: Java 21 + Spring Boot 3.3 · **DB schema**: `auth`
> Là **Identity Provider (IDP)** duy nhất trong hệ — các service khác verify JWT qua JWKS.

## 1. Trách nhiệm

**Có**:
- Đăng ký, đăng nhập (email + password).
- Sinh **access token** (JWT RS256, TTL 15m) + **refresh token** (opaque UUID hash, TTL 7d).
- Xoay vòng refresh token (rotation) và revoke.
- Phát **JWKS public key** qua `/.well-known/jwks.json`.
- CRUD user cho admin (optional — có endpoint thô sơ).

**Không có** (future work):
- Email verification flow.
- Forgot password flow.
- MFA / 2FA.
- OAuth2 social login.
- Audit log chi tiết, login history.
- Account lockout sau N lần sai (chỉ rate-limit IP qua Redis).
- Organization / tenant management.

## 2. Entity

Xem `database/postgresql/schema.sql` §3 (schema `auth`) cho DDL chi tiết. Tóm tắt:

- `auth.users (id, email, password_hash, full_name, role, is_active, created_at, updated_at)`
- `auth.refresh_tokens (id, user_id, token_hash, issued_at, expires_at, revoked_at, replaced_by, user_agent, ip)`

Role: `STUDENT`, `TEACHER`, `ADMIN` — enum string.

## 3. REST API

Base URL: `http://localhost:8101/api/v1`

### 3.1. Public endpoints

#### `POST /auth/register`
Đăng ký user mới. Admin-only trong production, nhưng DATN mở public cho dễ demo.

**Request**:
```json
{
  "email": "student1@example.com",
  "password": "P@ssw0rd123",
  "full_name": "Nguyễn Văn A",
  "role": "STUDENT"
}
```

**Response** `201 Created`:
```json
{
  "id": "uuid",
  "email": "student1@example.com",
  "full_name": "Nguyễn Văn A",
  "role": "STUDENT",
  "created_at": "2026-05-01T10:00:00Z"
}
```

**Error**:
- `400` — email invalid / password weak (min 8 char, có chữ + số)
- `409` — email đã tồn tại

#### `POST /auth/login`

**Request**:
```json
{ "email": "student1@example.com", "password": "P@ssw0rd123" }
```

**Response** `200 OK`:
```json
{
  "access_token": "eyJhbGciOiJSUzI1NiIs...",
  "access_expires_in": 900,
  "refresh_token": "r_8f3a...",
  "refresh_expires_in": 604800,
  "token_type": "Bearer",
  "user": { "id": "uuid", "email": "...", "role": "STUDENT", "full_name": "..." }
}
```

**Rate limit**: 10 lần/phút/IP (Redis `rate:login:{ip}`).

**Error**:
- `401` — sai email/password (response chung, không phân biệt để tránh enumeration)
- `403` — account `is_active = false`
- `429` — rate limit exceeded

#### `POST /auth/refresh`

**Request**:
```json
{ "refresh_token": "r_8f3a..." }
```

**Response** `200 OK`:
```json
{
  "access_token": "...",
  "access_expires_in": 900,
  "refresh_token": "r_new...",   // token MỚI, token cũ bị revoke
  "refresh_expires_in": 604800,
  "token_type": "Bearer"
}
```

**Token rotation**: tìm refresh token theo `sha256(raw)`, kiểm tra chưa expire/revoke. Tạo token mới, `UPDATE old SET revoked_at=now(), replaced_by=new.id`.

**Reuse detection**: nếu token đã `revoked_at IS NOT NULL` mà vẫn được gửi lại → revoke toàn bộ chain của user đó (suspect stolen). Log WARN.

#### `POST /auth/logout`

**Header**: `Authorization: Bearer <access_token>`
**Body**: `{ "refresh_token": "r_..." }`

**Response** `204 No Content`.

Revoke refresh token. Access token vẫn còn hiệu lực đến khi expire (stateless JWT — chấp nhận).

#### `GET /.well-known/jwks.json`

**Response** `200 OK`:
```json
{
  "keys": [{
    "kty": "RSA", "use": "sig", "alg": "RS256",
    "kid": "smartquiz-2026-05",
    "n": "0vx7ag...", "e": "AQAB"
  }]
}
```

**Cache**: `Cache-Control: public, max-age=3600`. Consumer cache 1h.

### 3.2. Authenticated endpoints

Header bắt buộc: `Authorization: Bearer <access_token>`.

#### `GET /auth/me`

Trả về user hiện tại.

**Response** `200 OK`:
```json
{ "id": "...", "email": "...", "full_name": "...", "role": "..." }
```

#### `PUT /auth/me/password`

**Request**:
```json
{ "old_password": "...", "new_password": "..." }
```

**Response** `204 No Content`.

Sau đổi password → revoke toàn bộ refresh token của user.

### 3.3. Admin endpoints (`ADMIN` role)

| Method | Path                          | Mục đích                       |
| ------ | ----------------------------- | ------------------------------ |
| GET    | `/admin/users`                | List user (paging)             |
| POST   | `/admin/users`                | Tạo user (bao gồm TEACHER)     |
| PATCH  | `/admin/users/{id}`           | Sửa (role, is_active)          |
| DELETE | `/admin/users/{id}`           | Soft delete (set is_active=false) |

## 4. JWT spec

### 4.1. Access token (RS256)

**Header**:
```json
{ "alg": "RS256", "typ": "JWT", "kid": "smartquiz-2026-05" }
```

**Payload claims**:
| Claim | Ý nghĩa |
|-------|---------|
| `iss` | `smartquiz-auth` |
| `sub` | user_id (UUID string) |
| `aud` | `smartquiz` |
| `exp` | issued_at + 900 |
| `iat` | issued_at |
| `jti` | random UUID (nonce) |
| `role` | `STUDENT` \| `TEACHER` \| `ADMIN` |
| `email` | email (để hiển thị UI) |
| `name` | full_name |

**Ví dụ**:
```json
{
  "iss": "smartquiz-auth",
  "sub": "3c8a7e2d-...",
  "aud": "smartquiz",
  "exp": 1714568400,
  "iat": 1714567500,
  "jti": "...",
  "role": "STUDENT",
  "email": "student1@example.com",
  "name": "Nguyễn Văn A"
}
```

### 4.2. Refresh token

**Format**: `r_<32 bytes base64url random>` (opaque, không phải JWT).

**Storage**: chỉ lưu `sha256(raw_token)` trong DB. Server không giữ raw.

### 4.3. Keypair

- Sinh bằng `ops/gen-jwt-keypair.sh` (RSA 2048).
- File: `ops/jwt/private.pem`, `ops/jwt/public.pem`.
- `kid` format: `smartquiz-{YYYY-MM}`.
- **DATN**: không rotate key trong vòng đời đồ án. Rotation SOP là future work.

## 5. Architecture

```
┌─────────────────────────────────────────────┐
│  Controller layer                            │
│  AuthController · JwksController             │
│  AdminUserController · MeController          │
└──────────────────┬───────────────────────────┘
                   │
┌──────────────────▼───────────────────────────┐
│  Service layer                               │
│  AuthService   (register, login, refresh)    │
│  TokenService  (mint JWT, hash refresh)      │
│  UserService   (CRUD)                        │
│  JwksService   (load public key, build JWKS) │
└──────────────────┬───────────────────────────┘
                   │
┌──────────────────▼───────────────────────────┐
│  Repository (Spring Data JPA)                │
│  UserRepository · RefreshTokenRepository     │
└──────────────────┬───────────────────────────┘
                   ▼
                PostgreSQL (schema: auth)
```

**Dependencies chính** (`build.gradle.kts`):
- `spring-boot-starter-web`
- `spring-boot-starter-data-jpa`
- `spring-boot-starter-security`
- `spring-boot-starter-validation`
- `spring-boot-starter-data-redis` (rate limit)
- `io.jsonwebtoken:jjwt-*` hoặc `com.auth0:java-jwt`
- `flyway-core` + `flyway-database-postgresql`
- Testcontainers (test)

## 6. Security

| Vấn đề                   | Giải pháp                                                     |
| ------------------------ | ------------------------------------------------------------- |
| Password storage         | BCrypt cost 12                                                |
| Brute force login        | Rate limit Redis 10/min/IP; không lockout account             |
| Timing attack login      | Luôn chạy BCrypt.matches() (dummy nếu email không tồn tại)    |
| Token leak               | Refresh token chỉ lưu hash, rotate mỗi lần refresh            |
| Token reuse              | Detect replay của revoked token → revoke chain                |
| Enumeration              | Response login sai thống nhất, không phân biệt email/password |
| SQL injection            | JPA parameterized                                             |
| CSRF                     | Stateless, Bearer token → không áp dụng                       |
| Weak password            | Validator: min 8, có chữ + số. Không check dictionary.        |

## 7. Kafka (DATN: không publish event)

Auth service **không publish event nào trong DATN scope**. Future work có thể bổ
sung `auth.user.registered.v1`, `auth.user.deactivated.v1` khi cần.

**Consumer**: không.

## 8. Flow chi tiết

### 8.1. Login

```
1. POST /auth/login {email, password}
2. Check rate limit rate:login:{ip} < 10
3. SELECT users WHERE email = $1
4. BCrypt.matches(password, user.password_hash)
   (chạy với dummy hash nếu user không tồn tại → chống timing attack)
5. Nếu match + is_active:
   a. Mint access token với claims
   b. Sinh raw refresh token, INSERT refresh_tokens (token_hash, expires_at)
   c. Return {access_token, refresh_token, user}
6. Nếu fail: return 401 "Invalid credentials"
```

### 8.2. Refresh token rotation

```
1. POST /auth/refresh {refresh_token: "r_..."}
2. token_hash = sha256(raw)
3. SELECT refresh_tokens WHERE token_hash = $1
4. Check expires_at > now AND revoked_at IS NULL
5. Nếu revoked → REUSE DETECTED:
   - UPDATE refresh_tokens SET revoked_at=now WHERE user_id=old.user_id
   - Log WARN "Refresh token reuse detected, user=X"
   - Return 401
6. Transaction:
   a. UPDATE old SET revoked_at=now
   b. INSERT new refresh_token
   c. UPDATE old SET replaced_by=new.id
7. Mint access token mới
8. Return {access_token, refresh_token (new)}
```

## 9. Test strategy

### Unit test (JUnit 5)
- `TokenServiceTest`: mint JWT có claim đúng; verify bằng public key.
- `AuthServiceTest`: login flow happy/fail; dummy BCrypt khi user not found.
- `PasswordPolicyTest`: min length, char class.

### Integration test (Testcontainers PG + Redis)
- Register → login → refresh → me → logout.
- Refresh token reuse detection.
- Rate limit exceed → 429.

### Contract test
- JWKS endpoint trả về key hợp lệ; Core service có thể verify JWT bằng key đó.

## 10. Cấu hình

`application.yml` (chủ yếu):
```yaml
server:
  port: 8101

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/smartquiz?currentSchema=auth
    username: auth_app
    password: ${DB_PASSWORD}
  jpa:
    hibernate.ddl-auto: validate
    properties.hibernate.default_schema: auth
  flyway:
    schemas: auth
    default-schema: auth
  data.redis:
    host: localhost
    port: 6379

smartquiz:
  jwt:
    issuer: smartquiz-auth
    audience: smartquiz
    kid: smartquiz-2026-05
    private-key-path: file:ops/jwt/private.pem
    public-key-path:  file:ops/jwt/public.pem
    access-ttl: 15m
    refresh-ttl: 7d
  rate-limit:
    login-per-minute-per-ip: 10
```

## 11. Quan sát được (observability)

- Log JSON qua Logback. MDC: `requestId`, `userId` (sau authenticate).
- Metric Micrometer:
  - `auth_login_total{result=success|fail}`
  - `auth_refresh_total{result=success|reuse_detected|expired}`
  - `auth_jwks_request_total`
- `/actuator/health`, `/actuator/prometheus`.

## 12. Non-goal + future work

Nếu hội đồng hỏi "scale thế nào":
- Email verification + forgot password (SMTP + token link).
- MFA TOTP (RFC 6238).
- OAuth2 federation (Google sign-in).
- Key rotation với JWKS multi-key (dual sign 1 tuần).
- Account lockout + CAPTCHA sau N fail.
