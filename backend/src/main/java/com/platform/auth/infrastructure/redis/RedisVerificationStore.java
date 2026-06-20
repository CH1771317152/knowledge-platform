package com.platform.auth.infrastructure.redis;

import com.platform.auth.application.VerificationStore;
import com.platform.auth.config.AuthProperties;
import com.platform.auth.domain.VerificationChannel;
import com.platform.auth.domain.VerificationCheckResult;
import com.platform.auth.domain.VerificationPurpose;
import com.platform.auth.domain.VerificationSendResult;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import org.springframework.util.StreamUtils;

@Repository
@Profile("!test")
public class RedisVerificationStore implements VerificationStore {

    private static final String SEND_SCRIPT_LOCATION = "redis/auth-verification-send.lua";
    private static final String VERIFY_SCRIPT_LOCATION = "redis/auth-verification-verify.lua";

    private static final long HOURLY_TTL_SECONDS = 3600L;
    private static final long VERIFY_RATE_TTL_SECONDS = 3600L;

    private final StringRedisTemplate redisTemplate;
    private final AuthProperties authProperties;

    private final DefaultRedisScript<String> sendScript = new DefaultRedisScript<>();
    private final DefaultRedisScript<String> verifyScript = new DefaultRedisScript<>();

    public RedisVerificationStore(StringRedisTemplate redisTemplate, AuthProperties authProperties) {
        this.redisTemplate = redisTemplate;
        this.authProperties = authProperties;
    }

    @PostConstruct
    void initScripts() {
        sendScript.setResultType(String.class);
        sendScript.setScriptText(loadScript(SEND_SCRIPT_LOCATION));
        verifyScript.setResultType(String.class);
        verifyScript.setScriptText(loadScript(VERIFY_SCRIPT_LOCATION));
    }

    private static String loadScript(String location) {
        try {
            return StreamUtils.copyToString(new ClassPathResource(location).getInputStream(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load redis script: " + location, e);
        }
    }

    @Override
    public VerificationSendResult storeCode(VerificationPurpose purpose,
                                            VerificationChannel channel,
                                            String target,
                                            String codeHash) {
        List<String> keys = List.of(
                codeKey(purpose, channel, target),
                resendKey(purpose, channel, target),
                hourlyKey(purpose, channel, target)
        );
        AuthProperties.Verification v = authProperties.verification();
        Object[] args = new Object[] {
                codeHash,
                Long.toString(v.codeTtlSeconds()),
                Long.toString(v.resendIntervalSeconds()),
                Long.toString(HOURLY_TTL_SECONDS),
                Integer.toString(v.hourlySendLimit())
        };
        String result = redisTemplate.execute(sendScript, keys, args);
        return VerificationSendResult.valueOf(normalize(result));
    }

    @Override
    public VerificationCheckResult verifyAndConsume(VerificationPurpose purpose,
                                                    VerificationChannel channel,
                                                    String target,
                                                    String codeHash) {
        List<String> keys = List.of(
                codeKey(purpose, channel, target),
                verifyRateKey(purpose, channel, target)
        );
        AuthProperties.Verification v = authProperties.verification();
        Object[] args = new Object[] {
                codeHash,
                Integer.toString(v.maxFailedAttempts()),
                Long.toString(VERIFY_RATE_TTL_SECONDS)
        };
        String result = redisTemplate.execute(verifyScript, keys, args);
        return VerificationCheckResult.valueOf(normalize(result));
    }

    private static String normalize(String result) {
        return result == null ? "" : result.trim();
    }

    private String codeKey(VerificationPurpose purpose, VerificationChannel channel, String target) {
        return "auth:verification:code:%s:%s:%s".formatted(purpose, channel, target);
    }

    private String resendKey(VerificationPurpose purpose, VerificationChannel channel, String target) {
        return "auth:verification:send-rate:%s:%s:%s:resend".formatted(purpose, channel, target);
    }

    private String hourlyKey(VerificationPurpose purpose, VerificationChannel channel, String target) {
        return "auth:verification:send-rate:%s:%s:%s:hourly".formatted(purpose, channel, target);
    }

    private String verifyRateKey(VerificationPurpose purpose, VerificationChannel channel, String target) {
        return "auth:verification:verify-rate:%s:%s:%s".formatted(purpose, channel, target);
    }
}
