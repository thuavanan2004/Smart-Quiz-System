package vn.smartquiz.auth.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Trả rỗng ở prod; dev-expose-reset-token=true thì kèm {@code reset_token_dev} cho test. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ForgotPasswordResponse(String resetTokenDev) {}
