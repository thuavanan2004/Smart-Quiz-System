package vn.smartquiz.auth.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.smartquiz.auth.config.AuthDevProperties;
import vn.smartquiz.auth.domain.notification.NotificationSender;
import vn.smartquiz.auth.domain.user.EmailVerificationToken;
import vn.smartquiz.auth.infrastructure.persistence.EmailVerificationTokenRepository;
import vn.smartquiz.auth.infrastructure.persistence.UserRepository;

/**
 * Khởi tạo flow reset password (design §6.3). Chống user enumeration: LUÔN trả 200 kể cả email
 * không tồn tại. Chỉ khi email tồn tại mới sinh token + gửi notification.
 */
@Service
public class ForgotPasswordUseCase {

  private static final Logger log = LoggerFactory.getLogger(ForgotPasswordUseCase.class);
  private static final Duration RESET_TTL = Duration.ofHours(1);

  private final UserRepository userRepo;
  private final EmailVerificationTokenRepository tokenRepo;
  private final NotificationSender notificationSender;
  private final Clock clock;
  private final boolean devExposeResetToken;
  private final SecureRandom random = new SecureRandom();

  public ForgotPasswordUseCase(
      UserRepository userRepo,
      EmailVerificationTokenRepository tokenRepo,
      NotificationSender notificationSender,
      AuthDevProperties devProps,
      Clock clock) {
    this.userRepo = userRepo;
    this.tokenRepo = tokenRepo;
    this.notificationSender = notificationSender;
    this.clock = clock;
    this.devExposeResetToken = devProps.exposeResetToken();
  }

  @Transactional
  public Result execute(String email) {
    Instant now = clock.instant();
    var userOpt = userRepo.findByEmailIgnoreCase(email);
    if (userOpt.isEmpty()) {
      // Timing: không branch khác biệt quá rõ. Slice 4 có thể thêm dummy-hash để đồng nhất thời
      // gian.
      log.debug("Forgot password for unknown email={}", email);
      return new Result(null);
    }
    var user = userOpt.get();

    byte[] raw = new byte[32];
    random.nextBytes(raw);
    String tokenPlain = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    byte[] tokenHash = TokenHashing.sha256Raw(tokenPlain);

    tokenRepo.save(
        EmailVerificationToken.issue(
            user.getId(),
            tokenHash,
            EmailVerificationToken.PURPOSE_RESET_PASSWORD,
            now,
            now.plus(RESET_TTL)));

    notificationSender.sendPasswordReset(user.getEmail(), tokenPlain);
    log.info("Password reset token issued for user={}", user.getId());

    return new Result(devExposeResetToken ? tokenPlain : null);
  }

  public record Result(String resetTokenDev) {}
}
