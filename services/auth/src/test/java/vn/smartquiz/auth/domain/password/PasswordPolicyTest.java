package vn.smartquiz.auth.domain.password;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

  private final PasswordPolicy policy = new PasswordPolicy();

  @Test
  void rejectsShortPassword() {
    var v = policy.check("Short1!".toCharArray(), "alice@x.vn", "Alice");
    assertThat(v).isEqualTo(PasswordPolicy.Violation.TOO_SHORT);
  }

  @Test
  void rejectsLowComplexity() {
    // 13 chars, only lowercase + digit → 2 groups
    var v = policy.check("abcdefgh123456".toCharArray(), "alice@x.vn", "Alice Nguyen");
    assertThat(v).isEqualTo(PasswordPolicy.Violation.INSUFFICIENT_COMPLEXITY);
  }

  @Test
  void rejectsIdentityInPassword() {
    var v = policy.check("alice.Secure!99x".toCharArray(), "alice@x.vn", "Alice Nguyen");
    assertThat(v).isEqualTo(PasswordPolicy.Violation.CONTAINS_IDENTITY);
  }

  @Test
  void acceptsStrongPassword() {
    var v = policy.check("Q9k.rt*Lm7zPx".toCharArray(), "alice@x.vn", "Alice Nguyen");
    assertThat(v).isNull();
  }
}
