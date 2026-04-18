package vn.smartquiz.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Pageable;
import vn.smartquiz.auth.domain.password.Argon2PasswordHasher;
import vn.smartquiz.auth.domain.user.PasswordHistory;
import vn.smartquiz.auth.infrastructure.persistence.PasswordHistoryRepository;

class PasswordHistoryCheckTest {

  private PasswordHistoryRepository repo;
  private Argon2PasswordHasher hasher;
  private PasswordHistoryCheck check;
  private UUID userId;

  @BeforeEach
  void setUp() {
    repo = Mockito.mock(PasswordHistoryRepository.class);
    hasher = new Argon2PasswordHasher();
    check = new PasswordHistoryCheck(repo, hasher);
    userId = UUID.randomUUID();
  }

  @Test
  void matchesRecentReturnsTrueWhenPasswordReused() {
    String oldHash = hasher.hash("OldPass.123456".toCharArray());
    when(repo.findRecentByUser(eq(userId), any(Pageable.class)))
        .thenReturn(List.of(PasswordHistory.record(userId, oldHash, Instant.now())));

    assertThat(check.matchesRecent(userId, "OldPass.123456".toCharArray())).isTrue();
  }

  @Test
  void matchesRecentReturnsFalseWhenPasswordNew() {
    String oldHash = hasher.hash("OldPass.123456".toCharArray());
    when(repo.findRecentByUser(eq(userId), any(Pageable.class)))
        .thenReturn(List.of(PasswordHistory.record(userId, oldHash, Instant.now())));

    assertThat(check.matchesRecent(userId, "BrandNew.Pwd.77".toCharArray())).isFalse();
  }

  @Test
  void matchesRecentReturnsFalseWhenHistoryEmpty() {
    when(repo.findRecentByUser(eq(userId), any(Pageable.class))).thenReturn(List.of());
    assertThat(check.matchesRecent(userId, "AnyPass.123456".toCharArray())).isFalse();
  }
}
