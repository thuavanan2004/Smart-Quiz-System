package vn.smartquiz.auth.domain.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.Objects;
import java.util.UUID;

/** Catalog entry của RBAC. `code` (vd "exam.update.own") dùng làm authority trong JWT. */
@Entity
@Table(name = "permissions")
public class Permission {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "code", nullable = false, unique = true)
  private String code;

  @Column(name = "resource", nullable = false)
  private String resource;

  @Column(name = "action", nullable = false)
  private String action;

  @Column(name = "scope")
  private String scope;

  protected Permission() {}

  public UUID getId() {
    return id;
  }

  public String getCode() {
    return code;
  }

  public String getResource() {
    return resource;
  }

  public String getAction() {
    return action;
  }

  public String getScope() {
    return scope;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Permission other)) {
      return false;
    }
    return Objects.equals(id, other.id);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id);
  }
}
