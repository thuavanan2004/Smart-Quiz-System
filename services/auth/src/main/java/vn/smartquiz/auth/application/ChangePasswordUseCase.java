package vn.smartquiz.auth.application;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.smartquiz.auth.common.AuthException;
import vn.smartquiz.auth.common.ErrorCode;
import vn.smartquiz.auth.domain.notification.NotificationSender;
import vn.smartquiz.auth.domain.password.Argon2PasswordHasher;
import vn.smartquiz.auth.domain.password.PasswordPolicy;
import vn.smartquiz.auth.domain.token.RefreshTokenService;
import vn.smartquiz.auth.domain.user.PasswordHistory;
import vn.smartquiz.auth.domain.user.User;
import vn.smartquiz.auth.infrastructure.persistence.PasswordHistoryRepository;
import vn.smartquiz.auth.infrastructure.persistence.UserRepository;

/**
 * User tự đổi mật khẩu (design §5.3 + §6.2). Yêu cầu old password chính xác. Sau khi đổi: revoke
 * tất cả refresh token (kể cả session hiện tại) → buộc login lại ở mọi device.
 */
@Service
public class ChangePasswordUseCase {

  private static final Logger log = LoggerFactory.getLogger(ChangePasswordUseCase.class);

  private final UserRepository userRepo;
  private final PasswordHistoryRepository passwordHistoryRepo;
  private final PasswordPolicy policy;
  private final PasswordHistoryCheck historyCheck;
  private final Argon2PasswordHasher hasher;
  private final RefreshTokenService refreshTokenService;
  private final NotificationSender notificationSender;
  private final Clock clock;

  public ChangePasswordUseCase(
      UserRepository userRepo,
      PasswordHistoryRepository passwordHistoryRepo,
      PasswordPolicy policy,
      PasswordHistoryCheck historyCheck,
      Argon2PasswordHasher hasher,
      RefreshTokenService refreshTokenService,
      NotificationSender notificationSender,
      Clock clock) {
    this.userRepo = userRepo;
    this.passwordHistoryRepo = passwordHistoryRepo;
    this.policy = policy;
    this.historyCheck = historyCheck;
    this.hasher = hasher;
    this.refreshTokenService = refreshTokenService;
    this.notificationSender = notificationSender;
    this.clock = clock;
  }

  @Transactional
  public void execute(Command cmd) {
    User user =
        userRepo
            .findById(cmd.userId())
            .orElseThrow(() -> new AuthException(ErrorCode.AUTH_INVALID_CREDENTIALS));

    if (user.getPasswordHash() == null
        || !hasher.verify(user.getPasswordHash(), cmd.oldPassword())) {
      throw new AuthException(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    var violation = policy.check(cmd.newPassword(), user.getEmail(), user.getFullName());
    if (violation != null) {
      throw new AuthException(ErrorCode.AUTH_WEAK_PASSWORD, violation.name());
    }

    if (historyCheck.matchesRecent(user.getId(), cmd.newPassword())) {
      throw new AuthException(ErrorCode.AUTH_WEAK_PASSWORD, "REUSED_RECENT");
    }

    Instant now = clock.instant();
    String newHash = hasher.hash(cmd.newPassword());
    user.changePassword(newHash, now);
    passwordHistoryRepo.save(PasswordHistory.record(user.getId(), newHash, now));
    int revoked = refreshTokenService.revokeAllForUser(user.getId(), now);
    log.info("Password changed for user={} — revoked {} refresh tokens", user.getId(), revoked);

    notificationSender.sendPasswordChanged(user.getEmail());
  }

  public record Command(UUID userId, char[] oldPassword, char[] newPassword) {}
}
