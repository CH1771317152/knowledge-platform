package com.platform.auth.infrastructure.verification;

import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Generates a cryptographically random numeric verification code of the given length,
 * zero-padded so the output is always exactly {@code length} digits.
 */
@Component
public class VerificationCodeGenerator {

    private final SecureRandom random = new SecureRandom();

    public String generate(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("length must be positive");
        }
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(random.nextInt(10));
        }
        return builder.toString();
    }
}
