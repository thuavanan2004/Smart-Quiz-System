package vn.smartquiz.auth.domain.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import vn.smartquiz.auth.config.AuthJwtProperties;
import vn.smartquiz.auth.infrastructure.persistence.RefreshTokenRepository;

/**
 * Sinh, rotate, revoke refresh token. Lưu SHA-256 hash vào PG (BYTEA, 32 bytes raw).
 *
 * <p>Rotation (design §5.2): mỗi lần /auth/refresh hợp lệ → token cũ revoked, cấp token mới. Nếu
 * token đã revoked bị dùng lại → {@link ReuseDetectedException} — caller revoke toàn bộ session của
 * user.
 *
 * <p>Mỗi session mang theo {@code activeOrgId} — switch-org rotate với org_id mới, refresh giữ
 * nguyên org cũ. Nếu null (user chưa thuộc org nào) thì access token có claim {@code org_id=null}.
 */
@Component
public class RefreshTokenService {

  private static final int RAW_LEN = 64;
  private final SecureRandom random = new SecureRandom();
  private final RefreshTokenRepository repo;
  private final Duration ttl;

  public RefreshTokenService(RefreshTokenRepository repo, AuthJwtProperties props) {
    this.repo = repo;
    this.ttl = Duration.ofDays(props.refreshTtlDays());
  }

  @Transactional
  public Issued issue(UUID userId, String userAgent, UUID activeOrgId, Instant now) {
    byte[] raw = new byte[RAW_LEN];
    random.nextBytes(raw);
    String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    byte[] hash = sha256(encoded);
    RefreshToken rt = RefreshToken.issue(userId, hash, userAgent, activeOrgId, now, now.plus(ttl));
    repo.save(rt);
    return new Issued(rt.getId(), encoded, activeOrgId, ttl.toSeconds());
  }

  /**
   * Rotation: consume refresh token cũ (revoke), issue mới với cùng {@code activeOrgId}. Dùng khi
   * /auth/refresh để client có thể tiếp tục phiên hiện tại mà không mất ngữ cảnh org.
   */
  @Transactional
  public RotationResult rotate(String plaintext, String userAgent, Instant now) {
    RefreshToken existing = consume(plaintext, now);
    Issued issued = issue(existing.getUserId(), userAgent, existing.getActiveOrgId(), now);
    return new RotationResult(existing.getUserId(), issued);
  }

  /**
   * Rotation + switch org: consume refresh token cũ (revoke), issue mới với {@code activeOrgId}
   * mới. Dùng cho /auth/switch-org.
   */
  @Transactional
  public RotationResult rotateWithOrg(
      String plaintext, String userAgent, UUID newActiveOrgId, Instant now) {
    RefreshToken existing = consume(plaintext, now);
    Issued issued = issue(existing.getUserId(), userAgent, newActiveOrgId, now);
    return new RotationResult(existing.getUserId(), issued);
  }

  private RefreshToken consume(String plaintext, Instant now) {
    byte[] hash = sha256(plaintext);
    RefreshToken existing =
        repo.findByTokenHash(hash).orElseThrow(() -> new InvalidRefreshException("not found"));
    if (existing.isRevoked()) {
      throw new ReuseDetectedException(existing.getUserId());
    }
    if (!existing.isUsable(now)) {
      throw new InvalidRefreshException("expired");
    }
    existing.revoke(now);
    return existing;
  }

  public Optional<RefreshToken> findActiveById(UUID id) {
    return repo.findById(id).filter(rt -> !rt.isRevoked());
  }

  public List<RefreshToken> listActiveByUser(UUID userId, Instant now) {
    return repo.findActiveByUserId(userId, now);
  }

  @Transactional
  public void revoke(RefreshToken rt, Instant now) {
    if (!rt.isRevoked()) {
      rt.revoke(now);
    }
  }

  @Transactional
  public int revokeAllForUser(UUID userId, Instant now) {
    return repo.revokeAllByUserId(userId, now);
  }

  static byte[] sha256(String input) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  public record Issued(UUID sessionId, String token, UUID activeOrgId, long ttlSeconds) {}

  public record RotationResult(UUID userId, Issued issued) {}

  public static class InvalidRefreshException extends RuntimeException {
    public InvalidRefreshException(String msg) {
      super(msg);
    }
  }

  public static class ReuseDetectedException extends RuntimeException {
    private final UUID userId;

    public ReuseDetectedException(UUID userId) {
      super("refresh token reused — stolen detection triggered");
      this.userId = userId;
    }

    public UUID userId() {
      return userId;
    }
  }
}
