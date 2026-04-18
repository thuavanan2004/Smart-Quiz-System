package vn.smartquiz.auth.application;

import java.time.Clock;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.smartquiz.auth.common.AuthException;
import vn.smartquiz.auth.common.ErrorCode;
import vn.smartquiz.auth.domain.user.User;
import vn.smartquiz.auth.infrastructure.persistence.UserOrganizationRepository;
import vn.smartquiz.auth.infrastructure.persistence.UserRepository;

/**
 * Admin unlock user trong org (design §12.3 POST /admin/users/{id}/unlock). Reset failed-login
 * counter + xóa locked_until. KHÔNG issue token — user tự login lại như bình thường.
 */
@Service
public class AdminUnlockUserUseCase {

  private static final Logger log = LoggerFactory.getLogger(AdminUnlockUserUseCase.class);

  private final UserRepository userRepo;
  private final UserOrganizationRepository userOrgRepo;
  private final Clock clock;

  public AdminUnlockUserUseCase(
      UserRepository userRepo, UserOrganizationRepository userOrgRepo, Clock clock) {
    this.userRepo = userRepo;
    this.userOrgRepo = userOrgRepo;
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
    target.unlock(clock.instant());
    log.info(
        "Admin {} unlocked user={} in org={}", cmd.actorId(), cmd.targetUserId(), cmd.orgId());
  }

  public record Command(UUID actorId, UUID orgId, UUID targetUserId) {}
}
