package vn.smartquiz.auth.domain.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Role (system hoặc custom theo org). Join với permissions qua role_permissions. */
@Entity
@Table(name = "roles")
public class Role {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "org_id")
  private UUID orgId;

  @Column(name = "code", nullable = false)
  private String code;

  @Column(name = "name", nullable = false)
  private String name;

  @Column(name = "is_system", nullable = false)
  private boolean system;

  @Column(name = "is_active", nullable = false)
  private boolean active;

  @ManyToMany(fetch = FetchType.EAGER)
  @JoinTable(
      name = "role_permissions",
      joinColumns = @JoinColumn(name = "role_id"),
      inverseJoinColumns = @JoinColumn(name = "permission_id"))
  private Set<Permission> permissions = new HashSet<>();

  protected Role() {}

  public UUID getId() {
    return id;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public String getCode() {
    return code;
  }

  public String getName() {
    return name;
  }

  public boolean isSystem() {
    return system;
  }

  public boolean isActive() {
    return active;
  }

  public Set<Permission> getPermissions() {
    return permissions;
  }
}
