package com.platform.auth.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Provides the BCrypt password encoder used by {@code AuthService} to hash passwords at registration
 * and verify them at login. Not {@code @Profile}-gated: the encoder has no DataSource/Redis
 * dependency, so it loads cleanly under both the {@code test} and {@code integration} profiles.
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
