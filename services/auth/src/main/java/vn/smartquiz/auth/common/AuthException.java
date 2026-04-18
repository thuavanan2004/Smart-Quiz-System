package vn.smartquiz.auth.common;

/** Exception domain Auth — {@link GlobalExceptionHandler} map sang Problem Details. */
public class AuthException extends RuntimeException {

  private final ErrorCode code;

  public AuthException(ErrorCode code) {
    super(code.defaultTitle());
    this.code = code;
  }

  public AuthException(ErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public ErrorCode code() {
    return code;
  }
}
