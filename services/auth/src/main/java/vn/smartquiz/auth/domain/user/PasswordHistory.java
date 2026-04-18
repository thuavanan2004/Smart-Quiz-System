package vn.smartquiz.auth.domain.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Lịch sử hash để chống tái dùng 5 password gần nhất (design §6.2). */
@Entity
@Table(name = "password_history")
public class PasswordHistory {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "password_hash", nullable = false)
  private String passwordHash;

  @Column(name = "changed_at", nullable = false)
  private Instant changedAt;

  protected PasswordHistory() {}

  public static PasswordHistory record(UUID userId, String passwordHash, Instant now) {
    PasswordHistory ph = new PasswordHistory();
    ph.id = UUID.randomUUID();
    ph.userId = userId;
    ph.passwordHash = passwordHash;
    ph.changedAt = now;
    return ph;
  }

  public UUID getId() {
    return id;
  }

  public UUID getUserId() {
    return userId;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public Instant getChangedAt() {
    return changedAt;
  }
}
