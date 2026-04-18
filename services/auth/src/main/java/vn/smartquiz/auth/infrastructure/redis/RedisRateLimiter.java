package vn.smartquiz.auth.infrastructure.redis;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import vn.smartquiz.auth.domain.ratelimit.RateLimitResult;
import vn.smartquiz.auth.domain.ratelimit.RateLimiter;

/**
 * Redis sliding-window rate limiter. Atomic qua Lua script (xem {@code scripts/rate_limit.lua}).
 * Script trả 0 (allowed) hoặc seconds cần chờ.
 */
@Component
public class RedisRateLimiter implements RateLimiter {

  private final StringRedisTemplate redis;
  private final DefaultRedisScript<Long> script;

  public RedisRateLimiter(StringRedisTemplate redis) {
    this.redis = redis;
    this.script = new DefaultRedisScript<>();
    this.script.setLocation(new ClassPathResource("scripts/rate_limit.lua"));
    this.script.setResultType(Long.class);
  }

  @Override
  public RateLimitResult allow(String key, int limit, Duration window) {
    long now = System.currentTimeMillis();
    Long result =
        redis.execute(
            script,
            List.of(key),
            Long.toString(now),
            Long.toString(window.toMillis()),
            Integer.toString(limit),
            UUID.randomUUID().toString());
    if (result == null || result == 0L) {
      return RateLimitResult.ok();
    }
    return RateLimitResult.retry(Duration.ofSeconds(result));
  }
}
