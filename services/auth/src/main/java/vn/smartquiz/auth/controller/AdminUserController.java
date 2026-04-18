package vn.smartquiz.auth.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.smartquiz.auth.application.AdminChangeRoleUseCase;
import vn.smartquiz.auth.application.AdminDeleteUserUseCase;
import vn.smartquiz.auth.application.AdminLockUserUseCase;
import vn.smartquiz.auth.application.AdminUnlockUserUseCase;
import vn.smartquiz.auth.application.AdminUserQuery;
import vn.smartquiz.auth.common.AuthException;
import vn.smartquiz.auth.common.ErrorCode;
import vn.smartquiz.auth.controller.dto.ChangeRoleRequest;
import vn.smartquiz.auth.controller.dto.LockUserRequest;

/**
 * Admin endpoints org-scoped (design §12.3). Tất cả op đều cần permission tương ứng trong JWT
 * authorities + target phải là member của org active của caller (JWT {@code org_id}).
 */
@RestController
@RequestMapping("/admin/users")
public class AdminUserController {

  private final AdminUserQuery adminUserQuery;
  private final AdminChangeRoleUseCase changeRoleUseCase;
  private final AdminLockUserUseCase lockUseCase;
  private final AdminUnlockUserUseCase unlockUseCase;
  private final AdminDeleteUserUseCase deleteUseCase;

  public AdminUserController(
      AdminUserQuery adminUserQuery,
      AdminChangeRoleUseCase changeRoleUseCase,
      AdminLockUserUseCase lockUseCase,
      AdminUnlockUserUseCase unlockUseCase,
      AdminDeleteUserUseCase deleteUseCase) {
    this.adminUserQuery = adminUserQuery;
    this.changeRoleUseCase = changeRoleUseCase;
    this.lockUseCase = lockUseCase;
    this.unlockUseCase = unlockUseCase;
    this.deleteUseCase = deleteUseCase;
  }

  @GetMapping
  @PreAuthorize("hasAuthority('user.read.org')")
  public ResponseEntity<AdminUserQuery.Page> list(
      @AuthenticationPrincipal Jwt jwt,
      @RequestParam(required = false) String q,
      @RequestParam(required = false) String role,
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size) {
    return ResponseEntity.ok(adminUserQuery.execute(activeOrg(jwt), q, role, page, size));
  }

  @GetMapping("/{id}")
  @PreAuthorize("hasAuthority('user.read.org')")
  public ResponseEntity<AdminUserQuery.Item> get(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    return ResponseEntity.ok(adminUserQuery.getInOrg(activeOrg(jwt), id));
  }

  @PatchMapping("/{id}/role")
  @PreAuthorize("hasAuthority('user.update.role')")
  public ResponseEntity<Void> changeRole(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody ChangeRoleRequest req) {
    changeRoleUseCase.execute(
        new AdminChangeRoleUseCase.Command(actor(jwt), activeOrg(jwt), id, req.roleCode()));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/lock")
  @PreAuthorize("hasAuthority('user.lock')")
  public ResponseEntity<Void> lock(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) LockUserRequest req) {
    Long minutes = req == null ? null : req.durationMinutes();
    lockUseCase.execute(
        new AdminLockUserUseCase.Command(actor(jwt), activeOrg(jwt), id, minutes));
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/{id}/unlock")
  @PreAuthorize("hasAuthority('user.lock')")
  public ResponseEntity<Void> unlock(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    unlockUseCase.execute(new AdminUnlockUserUseCase.Command(actor(jwt), activeOrg(jwt), id));
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasAuthority('user.delete')")
  public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
    if (id.equals(actor(jwt))) {
      // Không cho phép admin tự xoá mình qua API — tránh lock-out không thể khôi phục.
      throw new AuthException(ErrorCode.AUTH_FORBIDDEN, "Admin không thể tự xoá chính mình");
    }
    deleteUseCase.execute(new AdminDeleteUserUseCase.Command(actor(jwt), activeOrg(jwt), id));
    return ResponseEntity.noContent().build();
  }

  private static UUID activeOrg(Jwt jwt) {
    String orgId = jwt.getClaimAsString("org_id");
    if (orgId == null || orgId.isBlank()) {
      throw new AuthException(ErrorCode.AUTH_FORBIDDEN, "Không có org active");
    }
    return UUID.fromString(orgId);
  }

  private static UUID actor(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }
}
