package vn.smartquiz.auth.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.smartquiz.auth.common.AuthException;
import vn.smartquiz.auth.common.ErrorCode;
import vn.smartquiz.auth.domain.user.User;
import vn.smartquiz.auth.domain.user.UserOrganization;
import vn.smartquiz.auth.infrastructure.persistence.UserOrganizationRepository;
import vn.smartquiz.auth.infrastructure.persistence.UserRepository;

/**
 * List + lookup user theo org cho admin endpoints (design §12.3 GET /admin/users). Cap page size
 * 100 để tránh dump cả DB. Filter rỗng ({@code q=null, role=null}) trả về toàn org.
 */
@Service
public class AdminUserQuery {

  private static final int MAX_SIZE = 100;
  private static final int DEFAULT_SIZE = 20;

  private final UserRepository userRepo;
  private final UserOrganizationRepository userOrgRepo;

  public AdminUserQuery(UserRepository userRepo, UserOrganizationRepository userOrgRepo) {
    this.userRepo = userRepo;
    this.userOrgRepo = userOrgRepo;
  }

  @Transactional(readOnly = true)
  public Page execute(UUID orgId, String q, String roleCode, int page, int size) {
    int safePage = Math.max(page, 0);
    int safeSize = Math.min(Math.max(size, 1), MAX_SIZE);
    String safeQ = normalize(q);
    String safeRole = normalize(roleCode);

    List<User> users =
        userRepo.findByOrgFiltered(
            orgId, safeQ, safeRole, PageRequest.of(safePage, safeSize));
    long total = userRepo.countByOrgFiltered(orgId, safeQ, safeRole);

    List<Item> items = users.stream().map(u -> toItem(u, orgId)).toList();
    return new Page(items, total, safePage, safeSize);
  }

  @Transactional(readOnly = true)
  public Item getInOrg(UUID orgId, UUID userId) {
    UserOrganization membership =
        userOrgRepo
            .findActiveMembership(userId, orgId)
            .orElseThrow(() -> new AuthException(ErrorCode.AUTH_FORBIDDEN));
    User user =
        userRepo
            .findById(userId)
            .orElseThrow(() -> new AuthException(ErrorCode.AUTH_FORBIDDEN));
    return toItemFromMembership(user, membership);
  }

  private static String normalize(String s) {
    if (s == null) return null;
    String trimmed = s.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }

  private Item toItem(User u, UUID orgId) {
    var m = userOrgRepo.findActiveMembership(u.getId(), orgId).orElse(null);
    return toItemFromMembership(u, m);
  }

  private Item toItemFromMembership(User u, UserOrganization m) {
    String roleCode = m == null ? null : m.getRole().getCode();
    return new Item(
        u.getId(),
        u.getEmail(),
        u.getFullName(),
        roleCode,
        u.isEmailVerified(),
        u.isActive() && u.getDeletedAt() == null,
        isLocked(u),
        u.getLastLoginAt(),
        u.getCreatedAt());
  }

  private static boolean isLocked(User u) {
    return u.getLockedUntil() != null && u.getLockedUntil().isAfter(Instant.now());
  }

  public record Page(List<Item> items, long total, int page, int size) {}

  public record Item(
      UUID id,
      String email,
      String fullName,
      String roleCode,
      boolean emailVerified,
      boolean active,
      boolean locked,
      Instant lastLoginAt,
      Instant createdAt) {}
}
