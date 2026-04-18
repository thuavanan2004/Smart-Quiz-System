package vn.smartquiz.auth.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Switch-org yêu cầu cả {@code refresh_token} (để rotate session) và {@code org_id} đích. Client
 * dùng refresh token hiện tại (giống /auth/refresh) — server rotate + reissue với org mới.
 */
public record SwitchOrgRequest(@NotNull UUID orgId, @NotBlank String refreshToken) {}
