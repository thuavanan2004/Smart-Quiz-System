package vn.smartquiz.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dev-only flags — rò rỉ token trong response để test không cần SMTP. KHÔNG bật ở prod. Gom 2 cờ
 * vào 1 record vì cùng concern (dev ergonomics cho token flow).
 */
@ConfigurationProperties("auth.dev")
public record AuthDevProperties(boolean exposeVerificationToken, boolean exposeResetToken) {}
