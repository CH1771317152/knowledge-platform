package com.platform.auth.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AuthPropertiesTest {

    @MockBean
    private UserRepository userRepository;

    @Autowired
    private AuthProperties authProperties;

    @Test
    void bindsDefaultAuthProperties() {
        assertThat(authProperties.jwt().issuer()).isEqualTo("knowledge-platform");
        assertThat(authProperties.jwt().accessTokenTtlSeconds()).isEqualTo(900);
        assertThat(authProperties.refreshToken().ttlSeconds()).isEqualTo(1209600);
        assertThat(authProperties.verification().codeLength()).isEqualTo(6);
    }
}
