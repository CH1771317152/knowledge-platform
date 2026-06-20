package com.platform.storage.application;

import com.platform.storage.domain.PresignedUpload;
import com.platform.storage.domain.StoredObjectMetadata;
import java.io.InputStream;
import java.time.Duration;

public interface ObjectStorageService {
    PresignedUpload presignPut(String objectKey, String contentType, Duration expires);

    StoredObjectMetadata statObject(String objectKey);

    InputStream readObject(String objectKey);
}
