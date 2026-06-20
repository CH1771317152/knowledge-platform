package com.platform.storage.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.platform.storage.application.ObjectStorageService;
import com.platform.storage.domain.PresignedUpload;
import com.platform.storage.domain.StoredObjectMetadata;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Opt-in real OSS smoke test. Set RUN_ALIYUN_OSS_SMOKE_TEST=true plus OSS credentials to run it.
 */
@EnabledIfEnvironmentVariable(named = "RUN_ALIYUN_OSS_SMOKE_TEST", matches = "true")
@EnabledIfEnvironmentVariable(named = "OSS_ACCESS_KEY_ID", matches = ".+")
@EnabledIfEnvironmentVariable(named = "OSS_ACCESS_KEY_SECRET", matches = ".+")
@SpringBootTest
@ActiveProfiles({"integration", "aliyun-oss-smoke"})
class AliyunOssObjectStorageSmokeTest {

    @Autowired
    private ObjectStorageService objectStorageService;

    @Test
    void presignStatAndReadRoundTrip() throws Exception {
        String objectKey = "users/1/files/smoke/content-" + UUID.randomUUID() + ".md";
        String contentType = "text/plain";
        byte[] content = "hello oss".getBytes(StandardCharsets.UTF_8);

        PresignedUpload upload = objectStorageService.presignPut(objectKey, contentType, Duration.ofMinutes(5));
        HttpURLConnection connection = (HttpURLConnection) new URL(upload.putUrl()).openConnection();
        connection.setRequestMethod("PUT");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", contentType);
        connection.setFixedLengthStreamingMode(content.length);
        try (OutputStream outputStream = connection.getOutputStream()) {
            outputStream.write(content);
        }
        assertThat(connection.getResponseCode()).isBetween(200, 299);
        connection.disconnect();

        StoredObjectMetadata metadata = objectStorageService.statObject(objectKey);
        String readContent;
        try (InputStream inputStream = objectStorageService.readObject(objectKey)) {
            readContent = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThat(metadata.sizeBytes()).isEqualTo(content.length);
        assertThat(metadata.contentType()).isEqualTo(contentType);
        assertThat(readContent).isEqualTo("hello oss");
    }
}
