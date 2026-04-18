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
import vn.smartquiz.auth.domain.token.RefreshTokenService;

/**
 * Revoke 1 session cụ thể (design §12.2 DELETE /auth/sessions/{id}). Chỉ cho phép caller revoke
 * session của chính họ. Không blacklist access jti của session đó vì ta không có jti — access
 * token sẽ hết hạn tự nhiên (15 phút).
 */
@Service
public class RevokeSessionUseCase {

  private static final Logger log = LoggerFactory.getLogger(RevokeSessionUseCase.class);

  private final RefreshTokenService refreshTokenService;
  private final Clock clock;

  public RevokeSessionUseCase(RefreshTokenService refreshTokenService, Clock clock) {
    this.refreshTokenService = refreshTokenService;
    this.clock = clock;
  }

  @Transactional
  public void execute(UUID userId, UUID sessionId) {
    Instant now = clock.instant();
    var rt =
        refreshTokenService
            .findActiveById(sessionId)
            .orElseThrow(() -> new AuthException(ErrorCode.AUTH_TOKEN_INVALID));
    if (!rt.getUserId().equals(userId)) {
      // Không cho phép revoke của user khác — trả 403 (thay vì 404 để không leak existence).
      throw new AuthException(ErrorCode.AUTH_FORBIDDEN);
    }
    refreshTokenService.revoke(rt, now);
    log.info("Session revoked user={} session={}", userId, sessionId);
  }
}
