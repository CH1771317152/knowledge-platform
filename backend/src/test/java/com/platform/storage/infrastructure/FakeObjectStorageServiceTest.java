package com.platform.storage.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import com.platform.storage.domain.PresignedUpload;
import com.platform.storage.domain.StoredObjectMetadata;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class FakeObjectStorageServiceTest {

    private final FakeObjectStorageService storageService = new FakeObjectStorageService();

    @Test
    void storesPresignsStatsAndReadsObjects() throws Exception {
        byte[] content = "hello fake".getBytes(StandardCharsets.UTF_8);
        storageService.putObject("users/1/files/content.txt", "text/plain", content, "\"etag-1\"");

        PresignedUpload upload = storageService.presignPut(
                "users/1/files/content.txt", "text/plain", Duration.ofMinutes(5));
        StoredObjectMetadata metadata = storageService.statObject("users/1/files/content.txt");
        String readContent = new String(
                storageService.readObject("users/1/files/content.txt").readAllBytes(),
                StandardCharsets.UTF_8);

        assertThat(upload.bucket()).isEqualTo("fake-bucket");
        assertThat(upload.putUrl()).isEqualTo("https://fake-oss.local/users/1/files/content.txt");
        assertThat(upload.headers()).containsEntry("Content-Type", "text/plain");
        assertThat(metadata.bucket()).isEqualTo("fake-bucket");
        assertThat(metadata.etag()).isEqualTo("\"etag-1\"");
        assertThat(metadata.sizeBytes()).isEqualTo(content.length);
        assertThat(metadata.contentType()).isEqualTo("text/plain");
        assertThat(readContent).isEqualTo("hello fake");
    }

    @Test
    void throwsStorageNotFoundForMissingObjects() {
        assertThatThrownBy(() -> storageService.statObject("missing.txt"))
                .isInstanceOfSatisfying(PlatformException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.STORAGE_OBJECT_NOT_FOUND));
        assertThatThrownBy(() -> storageService.readObject("missing.txt"))
                .isInstanceOfSatisfying(PlatformException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.STORAGE_OBJECT_NOT_FOUND));
    }
}
