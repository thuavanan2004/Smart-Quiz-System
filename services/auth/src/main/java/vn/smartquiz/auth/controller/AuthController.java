package vn.smartquiz.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.smartquiz.auth.application.ChangePasswordUseCase;
import vn.smartquiz.auth.application.CurrentUserQuery;
import vn.smartquiz.auth.application.ForgotPasswordUseCase;
import vn.smartquiz.auth.application.LoginUseCase;
import vn.smartquiz.auth.application.LogoutAllUseCase;
import vn.smartquiz.auth.application.LogoutUseCase;
import vn.smartquiz.auth.application.RateLimitPolicy;
import vn.smartquiz.auth.application.RefreshTokenUseCase;
import vn.smartquiz.auth.application.RegisterUserUseCase;
import vn.smartquiz.auth.application.ResetPasswordUseCase;
import vn.smartquiz.auth.application.RevokeSessionUseCase;
import vn.smartquiz.auth.application.SessionsQuery;
import vn.smartquiz.auth.application.SwitchOrgUseCase;
import vn.smartquiz.auth.application.VerifyEmailUseCase;
import vn.smartquiz.auth.controller.dto.ChangePasswordRequest;
import vn.smartquiz.auth.controller.dto.ForgotPasswordRequest;
import vn.smartquiz.auth.controller.dto.ForgotPasswordResponse;
import vn.smartquiz.auth.controller.dto.LoginRequest;
import vn.smartquiz.auth.controller.dto.RefreshRequest;
import vn.smartquiz.auth.controller.dto.RegisterRequest;
import vn.smartquiz.auth.controller.dto.RegisterResponse;
import vn.smartquiz.auth.controller.dto.ResetPasswordRequest;
import vn.smartquiz.auth.controller.dto.SwitchOrgRequest;
import vn.smartquiz.auth.controller.dto.TokenPair;
import vn.smartquiz.auth.controller.dto.VerifyEmailRequest;

@RestController
@RequestMapping("/auth")
public class AuthController {

  private final RegisterUserUseCase registerUseCase;
  private final LoginUseCase loginUseCase;
  private final VerifyEmailUseCase verifyEmailUseCase;
  private final CurrentUserQuery currentUserQuery;
  private final RefreshTokenUseCase refreshTokenUseCase;
  private final LogoutUseCase logoutUseCase;
  private final LogoutAllUseCase logoutAllUseCase;
  private final ForgotPasswordUseCase forgotPasswordUseCase;
  private final ResetPasswordUseCase resetPasswordUseCase;
  private final ChangePasswordUseCase changePasswordUseCase;
  private final SwitchOrgUseCase switchOrgUseCase;
  private final SessionsQuery sessionsQuery;
  private final RevokeSessionUseCase revokeSessionUseCase;
  private final RateLimitPolicy rateLimitPolicy;

  public AuthController(
      RegisterUserUseCase registerUseCase,
      LoginUseCase loginUseCase,
      VerifyEmailUseCase verifyEmailUseCase,
      CurrentUserQuery currentUserQuery,
      RefreshTokenUseCase refreshTokenUseCase,
      LogoutUseCase logoutUseCase,
      LogoutAllUseCase logoutAllUseCase,
      ForgotPasswordUseCase forgotPasswordUseCase,
      ResetPasswordUseCase resetPasswordUseCase,
      ChangePasswordUseCase changePasswordUseCase,
      SwitchOrgUseCase switchOrgUseCase,
      SessionsQuery sessionsQuery,
      RevokeSessionUseCase revokeSessionUseCase,
      RateLimitPolicy rateLimitPolicy) {
    this.registerUseCase = registerUseCase;
    this.loginUseCase = loginUseCase;
    this.verifyEmailUseCase = verifyEmailUseCase;
    this.currentUserQuery = currentUserQuery;
    this.refreshTokenUseCase = refreshTokenUseCase;
    this.logoutUseCase = logoutUseCase;
    this.logoutAllUseCase = logoutAllUseCase;
    this.forgotPasswordUseCase = forgotPasswordUseCase;
    this.resetPasswordUseCase = resetPasswordUseCase;
    this.changePasswordUseCase = changePasswordUseCase;
    this.switchOrgUseCase = switchOrgUseCase;
    this.sessionsQuery = sessionsQuery;
    this.revokeSessionUseCase = revokeSessionUseCase;
    this.rateLimitPolicy = rateLimitPolicy;
  }

