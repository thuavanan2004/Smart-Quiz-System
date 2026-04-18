package vn.smartquiz.auth.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.smartquiz.auth.common.AuthException;
import vn.smartquiz.auth.common.ErrorCode;
import vn.smartquiz.auth.domain.user.User;
import vn.smartquiz.auth.domain.user.UserOrganization;
import vn.smartquiz.auth.infrastructure.persistence.UserOrganizationRepository;
import vn.smartquiz.auth.infrastructure.persistence.UserRepository;

/** Build response cho GET /auth/me từ user id (claim `sub` của JWT). */
@Service
public class CurrentUserQuery {

  private final UserRepository userRepo;
  private final UserOrganizationRepository userOrgRepo;

  public CurrentUserQuery(UserRepository userRepo, UserOrganizationRepository userOrgRepo) {
    this.userRepo = userRepo;
    this.userOrgRepo = userOrgRepo;
  }

  @Transactional(readOnly = true)
  public Me execute(UUID userId) {
    User user =
        userRepo
            .findById(userId)
            .orElseThrow(() -> new AuthException(ErrorCode.AUTH_TOKEN_INVALID));

    List<OrgView> orgs =
        userOrgRepo.findActiveByUserId(userId).stream().map(this::toOrgView).toList();

    UUID activeOrgId = orgs.isEmpty() ? null : orgs.get(0).id();
    return new Me(
        new UserView(
            user.getId(),
            user.getEmail(),
            user.getFullName(),
            user.getLocale(),
            user.getTimezone(),
            user.isEmailVerified(),
            user.getCreatedAt(),
            user.getLastLoginAt()),
        orgs,
        activeOrgId,
        user.isMfaEnabled(),
        null);
  }

  private OrgView toOrgView(UserOrganization m) {
    return new OrgView(m.getOrgId(), null, m.getRole().getCode(), m.getJoinedAt(), m.isActive());
  }

  public record Me(
      UserView user,
      List<OrgView> orgs,
      UUID activeOrgId,
      boolean mfaEnabled,
      String platformRole) {}

  public record UserView(
      UUID id,
      String email,
      String fullName,
      String locale,
      String timezone,
      boolean emailVerified,
      Instant createdAt,
      Instant lastLoginAt) {}

  public record OrgView(UUID id, String name, String roleCode, Instant joinedAt, boolean active) {}
}
