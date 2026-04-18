package vn.smartquiz.auth.domain.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import vn.smartquiz.auth.config.AuthJwtProperties;
import vn.smartquiz.auth.infrastructure.persistence.RefreshTokenRepository;

class RefreshTokenServiceTest {

  private RefreshTokenRepository repo;
  private RefreshTokenService svc;
  private Instant now;

  @BeforeEach
  void setUp() {
    repo = Mockito.mock(RefreshTokenRepository.class);
    AuthJwtProperties props =
        new AuthJwtProperties(
            Path.of("/tmp/priv"),
            Path.of("/tmp/pub"),
            "test-key",
            "https://auth.test",
            "smartquiz-api",
            900L,
            7);
    svc = new RefreshTokenService(repo, props);
    now = Clock.systemUTC().instant();
  }

  @Test
  void issueStoresSaltedHash() {
    UUID userId = UUID.randomUUID();
    when(repo.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

    var issued = svc.issue(userId, "ua/1.0", null, now);

    assertThat(issued.token()).isNotBlank();
    assertThat(issued.ttlSeconds()).isEqualTo(Duration.ofDays(7).toSeconds());
    ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
    verify(repo).save(captor.capture());
    assertThat(captor.getValue().getUserId()).isEqualTo(userId);
    assertThat(captor.getValue().getTokenHash()).hasSize(32); // SHA-256
    assertThat(captor.getValue().isRevoked()).isFalse();
  }

  @Test
  void rotateRevokesOldAndIssuesNew() {
    UUID userId = UUID.randomUUID();
    when(repo.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));

    var original = svc.issue(userId, "ua/1.0", null, now);
    RefreshToken oldEntity = capturedLastSaved();

    when(repo.findByTokenHash(RefreshTokenService.sha256(original.token())))
        .thenReturn(Optional.of(oldEntity));

    var rotated = svc.rotate(original.token(), "ua/1.0", now.plusSeconds(10));

    assertThat(rotated.userId()).isEqualTo(userId);
    assertThat(rotated.issued().token()).isNotEqualTo(original.token());
    assertThat(oldEntity.isRevoked()).isTrue();
    verify(repo, times(2)).save(any(RefreshToken.class)); // 1 issue + 1 rotate-issue
  }

  @Test
  void rotateWithRevokedTokenTriggersStolenDetection() {
    UUID userId = UUID.randomUUID();
    when(repo.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));
    var issued = svc.issue(userId, "ua", null, now);
    RefreshToken entity = capturedLastSaved();
    entity.revoke(now.plusSeconds(5));

    when(repo.findByTokenHash(RefreshTokenService.sha256(issued.token())))
        .thenReturn(Optional.of(entity));

    assertThatThrownBy(() -> svc.rotate(issued.token(), "ua", now.plusSeconds(10)))
        .isInstanceOf(RefreshTokenService.ReuseDetectedException.class)
        .extracting(e -> ((RefreshTokenService.ReuseDetectedException) e).userId())
        .isEqualTo(userId);
  }

  @Test
  void rotateUnknownTokenThrowsInvalid() {
    when(repo.findByTokenHash(any())).thenReturn(Optional.empty());

    assertThatThrownBy(() -> svc.rotate("bogus", "ua", now))
        .isInstanceOf(RefreshTokenService.InvalidRefreshException.class);
  }

  @Test
  void rotateExpiredTokenThrowsInvalid() {
    UUID userId = UUID.randomUUID();
    when(repo.save(any(RefreshToken.class))).thenAnswer(inv -> inv.getArgument(0));
    var issued = svc.issue(userId, "ua", null, now);
    RefreshToken entity = capturedLastSaved();

    when(repo.findByTokenHash(RefreshTokenService.sha256(issued.token())))
        .thenReturn(Optional.of(entity));

    // Rotate sau khi token đã hết hạn (TTL=7 ngày, dùng giờ +8 ngày)
    Instant future = now.plus(Duration.ofDays(8));
    assertThatThrownBy(() -> svc.rotate(issued.token(), "ua", future))
        .isInstanceOf(RefreshTokenService.InvalidRefreshException.class);
  }

  @Test
  void revokeAllForUserDelegatesToRepo() {
    UUID userId = UUID.randomUUID();
    when(repo.revokeAllByUserId(userId, now)).thenReturn(3);
    int count = svc.revokeAllForUser(userId, now);
    assertThat(count).isEqualTo(3);
  }

  private RefreshToken capturedLastSaved() {
    ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
    verify(repo, Mockito.atLeastOnce()).save(captor.capture());
    return captor.getValue();
  }
}