  @PostMapping("/register")
  public ResponseEntity<RegisterResponse> register(
      @Valid @RequestBody RegisterRequest req, HttpServletRequest http) {
    rateLimitPolicy.checkRegister(clientIp(http));
    var result =
        registerUseCase.execute(
            new RegisterUserUseCase.Command(
                req.email().toLowerCase(), req.fullName(), req.password().toCharArray()));
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new RegisterResponse(
                result.userId(), result.emailVerificationSent(), result.verificationTokenDev()));
  }

  @PostMapping("/login")
  public ResponseEntity<TokenPair> login(
      @Valid @RequestBody LoginRequest req, HttpServletRequest http) {
    String email = req.email().toLowerCase();
    rateLimitPolicy.checkLoginByIp(clientIp(http));
    rateLimitPolicy.checkLoginByEmail(email);
    var result =
        loginUseCase.execute(
            new LoginUseCase.Command(
                email, req.password().toCharArray(), http.getHeader("User-Agent")));
    return ResponseEntity.ok(
        TokenPair.bearer(result.accessToken(), result.refreshToken(), result.expiresInSeconds()));
  }

  @PostMapping("/email/verify")
  public ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest req) {
    verifyEmailUseCase.execute(req.token());
    return ResponseEntity.ok().build();
  }

  @GetMapping("/me")
  public ResponseEntity<CurrentUserQuery.Me> me(@AuthenticationPrincipal Jwt jwt) {
    UUID userId = UUID.fromString(jwt.getSubject());
    return ResponseEntity.ok(currentUserQuery.execute(userId));
  }

  @PostMapping("/refresh")
  public ResponseEntity<TokenPair> refresh(
      @Valid @RequestBody RefreshRequest req, HttpServletRequest http) {
    rateLimitPolicy.checkRefresh(clientIp(http));
    var result =
        refreshTokenUseCase.execute(
            new RefreshTokenUseCase.Command(req.refreshToken(), http.getHeader("User-Agent")));
    return ResponseEntity.ok(
        TokenPair.bearer(result.accessToken(), result.refreshToken(), result.expiresInSeconds()));
  }

  @PostMapping("/logout")
  public ResponseEntity<Void> logout(@AuthenticationPrincipal Jwt jwt) {
    logoutUseCase.execute(
        new LogoutUseCase.Command(
            UUID.fromString(jwt.getSubject()), sidFrom(jwt), jwt.getId(), expiresAt(jwt)));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/logout-all")
  public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal Jwt jwt) {
    logoutAllUseCase.execute(
        new LogoutAllUseCase.Command(
            UUID.fromString(jwt.getSubject()), jwt.getId(), expiresAt(jwt)));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/password/forgot")
  public ResponseEntity<ForgotPasswordResponse> forgotPassword(
      @Valid @RequestBody ForgotPasswordRequest req, HttpServletRequest http) {
    rateLimitPolicy.checkForgot(clientIp(http));
    var result = forgotPasswordUseCase.execute(req.email().toLowerCase());
    return ResponseEntity.ok(new ForgotPasswordResponse(result.resetTokenDev()));
  }

  @PostMapping("/password/reset")
  public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest req) {
    resetPasswordUseCase.execute(
        new ResetPasswordUseCase.Command(req.token(), req.newPassword().toCharArray()));
    return ResponseEntity.ok().build();
  }

  @PostMapping("/password/change")
  public ResponseEntity<Void> changePassword(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ChangePasswordRequest req) {
    changePasswordUseCase.execute(
        new ChangePasswordUseCase.Command(
            UUID.fromString(jwt.getSubject()),
            req.oldPassword().toCharArray(),
            req.newPassword().toCharArray()));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/switch-org")
  public ResponseEntity<TokenPair> switchOrg(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody SwitchOrgRequest req,
      HttpServletRequest http) {
    var result =
        switchOrgUseCase.execute(
            new SwitchOrgUseCase.Command(
                UUID.fromString(jwt.getSubject()),
                req.orgId(),
                req.refreshToken(),
                http.getHeader("User-Agent")));
    return ResponseEntity.ok(
        TokenPair.bearer(result.accessToken(), result.refreshToken(), result.expiresInSeconds()));
  }

  @GetMapping("/sessions")
  public ResponseEntity<List<SessionsQuery.SessionView>> listSessions(
      @AuthenticationPrincipal Jwt jwt) {
    UUID userId = UUID.fromString(jwt.getSubject());
    return ResponseEntity.ok(sessionsQuery.execute(userId, sidFrom(jwt)));
  }

  @DeleteMapping("/sessions/{id}")
  public ResponseEntity<Void> revokeSession(
      @AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID sessionId) {
    revokeSessionUseCase.execute(UUID.fromString(jwt.getSubject()), sessionId);
    return ResponseEntity.noContent().build();
  }

  private static String clientIp(HttpServletRequest req) {
    String xff = req.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) {
      return xff.split(",")[0].trim();
    }
    return req.getRemoteAddr();
  }

  private static UUID sidFrom(Jwt jwt) {
    String sid = jwt.getClaimAsString("sid");
    return sid == null || sid.isBlank() ? null : UUID.fromString(sid);
  }

  private static Instant expiresAt(Jwt jwt) {
    Instant exp = jwt.getExpiresAt();
    return exp == null ? Date.from(Instant.EPOCH).toInstant() : exp;
  }
}
