package vn.smartquiz.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.smartquiz.auth.application.CurrentUserQuery;
import vn.smartquiz.auth.application.LoginUseCase;
import vn.smartquiz.auth.application.RegisterUserUseCase;
import vn.smartquiz.auth.application.VerifyEmailUseCase;
import vn.smartquiz.auth.controller.dto.LoginRequest;
import vn.smartquiz.auth.controller.dto.RegisterRequest;
import vn.smartquiz.auth.controller.dto.RegisterResponse;
import vn.smartquiz.auth.controller.dto.TokenPair;
import vn.smartquiz.auth.controller.dto.VerifyEmailRequest;

@RestController
@RequestMapping("/auth")
public class AuthController {

  private final RegisterUserUseCase registerUseCase;
  private final LoginUseCase loginUseCase;
  private final VerifyEmailUseCase verifyEmailUseCase;
  private final CurrentUserQuery currentUserQuery;

  public AuthController(
      RegisterUserUseCase registerUseCase,
      LoginUseCase loginUseCase,
      VerifyEmailUseCase verifyEmailUseCase,
      CurrentUserQuery currentUserQuery) {
    this.registerUseCase = registerUseCase;
    this.loginUseCase = loginUseCase;
    this.verifyEmailUseCase = verifyEmailUseCase;
    this.currentUserQuery = currentUserQuery;
  }

  @PostMapping("/register")
  public ResponseEntity<RegisterResponse> register(@Valid @RequestBody RegisterRequest req) {
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
    var result =
        loginUseCase.execute(
            new LoginUseCase.Command(
                req.email().toLowerCase(),
                req.password().toCharArray(),
                http.getHeader("User-Agent")));
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
}
