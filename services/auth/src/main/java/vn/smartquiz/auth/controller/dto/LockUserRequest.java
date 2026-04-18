package vn.smartquiz.auth.controller.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Positive;

/** {@code durationMinutes=null} → lock vĩnh viễn (admin unlock thủ công). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LockUserRequest(@Positive Long durationMinutes) {}
