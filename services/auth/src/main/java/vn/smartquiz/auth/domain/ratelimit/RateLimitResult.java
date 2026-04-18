package vn.smartquiz.auth.domain.ratelimit;

import java.time.Duration;

public record RateLimitResult(boolean allowed, Duration retryAfter) {

  private static final RateLimitResult OK = new RateLimitResult(true, Duration.ZERO);

  public static RateLimitResult ok() {
    return OK;
  }

  public static RateLimitResult retry(Duration retryAfter) {
    return new RateLimitResult(false, retryAfter);
  }
}
