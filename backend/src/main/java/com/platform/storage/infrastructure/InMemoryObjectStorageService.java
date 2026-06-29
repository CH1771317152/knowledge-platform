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

/**
 * In-memory {@link ObjectStorageService} for the {@code integration} profile when real OSS
 * is not available. Lives in main sources (unlike {@code FakeObjectStorageService} in test
 * sources) so it is on the classpath during {@code spring-boot:run}.
 * <p>
 * Objects are lost on restart — suitable for local development and load testing only.
 */
@Service
@Profile("integration & !aliyun-oss-smoke")
public class InMemoryObjectStorageService implements ObjectStorageService {

    private static final String BUCKET = "in-memory";

    private final Map<String, Stored> objects = new ConcurrentHashMap<>();

    @Override
    public PresignedUpload presignPut(String objectKey, String contentType, Duration expires) {
        return new PresignedUpload(BUCKET, objectKey,
                "https://in-memory.local/" + objectKey,
                Map.of("Content-Type", contentType),
                LocalDateTime.now().plus(expires));
    }

    @Override
    public StoredObjectMetadata statObject(String objectKey) {
        Stored stored = get(objectKey);
        return new StoredObjectMetadata(BUCKET, objectKey, stored.etag(),
                stored.content().length, stored.contentType());
    }

    @Override
    public InputStream readObject(String objectKey) {
        Stored stored = get(objectKey);
        return new ByteArrayInputStream(Arrays.copyOf(stored.content(), stored.content().length));
    }

    /** Helper for integration tests to seed objects. */
    public void putObject(String objectKey, String contentType, byte[] content, String etag) {
        objects.put(objectKey, new Stored(Arrays.copyOf(content, content.length), contentType, etag));
    }

    private Stored get(String objectKey) {
        Stored stored = objects.get(objectKey);
        if (stored == null) {
            throw new PlatformException(ErrorCode.STORAGE_OBJECT_NOT_FOUND,
                    "Storage object not found: " + objectKey);
        }
        return stored;
    }

    private record Stored(byte[] content, String contentType, String etag) {}
}
