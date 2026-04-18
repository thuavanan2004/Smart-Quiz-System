package vn.smartquiz.auth.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.smartquiz.auth.common.AuthException;
import vn.smartquiz.auth.common.ErrorCode;
import vn.smartquiz.auth.domain.token.RefreshTokenService;
import vn.smartquiz.auth.domain.user.User;
import vn.smartquiz.auth.infrastructure.persistence.UserOrganizationRepository;
import vn.smartquiz.auth.infrastructure.persistence.UserRepository;

/**
 * Admin lock user trong org (design §12.3 POST /admin/users/{id}/lock). Revoke refresh token để
 * session hiện hành chết tức thì. {@code durationMinutes=null} → lock vĩnh viễn (admin unlock thủ
 * công).
 */
@Service
public class AdminLockUserUseCase {

  private static final Logger log = LoggerFactory.getLogger(AdminLockUserUseCase.class);

  private final UserRepository userRepo;
  private final UserOrganizationRepository userOrgRepo;
  private final RefreshTokenService refreshTokenService;
  private final Clock clock;

  public AdminLockUserUseCase(
      UserRepository userRepo,
      UserOrganizationRepository userOrgRepo,
      RefreshTokenService refreshTokenService,
      Clock clock) {
    this.userRepo = userRepo;
    this.userOrgRepo = userOrgRepo;
    this.refreshTokenService = refreshTokenService;
    this.clock = clock;
  }

  @Transactional
  public void execute(Command cmd) {
    userOrgRepo
        .findActiveMembership(cmd.targetUserId(), cmd.orgId())
        .orElseThrow(() -> new AuthException(ErrorCode.AUTH_FORBIDDEN));
    User target =
        userRepo
            .findById(cmd.targetUserId())
            .orElseThrow(() -> new AuthException(ErrorCode.AUTH_FORBIDDEN));

    Instant now = clock.instant();
    Instant until =
        cmd.durationMinutes() == null ? null : now.plus(Duration.ofMinutes(cmd.durationMinutes()));
    target.lockManually(until, now);

    int revoked = refreshTokenService.revokeAllForUser(cmd.targetUserId(), now);
    log.info(
        "Admin {} locked user={} in org={} until={} — revoked {} sessions",
        cmd.actorId(),
        cmd.targetUserId(),
        cmd.orgId(),
        until,
        revoked);
  }

  public record Command(UUID actorId, UUID orgId, UUID targetUserId, Long durationMinutes) {}
}
