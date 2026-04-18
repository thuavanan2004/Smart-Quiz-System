package vn.smartquiz.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import vn.smartquiz.auth.common.AuthException;
import vn.smartquiz.auth.common.ErrorCode;
import vn.smartquiz.auth.domain.ratelimit.RateLimitResult;
import vn.smartquiz.auth.domain.ratelimit.RateLimiter;

class RateLimitPolicyTest {

  private final RateLimiter limiter = Mockito.mock(RateLimiter.class);

  @Test
  void disabledSkipsAllChecks() {
    RateLimitPolicy policy = new RateLimitPolicy(limiter, false);
    policy.checkRegister("1.2.3.4");
    policy.checkLoginByIp("1.2.3.4");
    policy.checkLoginByEmail("alice@x.vn");
    verify(limiter, never()).allow(any(), anyInt(), any());
  }

  @Test
  void allowedPassesThrough() {
    when(limiter.allow(any(), anyInt(), any())).thenReturn(RateLimitResult.ok());
    RateLimitPolicy policy = new RateLimitPolicy(limiter, true);
    assertThatCode(() -> policy.checkLoginByIp("1.2.3.4")).doesNotThrowAnyException();
  }

  @Test
  void deniedThrowsRateLimitWithRetryAfter() {
    when(limiter.allow(any(), anyInt(), any()))
        .thenReturn(RateLimitResult.retry(Duration.ofSeconds(42)));
    RateLimitPolicy policy = new RateLimitPolicy(limiter, true);

    assertThatThrownBy(() -> policy.checkForgot("1.2.3.4"))
        .isInstanceOfSatisfying(
            AuthException.class,
            ex -> {
              assertThat(ex.code()).isEqualTo(ErrorCode.AUTH_RATE_LIMIT);
              assertThat(ex.retryAfter()).isEqualTo(Duration.ofSeconds(42));
            });
  }

  @Test
  void registerUsesExpectedKeyAndLimits() {
    when(limiter.allow(any(), anyInt(), any())).thenReturn(RateLimitResult.ok());
    new RateLimitPolicy(limiter, true).checkRegister("1.2.3.4");
    verify(limiter).allow(eq("rate:register:1.2.3.4"), eq(5), eq(Duration.ofHours(1)));
  }

  @Test
  void loginByIpAndEmailUseDistinctBuckets() {
    when(limiter.allow(any(), anyInt(), any())).thenReturn(RateLimitResult.ok());
    RateLimitPolicy policy = new RateLimitPolicy(limiter, true);

    policy.checkLoginByIp("1.2.3.4");
    policy.checkLoginByEmail("alice@x.vn");

    verify(limiter).allow(eq("rate:login:1.2.3.4"), eq(10), eq(Duration.ofMinutes(15)));
    verify(limiter).allow(eq("rate:login_user:alice@x.vn"), eq(5), eq(Duration.ofMinutes(15)));
  }

  @Test
  void refreshUsesPerMinuteBucket() {
    when(limiter.allow(any(), anyInt(), any())).thenReturn(RateLimitResult.ok());
    new RateLimitPolicy(limiter, true).checkRefresh("1.2.3.4");
    verify(limiter).allow(eq("rate:refresh:1.2.3.4"), eq(30), eq(Duration.ofMinutes(1)));
  }
}
