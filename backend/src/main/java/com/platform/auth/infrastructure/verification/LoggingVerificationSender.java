package com.platform.auth.infrastructure.verification;

import com.platform.auth.config.AuthProperties;
import com.platform.auth.domain.VerificationChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Default verification sender that simply logs the code (or a masked confirmation).
 *
 * <p>It is active only when {@code platform.auth.sender.mode=logging} (the application default)
 * and not under the {@code test} profile. When {@code expose-code-in-logs} is true the raw code
 * is logged; otherwise a masked "verification code sent" message is emitted without the code.
 */
@Component
@Profile("!test")
@ConditionalOnProperty(prefix = "platform.auth.sender", name = "mode", havingValue = "logging", matchIfMissing = true)
public class LoggingVerificationSender implements VerificationSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingVerificationSender.class);

    private final AuthProperties authProperties;

    public LoggingVerificationSender(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Override
    public void send(VerificationChannel channel, String target, String code) {
        if (authProperties.sender().exposeCodeInLogs()) {
            log.info("Verification code for {} via {}: {}", target, channel, code);
        } else {
            log.info("Verification code sent to {} via {}", target, channel);
        }
    }
}
