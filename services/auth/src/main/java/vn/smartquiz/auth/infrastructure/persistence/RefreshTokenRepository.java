package vn.smartquiz.auth.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import vn.smartquiz.auth.domain.token.RefreshToken;

@Repository
public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

  Optional<RefreshToken> findByTokenHash(byte[] tokenHash);

  @Query(
      "select rt from RefreshToken rt where rt.userId = :userId and rt.revoked = false "
          + "and rt.expiresAt > :now order by rt.createdAt desc")
  List<RefreshToken> findActiveByUserId(UUID userId, Instant now);

  @Modifying
  @Query(
      "update RefreshToken rt set rt.revoked = true, rt.revokedAt = :now "
          + "where rt.userId = :userId and rt.revoked = false")
  int revokeAllByUserId(UUID userId, Instant now);
}
