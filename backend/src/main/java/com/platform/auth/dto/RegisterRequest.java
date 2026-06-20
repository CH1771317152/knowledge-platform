package com.platform.auth.dto;

import com.platform.auth.domain.VerificationChannel;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank String username,
        @Email @NotBlank String email,
        String phone,
        @NotBlank @Size(min = 8, max = 72) String password,
        @NotNull VerificationChannel verificationChannel,
        @NotBlank String verificationTarget,
        @NotBlank String verificationCode) {
}
