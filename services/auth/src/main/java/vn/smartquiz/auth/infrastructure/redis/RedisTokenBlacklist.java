package vn.smartquiz.auth.infrastructure.redis;

import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import vn.smartquiz.auth.domain.token.TokenBlacklist;

/**
 * Redis-backed blacklist. Key {@code blacklist:jwt:<jti>} = "1", TTL = thời gian còn lại của access
 * token. Hết TTL key tự xóa — không cần GC thủ công.
 */
@Component
public class RedisTokenBlacklist implements TokenBlacklist {

  private static final String KEY_PREFIX = "blacklist:jwt:";

  private final StringRedisTemplate redis;

  public RedisTokenBlacklist(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public void ban(String jti, Duration ttl) {
    if (ttl.isZero() || ttl.isNegative()) {
      return;
    }
    redis.opsForValue().set(KEY_PREFIX + jti, "1", ttl);
  }

  @Override
  public boolean isBanned(String jti) {
    Boolean hasKey = redis.hasKey(KEY_PREFIX + jti);
    return Boolean.TRUE.equals(hasKey);
  }
}
