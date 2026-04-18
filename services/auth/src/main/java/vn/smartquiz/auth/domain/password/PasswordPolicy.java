package vn.smartquiz.auth.domain.password;

import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Password policy theo design §6.2: length 12-128, ≥3/4 nhóm ký tự, không chứa email/fullname
 * (slice 1 chưa check history/common-list — ghi chú trong trace).
 */
@Component
public class PasswordPolicy {

  private static final int MIN_LEN = 12;
  private static final int MAX_LEN = 128;

  public enum Violation {
    TOO_SHORT,
    TOO_LONG,
    INSUFFICIENT_COMPLEXITY,
    CONTAINS_IDENTITY
  }

  /** Validate password. Trả null nếu hợp lệ, hoặc {@link Violation} đầu tiên gặp. */
  public Violation check(char[] password, String email, String fullName) {
    if (password == null || password.length < MIN_LEN) {
      return Violation.TOO_SHORT;
    }
    if (password.length > MAX_LEN) {
      return Violation.TOO_LONG;
    }
    if (groupsPresent(password) < 3) {
      return Violation.INSUFFICIENT_COMPLEXITY;
    }
    String pwdLower = new String(password).toLowerCase(Locale.ROOT);
    if (containsIdentity(pwdLower, email) || containsIdentity(pwdLower, fullName)) {
      return Violation.CONTAINS_IDENTITY;
    }
    return null;
  }

  private static int groupsPresent(char[] pwd) {
    boolean upper = false;
    boolean lower = false;
    boolean digit = false;
    boolean special = false;
    for (char c : pwd) {
      if (Character.isUpperCase(c)) {
        upper = true;
      } else if (Character.isLowerCase(c)) {
        lower = true;
      } else if (Character.isDigit(c)) {
        digit = true;
      } else {
        special = true;
      }
    }
    return (upper ? 1 : 0) + (lower ? 1 : 0) + (digit ? 1 : 0) + (special ? 1 : 0);
  }

  private static boolean containsIdentity(String pwdLower, String identity) {
    if (identity == null || identity.isBlank()) {
      return false;
    }
    String idLower = identity.toLowerCase(Locale.ROOT);
    // Tách local-part của email + mỗi token của full name
    for (String token : idLower.split("[\\s@._-]+")) {
      if (token.length() >= 4 && pwdLower.contains(token)) {
        return true;
      }
    }
    return false;
  }
}
