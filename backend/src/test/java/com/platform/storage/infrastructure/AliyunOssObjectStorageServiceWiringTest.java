package com.platform.storage.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.storage.application.ObjectStorageService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Regression guard: verifies Spring can construct the {@link AliyunOssObjectStorageService}
 * bean under the {@code aliyun-oss-smoke} profile. This catches bean-wiring defects (such as
 * ambiguous constructors with no {@code @Autowired}) that the opt-in
 * {@link AliyunOssObjectStorageSmokeTest} only surfaces when real OSS credentials are present.
 *
 * <p>Dummy (non-empty) credentials are injected via {@code @SpringBootTest} properties so the
 * Aliyun {@code OSSClientBuilder} can construct the client without real credentials. This test
 * never calls OSS — it only asserts the bean instantiates and is the active
 * {@code ObjectStorageService} — so it runs in the normal integration suite without any OSS
 * environment variables.
 */
@SpringBootTest(properties = {
        "platform.storage.access-key-id=dummy-ci",
        "platform.storage.access-key-secret=dummy-ci"
})
@ActiveProfiles({"integration", "aliyun-oss-smoke"})
class AliyunOssObjectStorageServiceWiringTest {

    @Autowired
    private ObjectStorageService objectStorageService;

    @Test
    void aliyunOssBeanIsConstructedBySpring() {
        assertThat(objectStorageService).isInstanceOf(AliyunOssObjectStorageService.class);
    }
}
