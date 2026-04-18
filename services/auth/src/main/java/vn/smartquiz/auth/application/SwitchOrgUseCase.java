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
import vn.smartquiz.auth.domain.token.JwtTokenIssuer.AccessToken;
import vn.smartquiz.auth.domain.token.RefreshTokenService;
import vn.smartquiz.auth.domain.user.User;
import vn.smartquiz.auth.infrastructure.persistence.UserRepository;

/**
 * Switch active org (design §12.2). Rotate refresh token của session hiện tại với {@code
 * activeOrgId} mới, cấp access token có claim {@code org_id + authorities} tương ứng.
 *
 * <p>Yêu cầu: caller phải là member active của org đích. Nếu không → 403 AUTH_FORBIDDEN.
 */
@Service
public class SwitchOrgUseCase {

  private static final Logger log = LoggerFactory.getLogger(SwitchOrgUseCase.class);

  private final RefreshTokenService refreshTokenService;
  private final UserRepository userRepo;
  private final AccessTokenFactory accessTokenFactory;
  private final Clock clock;

  public SwitchOrgUseCase(
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

    User user =
        userRepo
            .findById(cmd.userId())
            .orElseThrow(() -> new AuthException(ErrorCode.AUTH_TOKEN_INVALID));

    if (!accessTokenFactory.isMemberOf(user.getId(), cmd.targetOrgId())) {
      throw new AuthException(ErrorCode.AUTH_FORBIDDEN);
    }

    RefreshTokenService.RotationResult rotated;
    try {
      rotated =
          refreshTokenService.rotateWithOrg(
              cmd.refreshToken(), cmd.userAgent(), cmd.targetOrgId(), now);
    } catch (RefreshTokenService.ReuseDetectedException reuse) {
      log.warn(
          "Stolen refresh token during switch-org for user={} — revoking all sessions",
          reuse.userId());
      refreshTokenService.revokeAllForUser(reuse.userId(), now);
      throw new AuthException(ErrorCode.AUTH_TOKEN_REVOKED);
    } catch (RefreshTokenService.InvalidRefreshException ex) {
      throw new AuthException(ErrorCode.AUTH_TOKEN_INVALID);
    }

    AccessToken access =
        accessTokenFactory.issueFor(
            user, rotated.issued().sessionId(), cmd.targetOrgId(), now);

    return new Result(access.serialized(), rotated.issued().token(), access.ttlSeconds());
  }

  public record Command(UUID userId, UUID targetOrgId, String refreshToken, String userAgent) {}

  public record Result(String accessToken, String refreshToken, long expiresInSeconds) {}
}
