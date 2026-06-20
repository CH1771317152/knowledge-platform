package com.platform.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record PasswordLoginRequest(@NotBlank String principal, @NotBlank String password) {
}
