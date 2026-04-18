package vn.smartquiz.auth.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import vn.smartquiz.auth.domain.user.UserOrganization;

@Repository
public interface UserOrganizationRepository
    extends JpaRepository<UserOrganization, UserOrganization.Key> {

  @Query("select uo from UserOrganization uo where uo.userId = :userId and uo.active = true")
  List<UserOrganization> findActiveByUserId(UUID userId);

  @Query(
      "select uo from UserOrganization uo "
          + "where uo.userId = :userId and uo.orgId = :orgId and uo.active = true")
  Optional<UserOrganization> findActiveMembership(UUID userId, UUID orgId);
}
