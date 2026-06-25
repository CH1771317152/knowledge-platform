package com.platform.storage.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import com.platform.storage.config.StorageProperties;
import com.platform.storage.domain.PresignedUpload;
import com.platform.storage.dto.PresignRequest;
import com.platform.storage.infrastructure.FakeObjectStorageService;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class StoragePresignServiceTest {

    private final FakeObjectStorageService objectStorageService = new FakeObjectStorageService();
    private final StoragePresignService service = new StoragePresignService(
            new StorageProperties("aliyun-oss", "https://oss.example.com", "cn-hangzhou",
                    "knowledge-platform-test", "access-key", "access-secret", 10),
            objectStorageService);

    @Test
    void presignsAllowedUserObjectKey() {
        PresignRequest request = new PresignRequest("users/42/avatar.png", "image/png", 10);

        PresignedUpload upload = service.presignForUser(42L, request);

        assertThat(upload.objectKey()).isEqualTo("users/42/avatar.png");
        assertThat(objectStorageService.lastContentType()).isEqualTo("image/png");
        assertThat(objectStorageService.lastExpires()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    void rejectsObjectKeyForAnotherUser() {
        PresignRequest request = new PresignRequest("users/99/avatar.png", "image/png", 10);

        assertForbidden(request);
    }

    @Test
    void rejectsObjectKeyWithParentTraversal() {
        PresignRequest request = new PresignRequest("users/42/../avatar.png", "image/png", 10);

        assertForbidden(request);
    }

    @Test
    void rejectsObjectKeyStartingWithSlash() {
        PresignRequest request = new PresignRequest("/users/42/avatar.png", "image/png", 10);

        assertForbidden(request);
    }

    @Test
    void rejectsUnsupportedContentType() {
        PresignRequest request = new PresignRequest("users/42/avatar.svg", "image/svg+xml", 10);

        assertForbidden(request);
    }

    @Test
    void capsExpirationAtConfiguredMaximum() {
        StoragePresignService cappedService = new StoragePresignService(
                new StorageProperties("aliyun-oss", "https://oss.example.com", "cn-hangzhou",
                        "knowledge-platform-test", "access-key", "access-secret", 5),
                objectStorageService);
        PresignRequest request = new PresignRequest("users/42/archive.zip", "application/zip", 10);

        cappedService.presignForUser(42L, request);

        assertThat(objectStorageService.lastExpires()).isEqualTo(Duration.ofMinutes(5));
    }

    private void assertForbidden(PresignRequest request) {
        assertThatThrownBy(() -> service.presignForUser(42L, request))
                .isInstanceOfSatisfying(PlatformException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.STORAGE_PRESIGN_FORBIDDEN));
    }

}
