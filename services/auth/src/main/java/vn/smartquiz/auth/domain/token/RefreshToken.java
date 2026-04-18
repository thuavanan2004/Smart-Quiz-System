package vn.smartquiz.auth.domain.token;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Opaque refresh token (64 byte random). Lưu SHA-256 hash (BYTEA). Slice 1 chỉ insert lúc login;
 * endpoint /auth/refresh + rotation để slice 2.
 */
@Entity
@Table(name = "refresh_tokens")
public class RefreshToken {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "token_hash", nullable = false, unique = true)
  private byte[] tokenHash;

  @Column(name = "user_agent")
  private String userAgent;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "revoked", nullable = false)
  private boolean revoked;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected RefreshToken() {}

  public static RefreshToken issue(
      UUID userId, byte[] tokenHash, String userAgent, Instant now, Instant expiresAt) {
    RefreshToken rt = new RefreshToken();
    rt.id = UUID.randomUUID();
    rt.userId = userId;
    rt.tokenHash = tokenHash;
    rt.userAgent = userAgent;
    rt.expiresAt = expiresAt;
    rt.revoked = false;
    rt.createdAt = now;
    return rt;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public byte[] getTokenHash() {
    return tokenHash;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public boolean isRevoked() {
    return revoked;
  }
}
