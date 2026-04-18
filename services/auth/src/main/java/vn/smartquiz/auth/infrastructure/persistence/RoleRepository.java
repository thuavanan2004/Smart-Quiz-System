package vn.smartquiz.auth.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import vn.smartquiz.auth.domain.user.Role;

@Repository
public interface RoleRepository extends JpaRepository<Role, UUID> {

  @Query("select r from Role r where r.code = :code and r.orgId is null")
  Optional<Role> findSystemRoleByCode(String code);
}
