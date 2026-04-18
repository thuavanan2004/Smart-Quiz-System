package vn.smartquiz.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/.well-known/**",
                                "/actuator/health/**",
                                "/actuator/info",
                                "/actuator/prometheus")
                        .permitAll()
                        // MVP: tất cả endpoint khác tạm thời permitAll — sẽ siết dần khi
                        // scaffold register/login/refresh flow (Task kế tiếp).
                        .anyRequest().permitAll());
        return http.build();
    }
}
