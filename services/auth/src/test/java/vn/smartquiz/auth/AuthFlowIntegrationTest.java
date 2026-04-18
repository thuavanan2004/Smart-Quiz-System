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
      "auth.dev-expose-verification-token=true",
      "spring.flyway.locations=filesystem:../../database/postgresql/migrations"
    })
class AuthFlowIntegrationTest {

  @Container
  static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>("postgres:16-alpine")
          .withDatabaseName("smartquiz")
          .withUsername("test")
          .withPassword("test");

  @DynamicPropertySource
  static void dbProps(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
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
}
