package com.platform.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.auth.config.AuthProperties;
import com.platform.auth.domain.VerificationChannel;
import com.platform.auth.domain.VerificationCheckResult;
import com.platform.auth.domain.VerificationPurpose;
import com.platform.auth.domain.VerificationSendResult;
import com.platform.auth.infrastructure.verification.VerificationCodeGenerator;
import com.platform.auth.infrastructure.verification.VerificationSender;
import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Pure unit test for {@link VerificationService}. Uses fake store + fake sender; no Spring context,
 * no Redis, no audit repo bean. Proves the service is testable under the {@code test} profile where
 * those beans are absent.
 */
class VerificationServiceTest {

    private static final AuthProperties AUTH_PROPERTIES = new AuthProperties(
            new AuthProperties.Jwt("issuer", "secret", 900L),
            new AuthProperties.RefreshToken(1209600L, 48),
            new AuthProperties.Verification(300L, 60L, 5, 5, 6),
            new AuthProperties.Sender("logging", false)
    );

    private FakeStore store;
    private FakeSender sender;
    private VerificationService service;

    @BeforeEach
    void setUp() {
        store = new FakeStore();
        sender = new FakeSender();
        service = new VerificationService(
                store,
                new VerificationCodeGenerator(),
                objectProvider(sender),
                objectProvider(null),
                AUTH_PROPERTIES
        );
    }

    @Test
    void sendsEmailVerificationCode() {
        store.nextSendResult = VerificationSendResult.SENT;

        service.sendCode(VerificationPurpose.REGISTER, VerificationChannel.EMAIL, "user@example.com");

        assertThat(store.recordedStoreHashes).hasSize(1);
        assertThat(sender.sent).hasSize(1);
        assertThat(sender.sent.get(0).target()).isEqualTo("user@example.com");
        assertThat(sender.sent.get(0).code()).hasSize(6);
    }

