package vn.smartquiz.auth.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.smartquiz.auth.domain.token.RefreshTokenService;
import vn.smartquiz.auth.domain.token.TokenBlacklist;

/**
 * Logout 1 device (design §5.3). Revoke refresh token của session hiện tại + blacklist jti access
 * token. TTL blacklist = thời gian còn lại của access token (hết hạn thì access token tự invalid).
 */
@Service
public class LogoutUseCase {

  private static final Logger log = LoggerFactory.getLogger(LogoutUseCase.class);

  private final RefreshTokenService refreshTokenService;
  private final TokenBlacklist blacklist;
  private final Clock clock;

  public LogoutUseCase(
      RefreshTokenService refreshTokenService, TokenBlacklist blacklist, Clock clock) {
    this.refreshTokenService = refreshTokenService;
    this.blacklist = blacklist;
    this.clock = clock;
  }

  @Transactional
  public void execute(Command cmd) {
    Instant now = clock.instant();
    if (cmd.sessionId() != null) {
      refreshTokenService
          .findActiveById(cmd.sessionId())
          .filter(rt -> rt.getUserId().equals(cmd.userId()))
          .ifPresent(rt -> refreshTokenService.revoke(rt, now));
    } else {
      log.debug("Logout for user={} — no sid claim in access token (legacy?)", cmd.userId());
    }

    Duration remaining = Duration.between(now, cmd.accessExpiresAt());
    blacklist.ban(cmd.jti(), remaining);
  }

  public record Command(UUID userId, UUID sessionId, String jti, Instant accessExpiresAt) {}
}
