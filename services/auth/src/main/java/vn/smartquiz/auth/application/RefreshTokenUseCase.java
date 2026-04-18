package vn.smartquiz.auth.application;

import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.smartquiz.auth.common.AuthException;
import vn.smartquiz.auth.common.ErrorCode;
import vn.smartquiz.auth.domain.token.JwtTokenIssuer.AccessToken;
import vn.smartquiz.auth.domain.token.RefreshTokenService;
import vn.smartquiz.auth.domain.user.User;
import vn.smartquiz.auth.infrastructure.persistence.UserRepository;

/**
 * Rotation refresh token (design §5.2). Mỗi gọi thành công: revoke token cũ + cấp cặp mới, giữ
 * nguyên {@code activeOrgId} của session. Stolen detection: nếu token đã revoked bị re-use →
 * revoke toàn bộ session của user.
 */
@Service
public class RefreshTokenUseCase {

  private static final Logger log = LoggerFactory.getLogger(RefreshTokenUseCase.class);

  private final RefreshTokenService refreshTokenService;
  private final UserRepository userRepo;
  private final AccessTokenFactory accessTokenFactory;
  private final Clock clock;

  public RefreshTokenUseCase(
      RefreshTokenService refreshTokenService,
      UserRepository userRepo,
      AccessTokenFactory accessTokenFactory,
      Clock clock) {
    this.refreshTokenService = refreshTokenService;
    this.userRepo = userRepo;
    this.accessTokenFactory = accessTokenFactory;
    this.clock = clock;
  }

  @Transactional
  public Result execute(Command cmd) {
    Instant now = clock.instant();

    RefreshTokenService.RotationResult rotated;
    try {
      rotated = refreshTokenService.rotate(cmd.refreshToken(), cmd.userAgent(), now);
    } catch (RefreshTokenService.ReuseDetectedException reuse) {
      log.warn("Stolen refresh token detected for user={} — revoking all sessions", reuse.userId());
      refreshTokenService.revokeAllForUser(reuse.userId(), now);
      throw new AuthException(ErrorCode.AUTH_TOKEN_REVOKED);
    } catch (RefreshTokenService.InvalidRefreshException ex) {
      throw new AuthException(ErrorCode.AUTH_TOKEN_INVALID);
    }

    User user =
        userRepo
            .findById(rotated.userId())
            .orElseThrow(() -> new AuthException(ErrorCode.AUTH_TOKEN_INVALID));

    if (!user.isActive() || user.getDeletedAt() != null) {
      throw new AuthException(ErrorCode.AUTH_TOKEN_INVALID);
    }

    AccessToken access =
        accessTokenFactory.issueFor(
            user, rotated.issued().sessionId(), rotated.issued().activeOrgId(), now);

    return new Result(access.serialized(), rotated.issued().token(), access.ttlSeconds());
  }

  public record Command(String refreshToken, String userAgent) {}

  public record Result(String accessToken, String refreshToken, long expiresInSeconds) {}
}