    @Test
    void rejectsRateLimitedSend() {
        store.nextSendResult = VerificationSendResult.RESEND_LIMITED;

        assertThatThrownBy(() ->
                service.sendCode(VerificationPurpose.REGISTER, VerificationChannel.EMAIL, "user@example.com"))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.AUTH_VERIFICATION_RATE_LIMITED);
        assertThat(sender.sent).isEmpty();
    }

    @Test
    void consumesMatchingVerificationCode() {
        store.nextCheckResult = VerificationCheckResult.MATCHED;

        service.verifyCode(VerificationPurpose.LOGIN, VerificationChannel.EMAIL, "user@example.com", "123456");

        // The store received a hash; no exception thrown for a MATCHED result.
        assertThat(store.recordedVerifyHashes).hasSize(1);
    }

    @Test
    void rejectsWrongVerificationCode() {
        store.nextCheckResult = VerificationCheckResult.MISMATCHED;

        assertThatThrownBy(() ->
                service.verifyCode(VerificationPurpose.LOGIN, VerificationChannel.EMAIL, "user@example.com", "000000"))
                .isInstanceOf(PlatformException.class)
                .matches(ex -> ((PlatformException) ex).errorCode() == ErrorCode.AUTH_INVALID_VERIFICATION_CODE);
    }

    @Test
    void normalizesEmailTarget() {
        store.nextSendResult = VerificationSendResult.SENT;

        service.sendCode(VerificationPurpose.REGISTER, VerificationChannel.EMAIL, "  User@Example.COM  ");

        CapturedSend captured = store.capturedStoreCalls.get(0);
        // The store received a lowercased + trimmed target.
        assertThat(captured.target()).isEqualTo("user@example.com");

        // Prove normalization flows into the hash: recompute SHA-256 of the normalized payload using
        // the code that was actually sent to the sender, and assert the store got exactly that hash.
        String codeSent = sender.sent.get(0).code();
        String recomputed = VerificationService.hashCode(
                captured.purpose(), captured.channel(), "user@example.com", codeSent);
        assertThat(captured.codeHash()).isEqualTo(recomputed);

        // Sanity: the hash for the un-normalized target must differ, proving normalization mattered.
        String unnormalizedHash = VerificationService.hashCode(
                captured.purpose(), captured.channel(), "  User@Example.COM  ", codeSent);
        assertThat(captured.codeHash()).isNotEqualTo(unnormalizedHash);
    }

    @Test
    void verifyCodeNormalizesTarget() {
        store.nextSendResult = VerificationSendResult.SENT;

        // Send to a normalized target; the store captures the produced hash.
        service.sendCode(VerificationPurpose.REGISTER, VerificationChannel.EMAIL, "alice@example.com");
        String storedHash = store.recordedStoreHashes.get(0);
        String codeSent = sender.sent.get(0).code();

        // Verify with a mixed-case + whitespace-padded target that normalizes to the same value.
        store.nextCheckResult = VerificationCheckResult.MATCHED;
        service.verifyCode(VerificationPurpose.REGISTER, VerificationChannel.EMAIL, "  Alice@EXAMPLE.com  ", codeSent);

        // No exception thrown -> the service accepted the mixed-case target.
        assertThat(store.recordedVerifyHashes).hasSize(1);
        // The hash derived from the mixed-case verify target matches the hash from the normalized
        // send target, proving normalizeTarget runs on the VERIFY path too.
        assertThat(store.recordedVerifyHashes.get(0)).isEqualTo(storedHash);

        // Sanity: the verify call received the normalized target, not the raw input.
        CapturedVerify verifyCall = store.capturedVerifyCalls.get(0);
        assertThat(verifyCall.target()).isEqualTo("alice@example.com");
    }

    // --- helpers -----------------------------------------------------------------

    private static <T> ObjectProvider<T> objectProvider(T bean) {
        return new SingletonObjectProvider<>(bean);
    }

    private record CapturedSend(VerificationPurpose purpose, VerificationChannel channel, String target,
                                String codeHash) {}

    private record CapturedVerify(VerificationPurpose purpose, VerificationChannel channel, String target,
                                  String codeHash) {}

    private static final class FakeStore implements VerificationStore {
        private VerificationSendResult nextSendResult = VerificationSendResult.SENT;
        private VerificationCheckResult nextCheckResult = VerificationCheckResult.MATCHED;
        private final List<CapturedSend> capturedStoreCalls = new ArrayList<>();
        private final List<String> recordedStoreHashes = new ArrayList<>();
        private final List<CapturedVerify> capturedVerifyCalls = new ArrayList<>();
        private final List<String> recordedVerifyHashes = new ArrayList<>();

        @Override
        public VerificationSendResult storeCode(VerificationPurpose purpose, VerificationChannel channel,
                                                String target, String codeHash) {
            capturedStoreCalls.add(new CapturedSend(purpose, channel, target, codeHash));
            recordedStoreHashes.add(codeHash);
            return nextSendResult;
        }

        @Override
        public VerificationCheckResult verifyAndConsume(VerificationPurpose purpose, VerificationChannel channel,
                                                       String target, String codeHash) {
            capturedVerifyCalls.add(new CapturedVerify(purpose, channel, target, codeHash));
            recordedVerifyHashes.add(codeHash);
            return nextCheckResult;
        }
    }

    private record SentCode(VerificationChannel channel, String target, String code) {}

    private static final class FakeSender implements VerificationSender {
        private final List<SentCode> sent = new ArrayList<>();

        @Override
        public void send(VerificationChannel channel, String target, String code) {
            sent.add(new SentCode(channel, target, code));
        }
    }

    private static final class SingletonObjectProvider<T> implements ObjectProvider<T> {
        private final T bean;

        SingletonObjectProvider(T bean) {
            this.bean = bean;
        }

        @Override
        public T getObject() throws org.springframework.beans.BeansException {
            return bean;
        }

        @Override
        public T getObject(Object... args) {
            return bean;
        }

        @Override
        public T getIfAvailable() {
            return bean;
        }

        @Override
        public T getIfUnique() {
            return bean;
        }
    }
}
