package vn.smartquiz.auth.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code roleCode} phải là role assignable trong org đích (system role hoặc custom role của org). */
public record ChangeRoleRequest(@NotBlank @Size(max = 50) String roleCode) {}
