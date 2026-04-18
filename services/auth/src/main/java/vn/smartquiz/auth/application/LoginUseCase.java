package vn.smartquiz.auth.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.smartquiz.auth.common.AuthException;
import vn.smartquiz.auth.common.ErrorCode;
import vn.smartquiz.auth.domain.password.Argon2PasswordHasher;
import vn.smartquiz.auth.domain.token.JwtTokenIssuer;
import vn.smartquiz.auth.domain.token.JwtTokenIssuer.OrgClaim;
import vn.smartquiz.auth.domain.token.RefreshTokenService;
import vn.smartquiz.auth.domain.user.Permission;
import vn.smartquiz.auth.domain.user.User;
import vn.smartquiz.auth.domain.user.UserOrganization;
import vn.smartquiz.auth.infrastructure.persistence.UserOrganizationRepository;
import vn.smartquiz.auth.infrastructure.persistence.UserRepository;

/**
 * Login email+password. Slice 1: không MFA, không rate-limit (slice 4), không timing-safe
 * dummy-hash khi user không tồn tại (cải thiện ở slice 4 cùng rate-limit).
 */
@Service
public class LoginUseCase {

  private final UserRepository userRepo;
  private final UserOrganizationRepository userOrgRepo;
  private final Argon2PasswordHasher hasher;
  private final JwtTokenIssuer tokenIssuer;
  private final RefreshTokenService refreshTokenService;
  private final Clock clock;

  public LoginUseCase(
      UserRepository userRepo,
      UserOrganizationRepository userOrgRepo,
      Argon2PasswordHasher hasher,
      JwtTokenIssuer tokenIssuer,
      RefreshTokenService refreshTokenService,
      Clock clock) {
    this.userRepo = userRepo;
    this.userOrgRepo = userOrgRepo;
    this.hasher = hasher;
    this.tokenIssuer = tokenIssuer;
    this.refreshTokenService = refreshTokenService;
    this.clock = clock;
  }

  @Transactional
  public Result execute(Command cmd) {
    Instant now = clock.instant();

    User user =
        userRepo
            .findByEmailIgnoreCase(cmd.email())
            .orElseThrow(() -> new AuthException(ErrorCode.AUTH_INVALID_CREDENTIALS));

    if (!user.isActive() || user.getDeletedAt() != null) {
      throw new AuthException(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    if (user.isLocked(now)) {
      throw new AuthException(ErrorCode.AUTH_ACCOUNT_LOCKED);
    }

    if (user.getPasswordHash() == null || !hasher.verify(user.getPasswordHash(), cmd.password())) {
      user.recordFailedLogin(now);
      if (user.isLocked(now)) {
        throw new AuthException(ErrorCode.AUTH_ACCOUNT_LOCKED);
      }
      throw new AuthException(ErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    if (!user.isEmailVerified()) {
      throw new AuthException(ErrorCode.AUTH_EMAIL_NOT_VERIFIED);
    }

    user.recordSuccessfulLogin(now);

    List<UserOrganization> memberships = userOrgRepo.findActiveByUserId(user.getId());
    List<OrgClaim> orgClaims = new ArrayList<>();
    Set<String> authorities = new LinkedHashSet<>();
    UUID activeOrgId = memberships.isEmpty() ? null : memberships.get(0).getOrgId();
    for (UserOrganization m : memberships) {
      var role = m.getRole();
      orgClaims.add(new OrgClaim(m.getOrgId().toString(), role.getCode(), role.getId().toString()));
      for (Permission p : role.getPermissions()) {
        authorities.add(p.getCode());
      }
    }

    var access =
        tokenIssuer.issueAccessToken(
            new JwtTokenIssuer.AccessTokenInput(
                user.getId(),
                user.getEmail(),
                user.isEmailVerified(),
                activeOrgId,
                orgClaims,
                List.copyOf(authorities),
                now));

    var refresh = refreshTokenService.issue(user.getId(), cmd.userAgent(), now);

    return new Result(access.serialized(), refresh.token(), access.ttlSeconds());
  }

  public record Command(String email, char[] password, String userAgent) {}

  public record Result(String accessToken, String refreshToken, long expiresInSeconds) {}
}
