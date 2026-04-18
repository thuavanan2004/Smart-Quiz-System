package vn.smartquiz.auth.common;

import java.time.Duration;

/** Exception domain Auth — {@link GlobalExceptionHandler} map sang Problem Details. */
public class AuthException extends RuntimeException {

  private final ErrorCode code;
  private final Duration retryAfter;

  public AuthException(ErrorCode code) {
    this(code, code.defaultTitle(), null);
  }

  public AuthException(ErrorCode code, String message) {
    this(code, message, null);
  }

  public AuthException(ErrorCode code, String message, Duration retryAfter) {
    super(message);
    this.code = code;
    this.retryAfter = retryAfter;
  }

  public ErrorCode code() {
    return code;
  }

  public Duration retryAfter() {
    return retryAfter;
  }
}
