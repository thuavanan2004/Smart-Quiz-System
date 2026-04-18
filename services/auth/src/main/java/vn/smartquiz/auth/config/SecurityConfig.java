package vn.smartquiz.auth.config;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

  @Bean
  public SecurityFilterChain filterChain(
      HttpSecurity http, JwtDecoder decoder, CorsConfigurationSource corsConfigurationSource)
      throws Exception {
    http.csrf(csrf -> csrf.disable())
        .cors(cors -> cors.configurationSource(corsConfigurationSource))
        .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .headers(
            headers ->
                headers
                    // Spring Security bật X-Content-Type-Options: nosniff mặc định, giữ nguyên.
                    .frameOptions(f -> f.deny())
                    // HSTS chỉ có hiệu lực trên HTTPS; browser bỏ qua khi HTTP. Design §13.4.
                    .httpStrictTransportSecurity(
                        hsts ->
                            hsts.includeSubDomains(true).preload(true).maxAgeInSeconds(31_536_000))
                    .referrerPolicy(
                        r ->
                            r.policy(
                                ReferrerPolicyHeaderWriter.ReferrerPolicy
                                    .STRICT_ORIGIN_WHEN_CROSS_ORIGIN))
                    .permissionsPolicy(p -> p.policy("geolocation=(), camera=(), microphone=()")))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/.well-known/**",
                        "/health",
                        "/ready",
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

  /**
   * CORS explicit origins từ config {@code auth.cors.allowed-origins} (CSV). Bao gồm các header cần
   * cho auth flow: Authorization, Content-Type, X-Request-Id. Expose Retry-After để client xử lý
   * rate limit + X-Request-Id để trace.
   */
  @Bean
  public CorsConfigurationSource corsConfigurationSource(
      @Value("${auth.cors.allowed-origins}") String allowedOriginsCsv) {
    List<String> origins =
        Arrays.stream(allowedOriginsCsv.split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .toList();
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(origins);
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id"));
    config.setExposedHeaders(List.of("X-Request-Id", "Retry-After"));
    config.setAllowCredentials(true);
    config.setMaxAge(3600L);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
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
