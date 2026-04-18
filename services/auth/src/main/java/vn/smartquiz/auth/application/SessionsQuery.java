package vn.smartquiz.auth.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.smartquiz.auth.domain.token.RefreshToken;
import vn.smartquiz.auth.domain.token.RefreshTokenService;

/** Liệt kê session (refresh token active) của 1 user — design §12.2 GET /auth/sessions. */
@Service
public class SessionsQuery {

  private final RefreshTokenService refreshTokenService;
  private final Clock clock;

  public SessionsQuery(RefreshTokenService refreshTokenService, Clock clock) {
    this.refreshTokenService = refreshTokenService;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public List<SessionView> execute(UUID userId, UUID currentSessionId) {
    Instant now = clock.instant();
    return refreshTokenService.listActiveByUser(userId, now).stream()
        .map(rt -> toView(rt, currentSessionId))
        .toList();
  }

  private SessionView toView(RefreshToken rt, UUID currentSessionId) {
    return new SessionView(
        rt.getId(),
        rt.getUserAgent(),
        rt.getActiveOrgId(),
        rt.getCreatedAt(),
        rt.getExpiresAt(),
        rt.getId().equals(currentSessionId));
  }

  public record SessionView(
      UUID id,
      String userAgent,
      UUID activeOrgId,
      Instant createdAt,
      Instant expiresAt,
      boolean current) {}
}
