package com.platform.storage.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class StoragePropertiesTest {

    @MockBean
    private UserRepository userRepository;

    @Autowired
    private StorageProperties storageProperties;

    @Test
    void bindsDefaultStorageProperties() {
        assertThat(storageProperties.endpoint()).isEqualTo("https://oss-cn-hangzhou.aliyuncs.com");
        assertThat(storageProperties.region()).isEqualTo("cn-hangzhou");
        assertThat(storageProperties.bucket()).isEqualTo("knowledge-platform-dev-2026");
        assertThat(storageProperties.presignExpireMinutes()).isEqualTo(10);
    }
}
