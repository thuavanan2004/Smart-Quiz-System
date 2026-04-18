package vn.smartquiz.auth.domain.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.smartquiz.auth.infrastructure.persistence.RefreshTokenRepository;

/**
 * Sinh refresh token (64 byte random, base64url). Lưu SHA-256 hash vào PG.
 *
 * <p>Slice 1: chỉ issue. Rotation / stolen detection / /auth/refresh endpoint — slice 2.
 */
@Component
public class RefreshTokenService {

  private static final int RAW_LEN = 64;
  private final SecureRandom random = new SecureRandom();
  private final RefreshTokenRepository repo;
  private final Duration ttl;

  public RefreshTokenService(
      RefreshTokenRepository repo, @Value("${auth.jwt.refresh-ttl-days:7}") int refreshTtlDays) {
    this.repo = repo;
    this.ttl = Duration.ofDays(refreshTtlDays);
  }

  @Transactional
  public Issued issue(UUID userId, String userAgent, Instant now) {
    byte[] raw = new byte[RAW_LEN];
    random.nextBytes(raw);
    String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    byte[] hash = sha256(encoded);
    RefreshToken rt = RefreshToken.issue(userId, hash, userAgent, now, now.plus(ttl));
    repo.save(rt);
    return new Issued(encoded, ttl.toSeconds());
  }

  private static byte[] sha256(String input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public record Issued(String token, long ttlSeconds) {}
}
