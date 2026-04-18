package vn.smartquiz.auth.controller.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
    @NotBlank String oldPassword, @NotBlank @Size(min = 12, max = 128) String newPassword) {}
