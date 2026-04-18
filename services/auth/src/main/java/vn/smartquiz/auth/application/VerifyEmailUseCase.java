package vn.smartquiz.auth.application;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.smartquiz.auth.common.AuthException;
import vn.smartquiz.auth.common.ErrorCode;
import vn.smartquiz.auth.domain.user.EmailVerificationToken;
import vn.smartquiz.auth.domain.user.User;
import vn.smartquiz.auth.infrastructure.persistence.EmailVerificationTokenRepository;
import vn.smartquiz.auth.infrastructure.persistence.UserRepository;

@Service
public class VerifyEmailUseCase {

  private final EmailVerificationTokenRepository verificationRepo;
  private final UserRepository userRepo;
  private final Clock clock;

  public VerifyEmailUseCase(
      EmailVerificationTokenRepository verificationRepo, UserRepository userRepo, Clock clock) {
    this.verificationRepo = verificationRepo;
    this.userRepo = userRepo;
    this.clock = clock;
  }

  @Transactional
  public void execute(String tokenPlain) {
    String tokenHash = TokenHashing.sha256Hex(tokenPlain);
    EmailVerificationToken token =
        verificationRepo
            .findByTokenHashAndPurpose(tokenHash, EmailVerificationToken.PURPOSE_VERIFY_EMAIL)
            .orElseThrow(() -> new AuthException(ErrorCode.AUTH_TOKEN_INVALID));

    Instant now = clock.instant();
    if (!token.isUsable(now)) {
      throw new AuthException(ErrorCode.AUTH_TOKEN_INVALID);
    }

    User user =
        userRepo
            .findById(token.getUserId())
            .orElseThrow(() -> new AuthException(ErrorCode.AUTH_TOKEN_INVALID));
    user.markEmailVerified(now);
    token.markUsed(now);
  }
}
