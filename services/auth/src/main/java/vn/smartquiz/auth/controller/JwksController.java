package vn.smartquiz.auth.controller;

import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.util.List;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.smartquiz.auth.config.AuthJwtProperties;

@RestController
public class JwksController {

  private static final JWKSelector MATCH_ALL = new JWKSelector(new JWKMatcher.Builder().build());

  private final JWKSource<SecurityContext> jwkSource;
  private final AuthJwtProperties jwtProps;

  public JwksController(JWKSource<SecurityContext> jwkSource, AuthJwtProperties jwtProps) {
    this.jwkSource = jwkSource;
    this.jwtProps = jwtProps;
  }

  @GetMapping(path = "/.well-known/jwks.json", produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> jwks() throws Exception {
    JWKSet jwks = new JWKSet(jwkSource.get(MATCH_ALL, null));
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePublic())
        .body(jwks.toPublicJWKSet().toJSONObject());
  }

  /**
   * OpenID Connect Discovery (RFC 8414 phạm vi rút gọn) — các service khác dùng để tự động tìm
   * jwks_uri + issuer khi cấu hình {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}.
   * Auth Service không phải OIDC Provider đầy đủ (chưa authorization endpoint, userinfo...) —
   * chỉ expose field bắt buộc để resource server hoạt động.
   */
  @GetMapping(
      path = "/.well-known/openid-configuration",
      produces = MediaType.APPLICATION_JSON_VALUE)
  public ResponseEntity<Map<String, Object>> openidConfiguration() {
    Map<String, Object> config =
        Map.of(
            "issuer", jwtProps.issuer(),
            "jwks_uri", jwtProps.issuer() + "/.well-known/jwks.json",
            "id_token_signing_alg_values_supported", List.of("RS256"),
            "response_types_supported", List.of("token"),
            "subject_types_supported", List.of("public"),
            "token_endpoint_auth_methods_supported", List.of("none"));
    return ResponseEntity.ok()
        .cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePublic())
        .body(config);
  }
}
