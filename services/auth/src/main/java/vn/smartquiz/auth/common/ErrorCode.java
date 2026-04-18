package vn.smartquiz.auth.common;

import org.springframework.http.HttpStatus;

/** Bảng mã lỗi Auth theo design §15.2. {@code type} URI chung domain error pattern. */
public enum ErrorCode {
  AUTH_INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "Email hoặc mật khẩu không đúng"),
  AUTH_MFA_REQUIRED(HttpStatus.OK, "Cần xác thực 2 yếu tố"),
  AUTH_MFA_INVALID(HttpStatus.UNAUTHORIZED, "Mã MFA không hợp lệ"),
  AUTH_ACCOUNT_LOCKED(HttpStatus.LOCKED, "Tài khoản đã bị khóa"),
  AUTH_EMAIL_NOT_VERIFIED(HttpStatus.FORBIDDEN, "Email chưa được xác minh"),
  AUTH_TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "Token đã hết hạn"),
  AUTH_TOKEN_INVALID(HttpStatus.UNAUTHORIZED, "Token không hợp lệ"),
  AUTH_TOKEN_REVOKED(HttpStatus.UNAUTHORIZED, "Token đã bị thu hồi"),
  AUTH_RATE_LIMIT(HttpStatus.TOO_MANY_REQUESTS, "Quá nhiều request"),
  AUTH_WEAK_PASSWORD(HttpStatus.UNPROCESSABLE_ENTITY, "Mật khẩu không đủ mạnh"),
  AUTH_EMAIL_EXISTS(HttpStatus.CONFLICT, "Email đã được sử dụng"),
  AUTH_OAUTH_STATE_MISMATCH(HttpStatus.BAD_REQUEST, "OAuth state không khớp"),
  AUTH_FORBIDDEN(HttpStatus.FORBIDDEN, "Thiếu quyền truy cập"),
  AUTH_INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "Lỗi hệ thống");

  private final HttpStatus status;
  private final String defaultTitle;

  ErrorCode(HttpStatus status, String defaultTitle) {
    this.status = status;
    this.defaultTitle = defaultTitle;
  }

  public HttpStatus status() {
    return status;
  }

  public String defaultTitle() {
    return defaultTitle;
  }

  public String typeUri() {
    return "https://smartquiz.vn/errors/"
        + name().toLowerCase().replace('_', '-').replace("auth-", "");
  }
}
