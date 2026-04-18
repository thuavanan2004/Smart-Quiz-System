package vn.smartquiz.auth.application;

import java.time.Clock;
import java.time.Instant;
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
import vn.smartquiz.auth.domain.user.EmailVerificationToken;
import vn.smartquiz.auth.domain.user.PasswordHistory;
import vn.smartquiz.auth.domain.user.User;
import vn.smartquiz.auth.infrastructure.persistence.EmailVerificationTokenRepository;
import vn.smartquiz.auth.infrastructure.persistence.PasswordHistoryRepository;
import vn.smartquiz.auth.infrastructure.persistence.UserRepository;

/**
 * Reset password bằng token từ email (design §6.3). Verify token → check policy + history → set
 * hash mới + insert history + mark token used + revoke all refresh (buộc login lại).
 */
@Service
public class ResetPasswordUseCase {

  private static final Logger log = LoggerFactory.getLogger(ResetPasswordUseCase.class);

  private final EmailVerificationTokenRepository tokenRepo;
  private final UserRepository userRepo;
  private final PasswordHistoryRepository passwordHistoryRepo;
  private final PasswordPolicy policy;
  private final PasswordHistoryCheck historyCheck;
  private final Argon2PasswordHasher hasher;
  private final RefreshTokenService refreshTokenService;
  private final NotificationSender notificationSender;
  private final Clock clock;

  public ResetPasswordUseCase(
      EmailVerificationTokenRepository tokenRepo,
      UserRepository userRepo,
      PasswordHistoryRepository passwordHistoryRepo,
      PasswordPolicy policy,
      PasswordHistoryCheck historyCheck,
      Argon2PasswordHasher hasher,
      RefreshTokenService refreshTokenService,
      NotificationSender notificationSender,
      Clock clock) {
    this.tokenRepo = tokenRepo;
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
    byte[] tokenHash = TokenHashing.sha256Raw(cmd.tokenPlain());
    EmailVerificationToken token =
        tokenRepo
            .findByTokenHashAndPurpose(tokenHash, EmailVerificationToken.PURPOSE_RESET_PASSWORD)
            .orElseThrow(() -> new AuthException(ErrorCode.AUTH_TOKEN_INVALID));

    Instant now = clock.instant();
    if (!token.isUsable(now)) {
      throw new AuthException(ErrorCode.AUTH_TOKEN_INVALID);
    }

    User user =
        userRepo
            .findById(token.getUserId())
            .orElseThrow(() -> new AuthException(ErrorCode.AUTH_TOKEN_INVALID));

    var violation = policy.check(cmd.newPassword(), user.getEmail(), user.getFullName());
    if (violation != null) {
      throw new AuthException(ErrorCode.AUTH_WEAK_PASSWORD, violation.name());
    }

    if (historyCheck.matchesRecent(user.getId(), cmd.newPassword())) {
      throw new AuthException(ErrorCode.AUTH_WEAK_PASSWORD, "REUSED_RECENT");
    }

    String newHash = hasher.hash(cmd.newPassword());
    user.changePassword(newHash, now);
    passwordHistoryRepo.save(PasswordHistory.record(user.getId(), newHash, now));
    token.markUsed(now);
    int revoked = refreshTokenService.revokeAllForUser(user.getId(), now);
    log.info(
        "Password reset for user={} — revoked {} refresh tokens, token used",
        user.getId(),
        revoked);

    notificationSender.sendPasswordChanged(user.getEmail());
  }

  public record Command(String tokenPlain, char[] newPassword) {}
}
