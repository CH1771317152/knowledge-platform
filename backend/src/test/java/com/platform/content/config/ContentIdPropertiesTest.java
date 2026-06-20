package com.platform.content.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ContentIdPropertiesTest {

    @MockBean
    private UserRepository userRepository;

    @Autowired
    private ContentIdProperties contentIdProperties;

    @Test
    void bindsDefaultContentIdProperties() {
        assertThat(contentIdProperties.workerId()).isEqualTo(1);
        assertThat(contentIdProperties.datacenterId()).isEqualTo(1);
    }
}
