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
import vn.smartquiz.auth.domain.user.Role;
import vn.smartquiz.auth.domain.user.UserOrganization;
import vn.smartquiz.auth.infrastructure.persistence.RoleRepository;
import vn.smartquiz.auth.infrastructure.persistence.UserOrganizationRepository;

/**
 * Đổi role của user trong 1 org (design §12.3 PATCH /admin/users/{id}/role). Revoke mọi refresh
 * token của target để JWT cũ mang role cũ không còn dùng được (user buộc login lại).
 */
@Service
public class AdminChangeRoleUseCase {

  private static final Logger log = LoggerFactory.getLogger(AdminChangeRoleUseCase.class);

  private final UserOrganizationRepository userOrgRepo;
  private final RoleRepository roleRepo;
  private final RefreshTokenService refreshTokenService;
  private final Clock clock;

  public AdminChangeRoleUseCase(
      UserOrganizationRepository userOrgRepo,
      RoleRepository roleRepo,
      RefreshTokenService refreshTokenService,
      Clock clock) {
    this.userOrgRepo = userOrgRepo;
    this.roleRepo = roleRepo;
    this.refreshTokenService = refreshTokenService;
    this.clock = clock;
  }

  @Transactional
  public void execute(Command cmd) {
    UserOrganization membership =
        userOrgRepo
            .findActiveMembership(cmd.targetUserId(), cmd.orgId())
            .orElseThrow(() -> new AuthException(ErrorCode.AUTH_FORBIDDEN));

    Role newRole =
        roleRepo
            .findAssignableRole(cmd.orgId(), cmd.newRoleCode())
            .orElseThrow(
                () ->
                    new AuthException(
                        ErrorCode.AUTH_FORBIDDEN, "Role không hợp lệ cho org này"));

    membership.changeRole(newRole);

    Instant now = clock.instant();
    int revoked = refreshTokenService.revokeAllForUser(cmd.targetUserId(), now);
    log.info(
        "Admin {} changed role of user={} in org={} to {} — revoked {} sessions",
        cmd.actorId(),
        cmd.targetUserId(),
        cmd.orgId(),
        cmd.newRoleCode(),
        revoked);
  }

  public record Command(UUID actorId, UUID orgId, UUID targetUserId, String newRoleCode) {}
}
