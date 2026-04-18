package vn.smartquiz.auth.domain.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.SignedJWT;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class JwtTokenIssuerTest {

  private static RSAKey keypair;
  private static JwtTokenIssuer issuer;

  @BeforeAll
  static void init() throws Exception {
    keypair = new RSAKeyGenerator(2048).keyID("test-key").keyUse(KeyUse.SIGNATURE).generate();
    JWKSource<SecurityContext> src = new ImmutableJWKSet<>(new JWKSet(keypair));
    issuer = new JwtTokenIssuer(src, "https://auth.test", 900, "smartquiz-api");
  }

  @Test
  void issuedTokenIsSignedRs256AndClaimsMatch() throws Exception {
    UUID userId = UUID.randomUUID();
    Instant now = Clock.systemUTC().instant();

    var token =
        issuer.issueAccessToken(
            new JwtTokenIssuer.AccessTokenInput(
                userId,
                "alice@test.vn",
                true,
                null,
                List.of(),
                List.of("attempt.start", "analytics.self"),
                now));

    SignedJWT parsed = SignedJWT.parse(token.serialized());
    assertThat(parsed.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.RS256);
    assertThat(parsed.getHeader().getKeyID()).isEqualTo("test-key");

    // Verify chữ ký bằng public key tương ứng
    assertThat(parsed.verify(new RSASSAVerifier(keypair.toRSAPublicKey()))).isTrue();

    var claims = parsed.getJWTClaimsSet();
    assertThat(claims.getSubject()).isEqualTo(userId.toString());
    assertThat(claims.getIssuer()).isEqualTo("https://auth.test");
    assertThat(claims.getAudience()).containsExactly("smartquiz-api");
    assertThat(claims.getStringClaim("email")).isEqualTo("alice@test.vn");
    assertThat(claims.getBooleanClaim("email_verified")).isTrue();
    assertThat(claims.getStringClaim("token_type")).isEqualTo("access");
    assertThat(claims.getStringListClaim("authorities"))
        .containsExactly("attempt.start", "analytics.self");
  }
}
