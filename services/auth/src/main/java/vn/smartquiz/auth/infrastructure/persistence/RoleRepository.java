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

  /**
   * Role dùng được trong org: system role (org_id null) HOẶC custom role của chính org đó. Admin
   * chỉ được chọn role thuộc 1 trong 2 scope này khi gán cho member.
   */
  @Query(
      "select r from Role r where r.code = :code and r.active = true "
          + "and (r.orgId is null or r.orgId = :orgId)")
  Optional<Role> findAssignableRole(UUID orgId, String code);
}
