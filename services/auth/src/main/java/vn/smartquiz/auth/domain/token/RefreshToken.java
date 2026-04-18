package vn.smartquiz.auth.domain.token;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Opaque refresh token (64 byte random). Lưu SHA-256 hash (BYTEA). Cột `active_org_id` cho phép
 * refresh + switch-org giữ đúng ngữ cảnh org đang active (xem migration V1776540000).
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

  @Column(name = "active_org_id")
  private UUID activeOrgId;

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
      UUID userId,
      byte[] tokenHash,
      String userAgent,
      UUID activeOrgId,
      Instant now,
      Instant expiresAt) {
    RefreshToken rt = new RefreshToken();
    rt.id = UUID.randomUUID();
    rt.userId = userId;
    rt.tokenHash = tokenHash;
    rt.userAgent = userAgent;
    rt.activeOrgId = activeOrgId;
    rt.expiresAt = expiresAt;
    rt.revoked = false;
    rt.createdAt = now;
    return rt;
  }

  public void revoke(Instant now) {
    this.revoked = true;
    this.revokedAt = now;
  }

  public boolean isUsable(Instant now) {
    return !revoked && expiresAt.isAfter(now);
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

  public String getUserAgent() {
    return userAgent;
  }

  public UUID getActiveOrgId() {
    return activeOrgId;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public boolean isRevoked() {
    return revoked;
  }

  public Instant getRevokedAt() {
    return revokedAt;
  }
}
