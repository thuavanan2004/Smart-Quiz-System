package vn.smartquiz.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import vn.smartquiz.auth.domain.user.User;
import vn.smartquiz.auth.infrastructure.persistence.UserRepository;

/**
 * Full flow: register → email verify → login → /me. Dùng Postgres 16 Testcontainers và chạy Flyway
 * migrations thật.
 *
 * <p>Opt-in: set {@code RUN_INTEGRATION_TESTS=true} trước khi chạy. Cần Docker daemon + Ryuk
 * cleanup hoạt động. Trên Docker Desktop Windows có thể cần bật "Expose daemon on
 * tcp://localhost:2375" hoặc set {@code DOCKER_HOST=tcp://localhost:2375}.
 */
@EnabledIfEnvironmentVariable(named = "RUN_INTEGRATION_TESTS", matches = "true")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "auth.jwt.private-key-path=../../ops/keys/jwt.private.pem",
      "auth.jwt.public-key-path=../../ops/keys/jwt.public.pem",
      "auth.jwt.key-id=test-key",
      "auth.jwt.issuer=https://auth.test",
      "auth.dev.expose-verification-token=true",
      "auth.dev.expose-reset-token=true",
      "auth.rate-limit.enabled=false",
      "spring.flyway.locations=filesystem:../../database/postgresql/migrations"
    })
class AuthFlowIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("smartquiz")
          .withUsername("test")
          .withPassword("test");

  @Container
  static final GenericContainer<?> REDIS =
      new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

  @DynamicPropertySource
  static void dbProps(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379).toString());
  }

  @Autowired private MockMvc mvc;

  @Autowired private UserRepository userRepo;

  @Autowired private ObjectMapper mapper;

  @Test
  void registerVerifyLoginMeFlow() throws Exception {
    // 1. Register
    String registerBody =
        """
                {"email":"alice@test.vn","password":"Q9k.rt*Lm7zPx","full_name":"Alice Nguyen"}
                """;
    String registerResp =
        mvc.perform(
                post("/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(registerBody))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.user_id").isNotEmpty())
            .andExpect(jsonPath("$.email_verification_sent").value(true))
            .andExpect(jsonPath("$.verification_token_dev").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();

    String verifyToken = mapper.readTree(registerResp).get("verification_token_dev").asText();

    // 2. Login trước verify email → phải 403
    String loginBody =
        """
                {"email":"alice@test.vn","password":"Q9k.rt*Lm7zPx"}
                """;
    mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginBody))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_EMAIL_NOT_VERIFIED"));

    // 3. Verify email
    String verifyBody = "{\"token\":\"" + verifyToken + "\"}";
    mvc.perform(
            post("/auth/email/verify").contentType(MediaType.APPLICATION_JSON).content(verifyBody))
        .andExpect(status().isOk());

    // 4. Login thành công
    String loginResp =
        mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(loginBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").isNotEmpty())
            .andExpect(jsonPath("$.refresh_token").isNotEmpty())
            .andExpect(jsonPath("$.token_type").value("Bearer"))
            .andExpect(jsonPath("$.expires_in").value(900))
            .andReturn()
            .getResponse()
            .getContentAsString();

    String accessToken = mapper.readTree(loginResp).get("access_token").asText();

    // 5. /auth/me với Bearer token
    mvc.perform(get("/auth/me").header("Authorization", "Bearer " + accessToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.user.email").value("alice@test.vn"))
        .andExpect(jsonPath("$.user.full_name").value("Alice Nguyen"))
        .andExpect(jsonPath("$.user.email_verified").value(true))
        .andExpect(jsonPath("$.orgs").isArray())
        .andExpect(jsonPath("$.mfa_enabled").value(false));

    // 6. /auth/me không token → 401
    mvc.perform(get("/auth/me")).andExpect(status().isUnauthorized());

    // 7. Rotation: /auth/refresh đổi access+refresh mới
    String refreshPlain = mapper.readTree(loginResp).get("refresh_token").asText();
    String refreshBody = "{\"refresh_token\":\"" + refreshPlain + "\"}";
    String rotatedResp =
        mvc.perform(
                post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.access_token").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String newAccess = mapper.readTree(rotatedResp).get("access_token").asText();
    String newRefresh = mapper.readTree(rotatedResp).get("refresh_token").asText();
    assertThat(newAccess).isNotEqualTo(accessToken);
    assertThat(newRefresh).isNotEqualTo(refreshPlain);

    // 8. Stolen detection: dùng lại refresh token CŨ → 401 + tất cả session bị revoke
    mvc.perform(post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(refreshBody))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_TOKEN_REVOKED"));

    // Sau stolen detection, refresh mới cũng bị revoke → gọi refresh với token MỚI cũng fail
    String newRefreshBody = "{\"refresh_token\":\"" + newRefresh + "\"}";
    mvc.perform(
            post("/auth/refresh").contentType(MediaType.APPLICATION_JSON).content(newRefreshBody))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void logoutBlacklistsAccessToken() throws Exception {
    // Register + verify + login
    String email = "logout@test.vn";
    String regBody =
        "{\"email\":\""
            + email
            + "\",\"password\":\"Q9k.rt*Lm7zPx\",\"full_name\":\"Logout User\"}";
    String regResp =
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(regBody))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String verifyToken = mapper.readTree(regResp).get("verification_token_dev").asText();
    mvc.perform(
            post("/auth/email/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + verifyToken + "\"}"))
        .andExpect(status().isOk());

    String loginResp =
        mvc.perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"password\":\"Q9k.rt*Lm7zPx\"}"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String access = mapper.readTree(loginResp).get("access_token").asText();

    // /me hoạt động trước logout
    mvc.perform(get("/auth/me").header("Authorization", "Bearer " + access))
        .andExpect(status().isOk());

    // Logout → 204
    mvc.perform(post("/auth/logout").header("Authorization", "Bearer " + access))
        .andExpect(status().isNoContent());

    // /me với access token cũ → 401 (bị blacklist)
    mvc.perform(get("/auth/me").header("Authorization", "Bearer " + access))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void forgotPasswordResetAndReloginFlow() throws Exception {
    String email = "forgot@test.vn";
    String oldPass = "OldPass.rt*Lm7z";
    String newPass = "NewPass.xy&Kz82";

    // Register + verify
    String regResp =
        mvc.perform(
                post("/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\""
                            + email
                            + "\",\"password\":\""
                            + oldPass
                            + "\",\"full_name\":\"Forgot User\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    mvc.perform(
            post("/auth/email/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"token\":\""
                        + mapper.readTree(regResp).get("verification_token_dev").asText()
                        + "\"}"))
        .andExpect(status().isOk());

    // Forgot với email không tồn tại → vẫn 200, không có reset_token_dev (user không có)
    mvc.perform(
            post("/auth/password/forgot")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"unknown@test.vn\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reset_token_dev").doesNotExist());

    // Forgot với email thật → 200 + token
    String forgotResp =
        mvc.perform(
                post("/auth/password/forgot")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.reset_token_dev").isNotEmpty())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String resetToken = mapper.readTree(forgotResp).get("reset_token_dev").asText();

    // Reset với pwd mới
    mvc.perform(
            post("/auth/password/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + resetToken + "\",\"new_password\":\"" + newPass + "\"}"))
        .andExpect(status().isOk());

    // Login với pwd CŨ → 401
    mvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + oldPass + "\"}"))
        .andExpect(status().isUnauthorized());

    // Login với pwd MỚI → 200
    mvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + newPass + "\"}"))
        .andExpect(status().isOk());

    // Token reset đã used → không dùng lại được
    mvc.perform(
            post("/auth/password/reset")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + resetToken + "\",\"new_password\":\"Another.Pass12!\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_TOKEN_INVALID"));
  }

  @Test
  void changePasswordRevokesAllRefreshTokens() throws Exception {
    String email = "change@test.vn";
    String oldPass = "OldPass.rt*Lm7z";
    String newPass = "NewPass.xy&Kz82";

    String regResp =
        mvc.perform(
                post("/auth/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"email\":\""
                            + email
                            + "\",\"password\":\""
                            + oldPass
                            + "\",\"full_name\":\"Change User\"}"))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    mvc.perform(
            post("/auth/email/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"token\":\""
                        + mapper.readTree(regResp).get("verification_token_dev").asText()
                        + "\"}"))
        .andExpect(status().isOk());

    String loginResp =
        mvc.perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"password\":\"" + oldPass + "\"}"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String access = mapper.readTree(loginResp).get("access_token").asText();
    String refresh = mapper.readTree(loginResp).get("refresh_token").asText();

    // Change password với old sai → 401
    mvc.perform(
            post("/auth/password/change")
                .header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"old_password\":\"WrongOldPass99!\",\"new_password\":\"" + newPass + "\"}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));

    // Change password với old = new → history reuse → 422
    mvc.perform(
            post("/auth/password/change")
                .header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"old_password\":\"" + oldPass + "\",\"new_password\":\"" + oldPass + "\"}"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("AUTH_WEAK_PASSWORD"));

    // Change thành công
    mvc.perform(
            post("/auth/password/change")
                .header("Authorization", "Bearer " + access)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"old_password\":\"" + oldPass + "\",\"new_password\":\"" + newPass + "\"}"))
        .andExpect(status().isNoContent());

    // Refresh token cũ bị revoke
    mvc.perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refresh_token\":\"" + refresh + "\"}"))
        .andExpect(status().isUnauthorized());

    // Login với pwd mới OK
    mvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + newPass + "\"}"))
        .andExpect(status().isOk());
  }

  @Test
  void logoutAllRevokesAllRefreshTokens() throws Exception {
    String email = "logoutall@test.vn";
    String regBody =
        "{\"email\":\"" + email + "\",\"password\":\"Q9k.rt*Lm7zPx\",\"full_name\":\"All User\"}";
    String regResp =
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(regBody))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    mvc.perform(
            post("/auth/email/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"token\":\""
                        + mapper.readTree(regResp).get("verification_token_dev").asText()
                        + "\"}"))
        .andExpect(status().isOk());

    // 2 session
    String s1 =
        mvc.perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"password\":\"Q9k.rt*Lm7zPx\"}"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String s2 =
        mvc.perform(
                post("/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"email\":\"" + email + "\",\"password\":\"Q9k.rt*Lm7zPx\"}"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String access1 = mapper.readTree(s1).get("access_token").asText();
    String refresh2 = mapper.readTree(s2).get("refresh_token").asText();

    // logout-all từ session 1 → cả 2 refresh token bị revoke
    mvc.perform(post("/auth/logout-all").header("Authorization", "Bearer " + access1))
        .andExpect(status().isNoContent());

    // Dùng refresh của session 2 → bị reject (revoked, không phải reuse vì chưa ai rotate nó)
    // Thực tế hit ReuseDetectedException (vì check isRevoked trước) → 401 AUTH_TOKEN_REVOKED
    mvc.perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refresh_token\":\"" + refresh2 + "\"}"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void rejectsWeakPasswordAtRegister() throws Exception {
    String body =
        """
                {"email":"weak@test.vn","password":"short","full_name":"Bob"}
                """;
    mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("AUTH_WEAK_PASSWORD"));
  }

  @Test
  void rejectsDuplicateEmail() throws Exception {
    String body =
        """
                {"email":"dup@test.vn","password":"Q9k.rt*Lm7zPx","full_name":"Dup User"}
                """;
    mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isCreated());

    mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("AUTH_EMAIL_EXISTS"));
  }

  @Test
  void invalidCredentialsReturns401() throws Exception {
    // Seed directly: user với email_verified=true nhưng sai password
    String body =
        """
                {"email":"nobody@test.vn","password":"Q9k.rt*Lm7zPx"}
                """;
    mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"));
  }

  @Test
  void lockoutAfterFiveFailedLogins() throws Exception {
    String regBody =
        """
                {"email":"lock@test.vn","password":"Q9k.rt*Lm7zPx","full_name":"Lock User"}
                """;
    mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(regBody))
        .andExpect(status().isCreated());
    User u = userRepo.findByEmailIgnoreCase("lock@test.vn").orElseThrow();
    u.markEmailVerified(Instant.now());
    userRepo.save(u);

    String wrong =
        """
                {"email":"lock@test.vn","password":"Wrong.Password123!"}
                """;
    for (int i = 0; i < 5; i++) {
      mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(wrong));
    }
    mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(wrong))
        .andExpect(status().isLocked())
        .andExpect(jsonPath("$.code").value("AUTH_ACCOUNT_LOCKED"));

    // Cũng chặn cả khi password đúng
    String correct =
        """
                {"email":"lock@test.vn","password":"Q9k.rt*Lm7zPx"}
                """;
    mvc.perform(post("/auth/login").contentType(MediaType.APPLICATION_JSON).content(correct))
        .andExpect(status().isLocked());

    JsonNode n =
        mapper.readTree(
            mvc.perform(get("/.well-known/jwks.json"))
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(n.get("keys").isArray()).isTrue();
  }

  @Test
  void openidConfigurationExposesIssuerAndJwksUri() throws Exception {
    String resp =
        mvc.perform(get("/.well-known/openid-configuration"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode n = mapper.readTree(resp);
    assertThat(n.get("issuer").asText()).isEqualTo("https://auth.test");
    assertThat(n.get("jwks_uri").asText()).isEqualTo("https://auth.test/.well-known/jwks.json");
    assertThat(n.get("id_token_signing_alg_values_supported").get(0).asText()).isEqualTo("RS256");
  }

  @Test
  void sessionsListShowsCurrentAndRevokeWorks() throws Exception {
    String email = "sess@test.vn";
    String pass = "Q9k.rt*Lm7zPx";
    registerAndVerify(email, pass, "Session User");

    // 2 session (2 login)
    String s1Resp = loginAndGetBody(email, pass);
    String s2Resp = loginAndGetBody(email, pass);
    String access1 = mapper.readTree(s1Resp).get("access_token").asText();
    String access2 = mapper.readTree(s2Resp).get("access_token").asText();

    // List từ session 1 — thấy 2 entry, flag current=true ở session 1
    String listResp =
        mvc.perform(get("/auth/sessions").header("Authorization", "Bearer " + access1))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode list = mapper.readTree(listResp);
    assertThat(list.isArray()).isTrue();
    assertThat(list.size()).isEqualTo(2);

    // Tìm session của access2 → revoke nó từ session 1
    String otherSessionId = null;
    for (JsonNode item : list) {
      if (!item.get("current").asBoolean()) {
        otherSessionId = item.get("id").asText();
      }
    }
    assertThat(otherSessionId).isNotNull();

    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/auth/sessions/" + otherSessionId)
                .header("Authorization", "Bearer " + access1))
        .andExpect(status().isNoContent());

    // Refresh token của session 2 giờ bị revoke → /me vẫn OK (access chưa hết hạn), nhưng refresh fail
    String refresh2 = mapper.readTree(s2Resp).get("refresh_token").asText();
    mvc.perform(
            post("/auth/refresh")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"refresh_token\":\"" + refresh2 + "\"}"))
        .andExpect(status().isUnauthorized());

    // Access token 1 vẫn work
    mvc.perform(get("/auth/me").header("Authorization", "Bearer " + access1))
        .andExpect(status().isOk());
    // Silence unused var warning
    assertThat(access2).isNotBlank();
  }

  @Test
  void revokeSessionOfAnotherUserIs403() throws Exception {
    registerAndVerify("owner@test.vn", "Q9k.rt*Lm7zPx", "Owner");
    registerAndVerify("stranger@test.vn", "Q9k.rt*Lm7zPx", "Stranger");

    String strangerResp = loginAndGetBody("stranger@test.vn", "Q9k.rt*Lm7zPx");
    // Parse sid từ access token để có session id của stranger
    String strangerAccess = mapper.readTree(strangerResp).get("access_token").asText();
    String sid =
        com.nimbusds.jwt.SignedJWT.parse(strangerAccess).getJWTClaimsSet().getStringClaim("sid");

    String ownerResp = loginAndGetBody("owner@test.vn", "Q9k.rt*Lm7zPx");
    String ownerAccess = mapper.readTree(ownerResp).get("access_token").asText();

    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/auth/sessions/" + sid)
                .header("Authorization", "Bearer " + ownerAccess))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("AUTH_FORBIDDEN"));
  }

  // ---- Helpers ---------------------------------------------------------

  private void registerAndVerify(String email, String pass, String fullName) throws Exception {
    String regBody =
        "{\"email\":\""
            + email
            + "\",\"password\":\""
            + pass
            + "\",\"full_name\":\""
            + fullName
            + "\"}";
    String regResp =
        mvc.perform(post("/auth/register").contentType(MediaType.APPLICATION_JSON).content(regBody))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String verifyToken = mapper.readTree(regResp).get("verification_token_dev").asText();
    mvc.perform(
            post("/auth/email/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + verifyToken + "\"}"))
        .andExpect(status().isOk());
  }

  private String loginAndGetBody(String email, String pass) throws Exception {
    return mvc.perform(
            post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"" + pass + "\"}"))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString();
  }
}
