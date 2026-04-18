package vn.smartquiz.auth.application;

import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.smartquiz.auth.common.AuthException;
import vn.smartquiz.auth.common.ErrorCode;
import vn.smartquiz.auth.domain.password.Argon2PasswordHasher;
import vn.smartquiz.auth.domain.token.JwtTokenIssuer.AccessToken;
import vn.smartquiz.auth.domain.token.RefreshTokenService;
import vn.smartquiz.auth.domain.user.User;
import vn.smartquiz.auth.infrastructure.persistence.UserRepository;

/**
 * Login email+password. Lockout + rotation refresh token. Authorities trong JWT chỉ của org
 * active — fallback về membership đầu tiên khi user chưa từng switch-org.
 */
@Service
public class LoginUseCase {

  private final UserRepository userRepo;
  private final Argon2PasswordHasher hasher;
  private final AccessTokenFactory accessTokenFactory;
  private final RefreshTokenService refreshTokenService;
  private final Clock clock;

  public LoginUseCase(
      UserRepository userRepo,
      Argon2PasswordHasher hasher,
      AccessTokenFactory accessTokenFactory,
      RefreshTokenService refreshTokenService,
      Clock clock) {
    this.userRepo = userRepo;
    this.hasher = hasher;
    this.accessTokenFactory = accessTokenFactory;
    this.refreshTokenService = refreshTokenService;
    this.clock = clock;
  }

  @Transactional
  public Result execute(Command cmd) {
    Instant now = clock.instant();

    User user =
        userRepo
            .findByEmailIgnoreCase(cmd.email())
            .orElseThrow(() -> new AuthException(ErrorCode.AUTH_INVALID_CREDENTIALS));

    if (!user.isActive() || user.getDeletedAt() != null) {
      throw new AuthException(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    if (user.isLocked(now)) {
      throw new AuthException(ErrorCode.AUTH_ACCOUNT_LOCKED);
    }

    if (user.getPasswordHash() == null || !hasher.verify(user.getPasswordHash(), cmd.password())) {
      user.recordFailedLogin(now);
      if (user.isLocked(now)) {
        throw new AuthException(ErrorCode.AUTH_ACCOUNT_LOCKED);
      }
      throw new AuthException(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    if (!user.isEmailVerified()) {
      throw new AuthException(ErrorCode.AUTH_EMAIL_NOT_VERIFIED);
    }

    user.recordSuccessfulLogin(now);

    // Lần đầu login: activeOrgId=null → factory pick membership đầu tiên. Client muốn đổi thì
    // gọi /auth/switch-org sau.
    var refresh = refreshTokenService.issue(user.getId(), cmd.userAgent(), null, now);
    AccessToken access = accessTokenFactory.issueFor(user, refresh.sessionId(), null, now);

    return new Result(access.serialized(), refresh.token(), access.ttlSeconds());
  }

  public record Command(String email, char[] password, String userAgent) {}

  public record Result(String accessToken, String refreshToken, long expiresInSeconds) {}
}
