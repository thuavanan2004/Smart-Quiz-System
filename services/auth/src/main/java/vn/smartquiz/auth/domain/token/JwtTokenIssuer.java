package vn.smartquiz.auth.domain.token;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import vn.smartquiz.auth.config.AuthJwtProperties;

/**
 * Phát JWT RS256 theo design §5.1. Slice 1 chỉ cấp access token; phát thêm claim `orgs[]` +
 * `authorities[]` tối giản (rỗng nếu user chưa thuộc org). Switch org / MFA passed để các slice
 * sau.
 */
@Component
public class JwtTokenIssuer {

  private static final JWKSelector MATCH_ALL = new JWKSelector(new JWKMatcher.Builder().build());

  private final JWKSource<SecurityContext> jwkSource;
  private final String issuer;
  private final List<String> audience;
  private final Duration accessTtl;

  public JwtTokenIssuer(JWKSource<SecurityContext> jwkSource, AuthJwtProperties props) {
    this.jwkSource = jwkSource;
    this.issuer = props.issuer();
    this.audience = List.of(props.audience());
    this.accessTtl = Duration.ofSeconds(props.accessTtlSeconds());
  }

  public AccessToken issueAccessToken(AccessTokenInput input) {
    Instant now = input.now();
    Instant exp = now.plus(accessTtl);
    String jti = UUID.randomUUID().toString();

    List<Map<String, String>> orgsClaim =
        input.orgs().stream()
            .map(o -> Map.of("id", o.id(), "role_code", o.roleCode(), "role_id", o.roleId()))
            .toList();

    JWTClaimsSet.Builder claims =
        new JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject(input.userId().toString())
            .issueTime(Date.from(now))
            .expirationTime(Date.from(exp))
            .jwtID(jti)
            .claim("email", input.email())
            .claim("email_verified", input.emailVerified())
            .claim("org_id", input.activeOrgId() == null ? null : input.activeOrgId().toString())
            .claim("orgs", orgsClaim)
            .claim("authorities", input.authorities())
            .claim("platform_role", null)
            .claim("mfa_passed", false)
            .claim("sid", input.sessionId() == null ? null : input.sessionId().toString())
            .claim("token_type", "access");

    try {
      RSAKey signingKey = (RSAKey) jwkSource.get(MATCH_ALL, null).get(0);
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
              claims.build());
      jwt.sign(new RSASSASigner(signingKey.toRSAPrivateKey()));
      return new AccessToken(jwt.serialize(), exp, accessTtl.toSeconds());
    } catch (JOSEException | ClassCastException e) {
      throw new IllegalStateException("Cannot sign JWT — signing key misconfigured", e);
    } catch (Exception e) {
      throw new IllegalStateException("Cannot load signing key", e);
    }
  }

  public record AccessToken(String serialized, Instant expiresAt, long ttlSeconds) {}

  public record AccessTokenInput(
      UUID userId,
      UUID sessionId,
      String email,
      boolean emailVerified,
      UUID activeOrgId,
      List<OrgClaim> orgs,
      List<String> authorities,
      Instant now) {}

  public record OrgClaim(String id, String roleCode, String roleId) {}

  /** Test helper. */
  JWK currentJwk() throws Exception {
    return jwkSource.get(MATCH_ALL, null).get(0);
  }
}
