package com.platform.auth.dto;

import com.platform.auth.domain.VerificationChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record VerificationCodeLoginRequest(
        @NotNull VerificationChannel channel,
        @NotBlank String target,
        @NotBlank String verificationCode) {
}
