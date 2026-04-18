package vn.smartquiz.auth.domain.password;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import org.springframework.stereotype.Component;

/**
 * Argon2id wrapper với tham số OWASP 2024 (design §6.1). Target ~250ms trên server 4 vCPU.
 *
 * <p>Lưu ý: mật khẩu phải là {@code char[]} (không {@code String}) để có thể wipe khỏi bộ nhớ sau
 * khi hash — thư viện argon2-jvm tự wipe cho cả input.
 */
@Component
public class Argon2PasswordHasher implements PasswordHasher {

  private static final int MEMORY_KB = 65_536;
  private static final int ITERATIONS = 3;
  private static final int PARALLELISM = 4;

  private final Argon2 argon2 = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id);

  @Override
  public String hash(char[] rawPassword) {
    try {
      return argon2.hash(ITERATIONS, MEMORY_KB, PARALLELISM, rawPassword);
    } finally {
      argon2.wipeArray(rawPassword);
    }
  }

  @Override
  public boolean verify(String storedHash, char[] rawPassword) {
    try {
      return argon2.verify(storedHash, rawPassword);
    } finally {
      argon2.wipeArray(rawPassword);
    }
  }
}
