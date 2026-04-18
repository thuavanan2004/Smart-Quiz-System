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
import vn.smartquiz.auth.domain.user.User;
import vn.smartquiz.auth.domain.user.UserOrganization;
import vn.smartquiz.auth.infrastructure.persistence.UserOrganizationRepository;
import vn.smartquiz.auth.infrastructure.persistence.UserRepository;

/**
 * Soft delete user (design §12.3 DELETE /admin/users/{id} + §18.4 open q6 "anonymize vs hard
 * delete" → chọn soft delete). Tác dụng:
 *
 * <ul>
 *   <li>Deactivate membership của target trong org admin đang action (các org khác không đụng
 *       để tránh admin A xoá user của org B qua shared user record).
 *   <li>Nếu target không còn membership active nào → soft delete user (set deleted_at).
 *   <li>Revoke toàn bộ refresh token của target.
 * </ul>
 */
@Service
public class AdminDeleteUserUseCase {

  private static final Logger log = LoggerFactory.getLogger(AdminDeleteUserUseCase.class);

  private final UserRepository userRepo;
  private final UserOrganizationRepository userOrgRepo;
  private final RefreshTokenService refreshTokenService;
  private final Clock clock;

  public AdminDeleteUserUseCase(
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
    // Kiểm tra "last org" TRƯỚC khi deactivate để query không phụ thuộc flush order.
    boolean isLastOrg =
        userOrgRepo.findActiveByUserId(cmd.targetUserId()).stream()
            .allMatch(m -> m.getOrgId().equals(cmd.orgId()));
    UserOrganization membership =
        userOrgRepo
            .findActiveMembership(cmd.targetUserId(), cmd.orgId())
            .orElseThrow(() -> new AuthException(ErrorCode.AUTH_FORBIDDEN));
    membership.deactivate();

    Instant now = clock.instant();
    if (isLastOrg) {
      User target =
          userRepo
              .findById(cmd.targetUserId())
              .orElseThrow(() -> new AuthException(ErrorCode.AUTH_FORBIDDEN));
      target.softDelete(now);
    }

    int revoked = refreshTokenService.revokeAllForUser(cmd.targetUserId(), now);
    log.info(
        "Admin {} removed user={} from org={} (hardDelete={}) — revoked {} sessions",
        cmd.actorId(),
        cmd.targetUserId(),
        cmd.orgId(),
        isLastOrg,
        revoked);
  }

  public record Command(UUID actorId, UUID orgId, UUID targetUserId) {}
}
