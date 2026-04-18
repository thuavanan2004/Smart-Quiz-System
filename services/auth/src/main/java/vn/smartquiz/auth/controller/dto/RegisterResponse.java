package vn.smartquiz.auth.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record RegisterResponse(
    UUID userId, boolean emailVerificationSent, String verificationTokenDev) {}
