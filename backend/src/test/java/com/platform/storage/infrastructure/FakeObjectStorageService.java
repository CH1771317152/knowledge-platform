package com.platform.storage.infrastructure;

import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import com.platform.storage.application.ObjectStorageService;
import com.platform.storage.domain.PresignedUpload;
import com.platform.storage.domain.StoredObjectMetadata;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile({"test", "integration & !aliyun-oss-smoke"})
public class FakeObjectStorageService implements ObjectStorageService {

    private static final String BUCKET = "fake-bucket";

    private final Map<String, Stored> objects = new ConcurrentHashMap<>();
    private String lastObjectKey;
    private String lastContentType;
    private Duration lastExpires;

    public void putObject(String objectKey, String contentType, byte[] content, String etag) {
        objects.put(objectKey, new Stored(Arrays.copyOf(content, content.length), contentType, etag));
    }

    @Override
    public PresignedUpload presignPut(String objectKey, String contentType, Duration expires) {
        this.lastObjectKey = objectKey;
        this.lastContentType = contentType;
        this.lastExpires = expires;
        return new PresignedUpload(BUCKET, objectKey, "https://fake-oss.local/" + objectKey,
                Map.of("Content-Type", contentType), LocalDateTime.now().plus(expires));
    }

    public String lastObjectKey() {
        return lastObjectKey;
    }

    public String lastContentType() {
        return lastContentType;
    }

    public Duration lastExpires() {
        return lastExpires;
    }

    @Override
    public StoredObjectMetadata statObject(String objectKey) {
        Stored stored = storedObject(objectKey);
        return new StoredObjectMetadata(BUCKET, objectKey, stored.etag(),
                stored.content().length, stored.contentType());
    }

    @Override
    public InputStream readObject(String objectKey) {
        Stored stored = storedObject(objectKey);
        return new ByteArrayInputStream(Arrays.copyOf(stored.content(), stored.content().length));
    }

    private Stored storedObject(String objectKey) {
        Stored stored = objects.get(objectKey);
        if (stored == null) {
            throw new PlatformException(ErrorCode.STORAGE_OBJECT_NOT_FOUND,
                    "Storage object not found: " + objectKey);
        }
        return stored;
    }

    private record Stored(byte[] content, String contentType, String etag) {
    }
}
