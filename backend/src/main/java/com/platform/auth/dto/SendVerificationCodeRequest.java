package com.platform.auth.dto;

import com.platform.auth.domain.VerificationChannel;
import com.platform.auth.domain.VerificationPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SendVerificationCodeRequest(
        @NotNull VerificationChannel channel,
        @NotBlank String target,
        @NotNull VerificationPurpose purpose) {
}
