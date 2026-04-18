package vn.smartquiz.auth.domain.notification;

/**
 * Gửi thông báo ngoài — email, SMS, push. Slice 3 chỉ cần email. Thật sự gửi qua SMTP/MailHog sẽ
 * thay ở slice sau (hoặc uỷ quyền hẳn cho Notification Service qua Kafka theo design §1.1).
 */
public interface NotificationSender {

  void sendEmailVerification(String toEmail, String tokenPlain);

  void sendPasswordReset(String toEmail, String tokenPlain);

  void sendPasswordChanged(String toEmail);
}
