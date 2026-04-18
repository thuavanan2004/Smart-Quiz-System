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
 * Logout toàn bộ device (design §5.3). Bulk revoke tất cả refresh_tokens của user + blacklist jti
 * hiện tại. Các access token khác sẽ hết hạn tự nhiên (TTL 15 phút).
 */
@Service
public class LogoutAllUseCase {

  private static final Logger log = LoggerFactory.getLogger(LogoutAllUseCase.class);

  private final RefreshTokenService refreshTokenService;
  private final TokenBlacklist blacklist;
  private final Clock clock;

  public LogoutAllUseCase(
      RefreshTokenService refreshTokenService, TokenBlacklist blacklist, Clock clock) {
    this.refreshTokenService = refreshTokenService;
    this.blacklist = blacklist;
    this.clock = clock;
  }

  @Transactional
  public void execute(Command cmd) {
    Instant now = clock.instant();
    int revoked = refreshTokenService.revokeAllForUser(cmd.userId(), now);
    log.info("Logout-all for user={} — revoked {} refresh tokens", cmd.userId(), revoked);

    Duration remaining = Duration.between(now, cmd.accessExpiresAt());
    blacklist.ban(cmd.jti(), remaining);
  }

  public record Command(UUID userId, String jti, Instant accessExpiresAt) {}
}
