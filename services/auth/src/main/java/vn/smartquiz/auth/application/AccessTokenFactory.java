package vn.smartquiz.auth.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.smartquiz.auth.domain.token.JwtTokenIssuer;
import vn.smartquiz.auth.domain.token.JwtTokenIssuer.AccessToken;
import vn.smartquiz.auth.domain.token.JwtTokenIssuer.OrgClaim;
import vn.smartquiz.auth.domain.user.Permission;
import vn.smartquiz.auth.domain.user.User;
import vn.smartquiz.auth.domain.user.UserOrganization;
import vn.smartquiz.auth.infrastructure.persistence.UserOrganizationRepository;

/**
 * Gom lại logic xây access token + claims — đã bị copy trong LoginUseCase, RefreshTokenUseCase
 * (và thêm SwitchOrgUseCase). Tập trung 1 nơi để thay đổi claim (vd thêm {@code
 * platform_role}) không phải sửa 3 chỗ.
 */
@Component
public class AccessTokenFactory {

  private final UserOrganizationRepository userOrgRepo;
  private final JwtTokenIssuer tokenIssuer;

  public AccessTokenFactory(UserOrganizationRepository userOrgRepo, JwtTokenIssuer tokenIssuer) {
    this.userOrgRepo = userOrgRepo;
    this.tokenIssuer = tokenIssuer;
  }

  /**
   * Xây access token cho phiên đã có refresh token. Nếu {@code requestedActiveOrgId} != null: phải
   * là org mà user đang active membership. Nếu null: fallback về membership đầu tiên (legacy
   * hành vi khi user chưa switch-org lần nào).
   */
  public AccessToken issueFor(User user, UUID sessionId, UUID requestedActiveOrgId, Instant now) {
    List<UserOrganization> memberships = userOrgRepo.findActiveByUserId(user.getId());
    List<OrgClaim> orgClaims = new ArrayList<>(memberships.size());
    Set<String> authorities = new LinkedHashSet<>();
    UUID activeOrgId = pickActiveOrg(memberships, requestedActiveOrgId);

    for (UserOrganization m : memberships) {
      var role = m.getRole();
      orgClaims.add(new OrgClaim(m.getOrgId().toString(), role.getCode(), role.getId().toString()));
      if (m.getOrgId().equals(activeOrgId)) {
        for (Permission p : role.getPermissions()) {
          authorities.add(p.getCode());
        }
      }
    }

    return tokenIssuer.issueAccessToken(
        new JwtTokenIssuer.AccessTokenInput(
            user.getId(),
            sessionId,
            user.getEmail(),
            user.isEmailVerified(),
            activeOrgId,
            orgClaims,
            List.copyOf(authorities),
            now));
  }

  /** @return true nếu user là member active của org. */
  public boolean isMemberOf(UUID userId, UUID orgId) {
    return userOrgRepo.findActiveByUserId(userId).stream()
        .anyMatch(m -> m.getOrgId().equals(orgId));
  }

  private static UUID pickActiveOrg(List<UserOrganization> memberships, UUID requested) {
    if (requested != null) {
      for (UserOrganization m : memberships) {
        if (m.getOrgId().equals(requested)) {
          return requested;
        }
      }
      // Requested org không còn là membership hợp lệ (bị kick?) → fallback đầu tiên.
    }
    return memberships.isEmpty() ? null : memberships.get(0).getOrgId();
  }
}
