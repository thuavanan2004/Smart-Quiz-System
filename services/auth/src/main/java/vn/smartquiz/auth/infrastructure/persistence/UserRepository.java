package vn.smartquiz.auth.infrastructure.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import vn.smartquiz.auth.domain.user.User;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

  Optional<User> findByEmailIgnoreCase(String email);

  boolean existsByEmailIgnoreCase(String email);

  /**
   * Danh sách user thuộc 1 org (chỉ membership active). Filter optional theo chuỗi {@code q}
   * (match email/fullName LIKE) và {@code roleCode}. Order theo created_at DESC. Dùng cross-join
   * JPQL + WHERE để tránh entity-join syntax (Hibernate 6 hỗ trợ nhưng kém tương thích).
   */
  @Query(
      "select u from User u, UserOrganization m "
          + "where m.userId = u.id and m.orgId = :orgId and m.active = true "
          + "and u.deletedAt is null "
          + "and (:q is null or lower(u.email) like lower(concat('%', :q, '%')) "
          + "     or lower(u.fullName) like lower(concat('%', :q, '%'))) "
          + "and (:roleCode is null or m.role.code = :roleCode) "
          + "order by u.createdAt desc")
  List<User> findByOrgFiltered(UUID orgId, String q, String roleCode, Pageable pageable);

  @Query(
      "select count(u) from User u, UserOrganization m "
          + "where m.userId = u.id and m.orgId = :orgId and m.active = true "
          + "and u.deletedAt is null "
          + "and (:q is null or lower(u.email) like lower(concat('%', :q, '%')) "
          + "     or lower(u.fullName) like lower(concat('%', :q, '%'))) "
          + "and (:roleCode is null or m.role.code = :roleCode)")
  long countByOrgFiltered(UUID orgId, String q, String roleCode);
}
