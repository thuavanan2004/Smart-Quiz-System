package vn.smartquiz.auth.domain.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Token verify email / reset password. `token_hash` = SHA-256 raw (32 bytes, BYTEA) của plaintext
 * 32 bytes random. Schema V0001 dùng BYTEA (xem design §4.3 bản rev mới).
 */
@Entity
@Table(name = "email_verification_tokens")
public class EmailVerificationToken {

  public static final String PURPOSE_VERIFY_EMAIL = "verify_email";
  public static final String PURPOSE_RESET_PASSWORD = "reset_password";

  @Id
  @Column(name = "token_hash")
  private byte[] tokenHash;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "purpose", nullable = false)
  private String purpose;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "used_at")
  private Instant usedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected EmailVerificationToken() {}

  public static EmailVerificationToken issue(
      UUID userId, byte[] tokenHash, String purpose, Instant now, Instant expiresAt) {
    EmailVerificationToken t = new EmailVerificationToken();
    t.tokenHash = tokenHash;
    t.userId = userId;
    t.purpose = purpose;
    t.createdAt = now;
    t.expiresAt = expiresAt;
    return t;
  }

  public boolean isUsable(Instant now) {
    return usedAt == null && expiresAt.isAfter(now);
  }

  public void markUsed(Instant now) {
    this.usedAt = now;
  }

  public byte[] getTokenHash() {
    return tokenHash;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getPurpose() {
    return purpose;
  }

  public Instant getExpiresAt() {
    return expiresAt;
  }

  public Instant getUsedAt() {
    return usedAt;
  }
}
