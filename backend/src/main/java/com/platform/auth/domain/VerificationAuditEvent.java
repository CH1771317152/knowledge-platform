package com.platform.auth.domain;

/**
 * An audit record describing a single verification lifecycle event (send, consume, failure).
 */
public record VerificationAuditEvent(
        Long id,
        VerificationChannel channel,
        String target,
        VerificationPurpose purpose,
        String status,
        String requestIp,
        String failureReason
) {
}
