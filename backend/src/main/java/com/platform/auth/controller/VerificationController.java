package com.platform.auth.controller;

import com.platform.auth.application.VerificationService;
import com.platform.auth.domain.VerificationChannel;
import com.platform.auth.domain.VerificationPurpose;
import com.platform.auth.dto.SendVerificationCodeRequest;
import com.platform.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sends verification codes for registration, login, and password reset. Under {@code /api/auth/**}
 * so it is reachable without a bearer token. The audit IP is optional (Task 4); we pass null here.
 */
@RestController
@RequestMapping("/api/auth/verification-codes")
@Profile("!test")
public class VerificationController {

    private final VerificationService verificationService;

    public VerificationController(VerificationService verificationService) {
        this.verificationService = verificationService;
    }

    @PostMapping
    public ApiResponse<Void> send(@Valid @RequestBody SendVerificationCodeRequest request) {
        VerificationChannel channel = request.channel();
        VerificationPurpose purpose = request.purpose();
        verificationService.sendCode(purpose, channel, request.target());
        return ApiResponse.ok(null);
    }
}
