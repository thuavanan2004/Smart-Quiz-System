package vn.smartquiz.auth.domain.password;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class Argon2PasswordHasherTest {

  private final Argon2PasswordHasher hasher = new Argon2PasswordHasher();

  @Test
  void hashThenVerifyRoundTrip() {
    String hash = hasher.hash("CorrectHorse.Battery42!".toCharArray());
    assertThat(hash).startsWith("$argon2id$");
    assertThat(hasher.verify(hash, "CorrectHorse.Battery42!".toCharArray())).isTrue();
  }

  @Test
  void verifyRejectsWrongPassword() {
    String hash = hasher.hash("CorrectHorse.Battery42!".toCharArray());
    assertThat(hasher.verify(hash, "wrong-password-123!".toCharArray())).isFalse();
  }

  @Test
  void hashIsNondeterministic() {
    String a = hasher.hash("same-password-123!".toCharArray());
    String b = hasher.hash("same-password-123!".toCharArray());
    assertThat(a).isNotEqualTo(b);
  }
}
