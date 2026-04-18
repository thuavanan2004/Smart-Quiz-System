package vn.smartquiz.auth.infrastructure.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import vn.smartquiz.auth.domain.user.PasswordHistory;

@Repository
public interface PasswordHistoryRepository extends JpaRepository<PasswordHistory, UUID> {

  @Query("select ph from PasswordHistory ph where ph.userId = :userId order by ph.changedAt desc")
  List<PasswordHistory> findRecentByUser(UUID userId, Pageable pageable);
}
