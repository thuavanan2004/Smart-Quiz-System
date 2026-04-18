package vn.smartquiz.auth.domain.token;

import java.time.Duration;

/**
 * Blacklist JWT theo jti. Design §5.3: logout → thêm jti vào blacklist với TTL = thời gian còn lại
 * của access token. Resource server check trên mỗi request authenticated.
 */
public interface TokenBlacklist {

  void ban(String jti, Duration ttl);

  boolean isBanned(String jti);
}
