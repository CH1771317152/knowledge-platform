package com.platform.auth.repository;

import com.platform.auth.domain.VerificationAuditEvent;
import com.platform.auth.domain.VerificationChannel;
import com.platform.auth.domain.VerificationPurpose;

public interface AuthVerificationAuditRepository {

    void record(VerificationChannel channel,
                String target,
                VerificationPurpose purpose,
                String status,
                String requestIp,
                String failureReason);

    default void record(VerificationAuditEvent event) {
        record(event.channel(), event.target(), event.purpose(),
                event.status(), event.requestIp(), event.failureReason());
    }
}
