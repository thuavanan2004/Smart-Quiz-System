package vn.smartquiz.auth.domain.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Aggregate root Auth domain. Map 1-1 với bảng `users` (baseline schema §2).
 *
 * <p>Business methods (lockout, email verify) nằm ở entity để logic không rò rỉ ra use case — use
 * case chỉ orchestrate, entity bảo vệ invariant.
 */
@Entity
@Table(name = "users")
public class User {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "email", nullable = false, updatable = false)
  private String email;

  @Column(name = "username")
  private String username;

  @Column(name = "full_name", nullable = false)
  private String fullName;

  @Column(name = "password_hash")
  private String passwordHash;

  @Column(name = "mfa_enabled", nullable = false)
  private boolean mfaEnabled;

  @Column(name = "email_verified", nullable = false)
  private boolean emailVerified;

  @Column(name = "locale")
  private String locale;

  @Column(name = "timezone")
  private String timezone;

  @Column(name = "last_login_at")
  private Instant lastLoginAt;

  @Column(name = "failed_login_count", nullable = false)
  private short failedLoginCount;

  @Column(name = "locked_until")
  private Instant lockedUntil;

  @Column(name = "is_active", nullable = false)
  private boolean active;

  @Column(name = "created_at", nullable = false, updatable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  protected User() {}

  public static User newAccount(
      UUID id, String email, String fullName, String passwordHash, Instant now) {
    User u = new User();
    u.id = id;
    u.email = email;
    u.fullName = fullName;
    u.passwordHash = passwordHash;
    u.mfaEnabled = false;
    u.emailVerified = false;
    u.locale = "vi-VN";
    u.timezone = "Asia/Ho_Chi_Minh";
    u.failedLoginCount = 0;
    u.active = true;
    u.createdAt = now;
    u.updatedAt = now;
    return u;
  }

  // ---- Business methods ---------------------------------------------------

  /** Ghi nhận đăng nhập thất bại. Lockout theo thang design §9.2 (5/10/20 lần). */
  public void recordFailedLogin(Instant now) {
    this.failedLoginCount = (short) (this.failedLoginCount + 1);
    if (failedLoginCount >= 20) {
      this.lockedUntil = now.plus(Duration.ofDays(3650)); // ~forever; admin unlock
    } else if (failedLoginCount >= 10) {
      this.lockedUntil = now.plus(Duration.ofHours(1));
    } else if (failedLoginCount >= 5) {
      this.lockedUntil = now.plus(Duration.ofMinutes(15));
    }
    this.updatedAt = now;
  }

  public void recordSuccessfulLogin(Instant now) {
    this.failedLoginCount = 0;
    this.lockedUntil = null;
    this.lastLoginAt = now;
    this.updatedAt = now;
  }

  public boolean isLocked(Instant now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }

  public void markEmailVerified(Instant now) {
    this.emailVerified = true;
    this.updatedAt = now;
  }

  /**
   * Đổi mật khẩu. Reset failed counter + unlock (design §9.2: login thành công reset, đổi pwd cũng
   * coi như recovery). Caller phải tự insert {@link PasswordHistory} + revoke refresh token.
   */
  public void changePassword(String newPasswordHash, Instant now) {
    this.passwordHash = newPasswordHash;
    this.failedLoginCount = 0;
    this.lockedUntil = null;
    this.updatedAt = now;
  }

  /** Admin lock manually — {@code until=null} nghĩa là khóa vô thời hạn. */
  public void lockManually(Instant until, Instant now) {
    // Dùng mốc rất xa (100 năm) làm "vĩnh viễn" để không phải phân biệt null ở mọi check.
    this.lockedUntil = until != null ? until : now.plus(Duration.ofDays(36500));
    this.updatedAt = now;
  }

  public void unlock(Instant now) {
    this.failedLoginCount = 0;
    this.lockedUntil = null;
    this.updatedAt = now;
  }

  public void softDelete(Instant now) {
    this.active = false;
    this.deletedAt = now;
    this.updatedAt = now;
  }

  // ---- Getters ------------------------------------------------------------

  public UUID getId() {
    return id;
  }

  public String getEmail() {
    return email;
  }

  public String getUsername() {
    return username;
  }

  public String getFullName() {
    return fullName;
  }

  public String getPasswordHash() {
    return passwordHash;
  }

  public boolean isMfaEnabled() {
    return mfaEnabled;
  }

  public boolean isEmailVerified() {
    return emailVerified;
  }

  public String getLocale() {
    return locale;
  }

  public String getTimezone() {
    return timezone;
  }

  public Instant getLastLoginAt() {
    return lastLoginAt;
  }

  public short getFailedLoginCount() {
    return failedLoginCount;
  }

  public Instant getLockedUntil() {
    return lockedUntil;
  }

  public boolean isActive() {
    return active;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getDeletedAt() {
    return deletedAt;
  }
}
