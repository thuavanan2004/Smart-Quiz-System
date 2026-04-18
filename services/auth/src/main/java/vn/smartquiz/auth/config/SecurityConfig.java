package vn.smartquiz.auth.config;

import java.security.interfaces.RSAPublicKey;
import java.util.Collection;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http, JwtDecoder decoder) throws Exception {
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/.well-known/**",
                        "/actuator/health/**",
                        "/actuator/info",
                        "/actuator/prometheus",
                        "/auth/register",
                        "/auth/login",
                        "/auth/login/mfa",
                        "/auth/refresh",
                        "/auth/password/forgot",
                        "/auth/password/reset",
                        "/auth/email/verify",
                        "/auth/oauth/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .oauth2ResourceServer(
            oauth2 ->
                oauth2.jwt(
                    jwt ->
                        jwt.decoder(decoder)
                            .jwtAuthenticationConverter(authoritiesClaimConverter())));
    return http.build();
  }

  /** Verify JWT bằng public key cục bộ (cùng keypair mà Auth ký). Khác service → JWKS. */
  @Bean
  public JwtDecoder jwtDecoder(RSAPublicKey publicKey) {
    return NimbusJwtDecoder.withPublicKey(publicKey).build();
  }

  /** Extract claim `authorities` (array of permission codes) vào {@link GrantedAuthority}. */
  private Converter<Jwt, AbstractAuthenticationToken> authoritiesClaimConverter() {
    JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
    converter.setJwtGrantedAuthoritiesConverter(new AuthoritiesClaimConverter());
    return converter;
  }

  private static final class AuthoritiesClaimConverter
      implements Converter<Jwt, Collection<GrantedAuthority>> {
    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
      List<String> codes = jwt.getClaimAsStringList("authorities");
      if (codes == null || codes.isEmpty()) {
        return List.of();
      }
      return codes.stream().<GrantedAuthority>map(SimpleGrantedAuthority::new).toList();
    }
  }
}
