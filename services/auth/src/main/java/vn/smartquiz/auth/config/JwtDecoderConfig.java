package vn.smartquiz.auth.config;

import java.security.interfaces.RSAPublicKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import vn.smartquiz.auth.domain.token.TokenBlacklist;

/**
 * {@link JwtDecoder} gồm verify chữ ký + check jti blacklist (design §5.3). Tách khỏi {@link
 * SecurityConfig} vì logic validator phức tạp hơn 1 bean definition.
 */
@Configuration
public class JwtDecoderConfig {

  @Bean
  public JwtDecoder jwtDecoder(RSAPublicKey publicKey, TokenBlacklist blacklist) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(publicKey).build();
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefault(), new BlacklistValidator(blacklist)));
    return decoder;
  }

  /** Reject JWT nếu jti nằm trong blacklist. */
  static final class BlacklistValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error REVOKED =
        new OAuth2Error(
            "invalid_token", "Token đã bị thu hồi", "https://smartquiz.vn/errors/token-revoked");

    private final TokenBlacklist blacklist;

    BlacklistValidator(TokenBlacklist blacklist) {
      this.blacklist = blacklist;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt jwt) {
      String jti = jwt.getId();
      if (jti != null && blacklist.isBanned(jti)) {
        return OAuth2TokenValidatorResult.failure(REVOKED);
      }
      return OAuth2TokenValidatorResult.success();
    }
  }
}
