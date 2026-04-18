package vn.smartquiz.auth.infrastructure.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import vn.smartquiz.auth.domain.notification.NotificationSender;

/**
 * Stub log-based (MVP). Token plaintext chỉ ở DEBUG — production log ở mức INFO không để rò rỉ.
 * Thay bằng SMTP/MailHog hoặc publish Kafka topic `auth.notification.*` khi có Notification Service
 * thật.
 */
@Component
public class LoggingNotificationSender implements NotificationSender {

  private static final Logger log = LoggerFactory.getLogger(LoggingNotificationSender.class);

  @Override
  public void sendEmailVerification(String toEmail, String tokenPlain) {
    log.info("[notification] email.verify to={} (token TTL 24h)", toEmail);
    log.debug("[notification] email.verify plaintext token for {}: {}", toEmail, tokenPlain);
  }

  @Override
  public void sendPasswordReset(String toEmail, String tokenPlain) {
    log.info("[notification] password.reset to={} (token TTL 1h)", toEmail);
    log.debug("[notification] password.reset plaintext token for {}: {}", toEmail, tokenPlain);
  }

  @Override
  public void sendPasswordChanged(String toEmail) {
    log.info("[notification] password.changed to={} (security notice)", toEmail);
  }
}
