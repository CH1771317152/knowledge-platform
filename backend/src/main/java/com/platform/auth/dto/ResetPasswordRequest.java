package com.platform.auth.dto;

import com.platform.auth.domain.VerificationChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResetPasswordRequest(
        @NotNull VerificationChannel channel,
        @NotBlank String target,
        @NotBlank String verificationCode,
        @NotBlank @Size(min = 8, max = 72) String newPassword) {
}
