package vn.smartquiz.auth.domain.ratelimit;

import java.time.Duration;

/**
 * Sliding-window counter per key. Không throw — caller quyết định phản ứng dựa trên {@link
 * RateLimitResult}.
 */
public interface RateLimiter {

  RateLimitResult allow(String key, int limit, Duration window);
}
