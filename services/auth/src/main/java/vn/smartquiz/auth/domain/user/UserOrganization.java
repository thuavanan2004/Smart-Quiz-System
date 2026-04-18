package vn.smartquiz.auth.domain.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Membership của user trong 1 org. Composite PK (user_id, org_id). */
@Entity
@Table(name = "user_organizations")
@IdClass(UserOrganization.Key.class)
public class UserOrganization {

  @Id
  @Column(name = "user_id")
  private UUID userId;

  @Id
  @Column(name = "org_id")
  private UUID orgId;

  @ManyToOne(fetch = FetchType.EAGER)
  @JoinColumn(name = "role_id", nullable = false)
  private Role role;

  @Column(name = "joined_at", nullable = false)
  private Instant joinedAt;

  @Column(name = "is_active", nullable = false)
  private boolean active;

  protected UserOrganization() {}

  public UUID getUserId() {
    return userId;
  }

  public UUID getOrgId() {
    return orgId;
  }

  public Role getRole() {
    return role;
  }

  public Instant getJoinedAt() {
    return joinedAt;
  }

  public boolean isActive() {
    return active;
  }

  public static final class Key implements Serializable {
    private UUID userId;
    private UUID orgId;

    public Key() {}

    public Key(UUID userId, UUID orgId) {
      this.userId = userId;
      this.orgId = orgId;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof Key key)) {
        return false;
      }
      return Objects.equals(userId, key.userId) && Objects.equals(orgId, key.orgId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(userId, orgId);
    }
  }
}
