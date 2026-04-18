package vn.smartquiz.auth.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Centralized JWT config (Iron Rule #4: typed, validated at startup, fail fast). Thay thế
 * {@code @Value} rải rác ở {@code JwkConfig}, {@code JwtTokenIssuer}, {@code RefreshTokenService}.
 *
 * <p>Compact constructor validate required fields — app không start nếu thiếu.
 */
@ConfigurationProperties("auth.jwt")
public record AuthJwtProperties(
    Path privateKeyPath,
    Path publicKeyPath,
    String keyId,
    String issuer,
    String audience,
    long accessTtlSeconds,
    int refreshTtlDays) {

  public AuthJwtProperties {
    if (privateKeyPath == null
        || publicKeyPath == null
        || keyId == null
        || keyId.isBlank()
        || issuer == null
        || issuer.isBlank()
        || audience == null
        || audience.isBlank()) {
      throw new IllegalStateException(
          "auth.jwt.* properties required: private-key-path, public-key-path, key-id, issuer, audience");
    }
    if (accessTtlSeconds <= 0) {
      throw new IllegalStateException("auth.jwt.access-ttl-seconds must be > 0");
    }
    if (refreshTtlDays <= 0) {
      throw new IllegalStateException("auth.jwt.refresh-ttl-days must be > 0");
    }
  }
}
