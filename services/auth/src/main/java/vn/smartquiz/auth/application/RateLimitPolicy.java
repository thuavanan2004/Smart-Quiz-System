package vn.smartquiz.auth.application;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import vn.smartquiz.auth.common.AuthException;
import vn.smartquiz.auth.common.ErrorCode;
import vn.smartquiz.auth.domain.ratelimit.RateLimitResult;
import vn.smartquiz.auth.domain.ratelimit.RateLimiter;

/**
 * Policy enforce giới hạn request theo design §9.1. Tách khỏi {@link RateLimiter} để chính sách
 * (limits, keys) nằm 1 chỗ và có thể thay đổi không đụng hạ tầng.
 *
 * <p>{@code auth.rate-limit.enabled=false} → tắt toàn bộ (dùng trong test khi muốn spam endpoint,
 * vd test lockout).
 */
@Component
public class RateLimitPolicy {

  private static final Duration HOUR = Duration.ofHours(1);
  private static final Duration MIN_15 = Duration.ofMinutes(15);
  private static final Duration MIN_1 = Duration.ofMinutes(1);

  private final RateLimiter limiter;
  private final boolean enabled;

  public RateLimitPolicy(
      RateLimiter limiter, @Value("${auth.rate-limit.enabled:true}") boolean enabled) {
    this.limiter = limiter;
    this.enabled = enabled;
  }

  public void checkRegister(String ip) {
    check("rate:register:" + ip, 5, HOUR);
  }

  public void checkLoginByIp(String ip) {
    check("rate:login:" + ip, 10, MIN_15);
  }

  public void checkLoginByEmail(String email) {
    check("rate:login_user:" + email, 5, MIN_15);
  }

  public void checkForgot(String ip) {
    check("rate:forgot:" + ip, 3, HOUR);
  }

  public void checkRefresh(String ip) {
    check("rate:refresh:" + ip, 30, MIN_1);
  }

  private void check(String key, int limit, Duration window) {
    if (!enabled) {
      return;
    }
    RateLimitResult result = limiter.allow(key, limit, window);
    if (!result.allowed()) {
      throw new AuthException(
          ErrorCode.AUTH_RATE_LIMIT, ErrorCode.AUTH_RATE_LIMIT.defaultTitle(), result.retryAfter());
    }
  }
}
