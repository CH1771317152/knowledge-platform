package com.platform.auth.infrastructure.verification;

import com.platform.auth.domain.VerificationChannel;

public interface VerificationSender {

    void send(VerificationChannel channel, String target, String code);
}
