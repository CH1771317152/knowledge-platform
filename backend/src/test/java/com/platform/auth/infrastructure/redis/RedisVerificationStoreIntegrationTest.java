package com.platform.auth.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.auth.domain.VerificationChannel;
import com.platform.auth.domain.VerificationCheckResult;
import com.platform.auth.domain.VerificationPurpose;
import com.platform.auth.domain.VerificationSendResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Integration test for {@link RedisVerificationStore} against a real Redis (localhost:6379) under the
 * {@code integration} profile. Each test uses a unique target email so the Redis keys never collide
 * with other tests or with one another.
 */
@SpringBootTest
@ActiveProfiles("integration")
class RedisVerificationStoreIntegrationTest {

    @Autowired
    private RedisVerificationStore store;

    @Test
    void sendScriptLimitsImmediateResend() {
        String target = "redis-send-" + System.nanoTime() + "@example.com";

        VerificationSendResult first = store.storeCode(VerificationPurpose.REGISTER, VerificationChannel.EMAIL,
                target, "hash-1");
        VerificationSendResult second = store.storeCode(VerificationPurpose.REGISTER, VerificationChannel.EMAIL,
                target, "hash-2");

        assertThat(first).isEqualTo(VerificationSendResult.SENT);
        assertThat(second).isEqualTo(VerificationSendResult.RESEND_LIMITED);
    }

    @Test
    void verifyScriptConsumesMatchingCode() {
        String target = "redis-consume-" + System.nanoTime() + "@example.com";

        store.storeCode(VerificationPurpose.LOGIN, VerificationChannel.EMAIL, target, "hash-1");
        VerificationCheckResult first = store.verifyAndConsume(VerificationPurpose.LOGIN,
                VerificationChannel.EMAIL, target, "hash-1");
        VerificationCheckResult second = store.verifyAndConsume(VerificationPurpose.LOGIN,
                VerificationChannel.EMAIL, target, "hash-1");

        assertThat(first).isEqualTo(VerificationCheckResult.MATCHED);
        assertThat(second).isEqualTo(VerificationCheckResult.MISSING);
    }

    @Test
    void verifyScriptLocksAfterMaxFailures() {
        String target = "redis-lock-" + System.nanoTime() + "@example.com";

        store.storeCode(VerificationPurpose.RESET_PASSWORD, VerificationChannel.EMAIL, target, "hash-1");
        VerificationCheckResult last = VerificationCheckResult.MISMATCHED;
        // Default maxFailedAttempts = 5; the 5th wrong attempt transitions to LOCKED.
        for (int index = 0; index < 5; index++) {
            last = store.verifyAndConsume(VerificationPurpose.RESET_PASSWORD, VerificationChannel.EMAIL,
                    target, "wrong-hash");
        }

        assertThat(last).isEqualTo(VerificationCheckResult.LOCKED);
    }
}
