package com.platform.auth.application;

import com.platform.auth.config.AuthProperties;
import com.platform.auth.domain.VerificationChannel;
import com.platform.auth.domain.VerificationCheckResult;
import com.platform.auth.domain.VerificationPurpose;
import com.platform.auth.domain.VerificationSendResult;
import com.platform.auth.infrastructure.verification.VerificationCodeGenerator;
import com.platform.auth.infrastructure.verification.VerificationSender;
import com.platform.auth.repository.AuthVerificationAuditRepository;
import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class VerificationService {

    private static final Logger log = LoggerFactory.getLogger(VerificationService.class);

    private final VerificationStore store;
    private final VerificationCodeGenerator codeGenerator;
    private final ObjectProvider<VerificationSender> senderProvider;
    private final ObjectProvider<AuthVerificationAuditRepository> auditRepositoryProvider;
    private final AuthProperties authProperties;

    public VerificationService(VerificationStore store,
                               VerificationCodeGenerator codeGenerator,
                               ObjectProvider<VerificationSender> senderProvider,
                               ObjectProvider<AuthVerificationAuditRepository> auditRepositoryProvider,
                               AuthProperties authProperties) {
        this.store = store;
        this.codeGenerator = codeGenerator;
        this.senderProvider = senderProvider;
        this.auditRepositoryProvider = auditRepositoryProvider;
        this.authProperties = authProperties;
    }

    public void sendCode(VerificationPurpose purpose, VerificationChannel channel, String target) {
        String normalizedTarget = normalizeTarget(channel, target);
        int codeLength = authProperties.verification().codeLength();
        String code = codeGenerator.generate(codeLength);
        String codeHash = hashCode(purpose, channel, normalizedTarget, code);

        VerificationSendResult result = store.storeCode(purpose, channel, normalizedTarget, codeHash);
        if (result != VerificationSendResult.SENT) {
            String message = switch (result) {
                case RESEND_LIMITED -> "Verification code already sent; please wait before requesting another "
                        + "(resend interval " + authProperties.verification().resendIntervalSeconds() + "s)";
                case HOURLY_LIMITED -> "Too many verification codes requested; please try again later";
                default -> "Verification code request rejected";
            };
            auditQuietly(channel, normalizedTarget, purpose, "RATE_LIMITED", null,
                    "send rejected: " + result);
            throw new PlatformException(ErrorCode.AUTH_VERIFICATION_RATE_LIMITED, message);
        }

        VerificationSender sender = senderProvider.getIfAvailable();
        if (sender != null) {
            try {
                sender.send(channel, normalizedTarget, code);
                auditQuietly(channel, normalizedTarget, purpose, "SENT", null, null);
            } catch (RuntimeException ex) {
                log.warn("Verification sender failed for {} via {}: {}",
                        normalizedTarget, channel, ex.toString());
                auditQuietly(channel, normalizedTarget, purpose, "FAILED", null,
                        "sender error: " + ex.getMessage());
            }
        }
    }

    public void verifyCode(VerificationPurpose purpose, VerificationChannel channel, String target, String code) {
        String normalizedTarget = normalizeTarget(channel, target);
        String codeHash = hashCode(purpose, channel, normalizedTarget, code);

        VerificationCheckResult result = store.verifyAndConsume(purpose, channel, normalizedTarget, codeHash);
        if (result != VerificationCheckResult.MATCHED) {
            // All non-success outcomes map to the same error code to avoid leaking whether the code
            // existed, was wrong, or was locked out.
            auditQuietly(channel, normalizedTarget, purpose, "FAILED", null,
                    "verify rejected: " + result);
            throw new PlatformException(ErrorCode.AUTH_INVALID_VERIFICATION_CODE,
                    "Invalid or expired verification code");
        }
        auditQuietly(channel, normalizedTarget, purpose, "CONSUMED", null, null);
    }

    private String normalizeTarget(VerificationChannel channel, String target) {
        if (target == null) {
            return null;
        }
        String trimmed = target.trim();
        return channel == VerificationChannel.EMAIL ? trimmed.toLowerCase() : trimmed;
    }

    static String hashCode(VerificationPurpose purpose, VerificationChannel channel, String target, String code) {
        String payload = purpose + ":" + channel + ":" + target + ":" + code;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private void auditQuietly(VerificationChannel channel,
                              String target,
                              VerificationPurpose purpose,
                              String status,
                              String requestIp,
                              String failureReason) {
        AuthVerificationAuditRepository auditRepository = auditRepositoryProvider.getIfAvailable();
        if (auditRepository == null) {
            return;
        }
        try {
            auditRepository.record(channel, target, purpose, status, requestIp, failureReason);
        } catch (RuntimeException ex) {
            log.debug("Verification audit write failed (ignored): {}", ex.toString());
        }
    }
}
