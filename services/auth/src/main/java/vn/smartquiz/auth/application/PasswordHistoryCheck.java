package vn.smartquiz.auth.application;

import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import vn.smartquiz.auth.domain.password.Argon2PasswordHasher;
import vn.smartquiz.auth.domain.user.PasswordHistory;
import vn.smartquiz.auth.infrastructure.persistence.PasswordHistoryRepository;

/** Check password mới không trùng 5 hash gần nhất (design §6.2). */
@Component
public class PasswordHistoryCheck {

  private static final int HISTORY_SIZE = 5;

  private final PasswordHistoryRepository repo;
  private final Argon2PasswordHasher hasher;

  public PasswordHistoryCheck(PasswordHistoryRepository repo, Argon2PasswordHasher hasher) {
    this.repo = repo;
    this.hasher = hasher;
  }

  /**
   * Trả true nếu {@code rawPassword} trùng với 1 trong các hash gần đây. Clone input mỗi iteration
   * vì {@link Argon2PasswordHasher#verify} wipe char[] sau mỗi lần gọi.
   */
  public boolean matchesRecent(UUID userId, char[] rawPassword) {
    var recent = repo.findRecentByUser(userId, PageRequest.of(0, HISTORY_SIZE));
    for (PasswordHistory ph : recent) {
      if (hasher.verify(ph.getPasswordHash(), rawPassword.clone())) {
        return true;
      }
    }
    return false;
  }
}
