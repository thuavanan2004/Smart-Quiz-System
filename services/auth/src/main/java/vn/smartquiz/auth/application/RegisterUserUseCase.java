package vn.smartquiz.auth.application;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.smartquiz.auth.common.AuthException;
import vn.smartquiz.auth.common.ErrorCode;
import vn.smartquiz.auth.config.AuthDevProperties;
import vn.smartquiz.auth.domain.notification.NotificationSender;
import vn.smartquiz.auth.domain.password.Argon2PasswordHasher;
import vn.smartquiz.auth.domain.password.PasswordPolicy;
import vn.smartquiz.auth.domain.user.EmailVerificationToken;
import vn.smartquiz.auth.domain.user.PasswordHistory;
import vn.smartquiz.auth.domain.user.User;
import vn.smartquiz.auth.infrastructure.persistence.EmailVerificationTokenRepository;
import vn.smartquiz.auth.infrastructure.persistence.PasswordHistoryRepository;
import vn.smartquiz.auth.infrastructure.persistence.UserRepository;

/**
 * Tạo user + sinh email verification token. Không gửi email (Notification Service stub ở slice 3);
 * khi {@code auth.dev-expose-verification-token=true}, trả về token plaintext trong response cho
 * dev.
 */
@Service
public class RegisterUserUseCase {

  private static final Logger log = LoggerFactory.getLogger(RegisterUserUseCase.class);
  private static final Duration VERIFICATION_TTL = Duration.ofHours(24);

  private final UserRepository userRepo;
  private final PasswordHistoryRepository passwordHistoryRepo;
  private final EmailVerificationTokenRepository verificationRepo;
  private final PasswordPolicy policy;
  private final Argon2PasswordHasher hasher;
  private final NotificationSender notificationSender;
  private final Clock clock;
  private final boolean devExposeVerificationToken;
  private final SecureRandom random = new SecureRandom();

  public RegisterUserUseCase(
      UserRepository userRepo,
      PasswordHistoryRepository passwordHistoryRepo,
      EmailVerificationTokenRepository verificationRepo,
      PasswordPolicy policy,
      Argon2PasswordHasher hasher,
      NotificationSender notificationSender,
      AuthDevProperties devProps,
      Clock clock) {
    this.userRepo = userRepo;
    this.passwordHistoryRepo = passwordHistoryRepo;
    this.verificationRepo = verificationRepo;
    this.policy = policy;
    this.hasher = hasher;
    this.notificationSender = notificationSender;
    this.clock = clock;
    this.devExposeVerificationToken = devProps.exposeVerificationToken();
  }

  @Transactional
  public Result execute(Command cmd) {
    var violation = policy.check(cmd.password(), cmd.email(), cmd.fullName());
    if (violation != null) {
      throw new AuthException(ErrorCode.AUTH_WEAK_PASSWORD, violation.name());
    }
    if (userRepo.existsByEmailIgnoreCase(cmd.email())) {
      throw new AuthException(ErrorCode.AUTH_EMAIL_EXISTS);
    }

    Instant now = clock.instant();
    String passwordHash = hasher.hash(cmd.password());
    User user = User.newAccount(UUID.randomUUID(), cmd.email(), cmd.fullName(), passwordHash, now);
    userRepo.save(user);
    passwordHistoryRepo.save(PasswordHistory.record(user.getId(), passwordHash, now));

    byte[] raw = new byte[32];
    random.nextBytes(raw);
    String tokenPlain = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    byte[] tokenHash = TokenHashing.sha256Raw(tokenPlain);
    verificationRepo.save(
        EmailVerificationToken.issue(
            user.getId(),
            tokenHash,
            EmailVerificationToken.PURPOSE_VERIFY_EMAIL,
            now,
            now.plus(VERIFICATION_TTL)));

    log.info("User registered id={} email={}", user.getId(), user.getEmail());
    notificationSender.sendEmailVerification(user.getEmail(), tokenPlain);

    return new Result(user.getId(), true, devExposeVerificationToken ? tokenPlain : null);
  }

  public record Command(String email, String fullName, char[] password) {}

  public record Result(UUID userId, boolean emailVerificationSent, String verificationTokenDev) {}
}
