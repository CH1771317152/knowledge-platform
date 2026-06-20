package com.platform.storage.infrastructure;

import com.aliyun.oss.ClientException;
import com.aliyun.oss.HttpMethod;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.ObjectMetadata;
import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import com.platform.storage.application.ObjectStorageService;
import com.platform.storage.config.StorageProperties;
import com.platform.storage.domain.PresignedUpload;
import com.platform.storage.domain.StoredObjectMetadata;
import jakarta.annotation.PreDestroy;
import java.io.InputStream;
import org.springframework.beans.factory.annotation.Autowired;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("!test & (!integration | aliyun-oss-smoke)")
public class AliyunOssObjectStorageService implements ObjectStorageService {

    private static final Set<String> MISSING_OBJECT_ERROR_CODES = Set.of(
            "NoSuchKey",
            "NotFound"
    );

    private final StorageProperties storageProperties;
    private final OSS ossClient;

    @Autowired
    public AliyunOssObjectStorageService(StorageProperties storageProperties) {
        this.storageProperties = storageProperties;
        this.ossClient = new OSSClientBuilder().build(
                storageProperties.endpoint(),
                storageProperties.accessKeyId(),
                storageProperties.accessKeySecret());
    }

    AliyunOssObjectStorageService(StorageProperties storageProperties, OSS ossClient) {
        this.storageProperties = storageProperties;
        this.ossClient = ossClient;
    }

    @Override
    public PresignedUpload presignPut(String objectKey, String contentType, Duration expires) {
        LocalDateTime expiresAt = LocalDateTime.now().plus(expires);
        Date expiration = Date.from(Instant.now().plus(expires));
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(
                storageProperties.bucket(), objectKey, HttpMethod.PUT);
        request.setExpiration(expiration);
        request.setContentType(contentType);
        request.addHeader("Content-Type", contentType);

        try {
            URL url = ossClient.generatePresignedUrl(request);
            return new PresignedUpload(storageProperties.bucket(), objectKey, url.toString(),
                    Map.of("Content-Type", contentType), expiresAt);
        } catch (ClientException exception) {
            throw objectCheckFailed("Failed to presign object upload: " + objectKey, exception);
        }
    }

    @Override
    public StoredObjectMetadata statObject(String objectKey) {
        try {
            ObjectMetadata metadata = ossClient.getObjectMetadata(storageProperties.bucket(), objectKey);
            return new StoredObjectMetadata(
                    storageProperties.bucket(),
                    objectKey,
                    metadata.getETag(),
                    metadata.getContentLength(),
                    metadata.getContentType());
        } catch (OSSException exception) {
            throw translateOssException(objectKey, exception);
        } catch (ClientException exception) {
            throw objectCheckFailed("Failed to stat object: " + objectKey, exception);
        }
    }

    @Override
    public InputStream readObject(String objectKey) {
        try {
            OSSObject object = ossClient.getObject(storageProperties.bucket(), objectKey);
            return object.getObjectContent();
        } catch (OSSException exception) {
            throw translateOssException(objectKey, exception);
        } catch (ClientException exception) {
            throw objectCheckFailed("Failed to read object: " + objectKey, exception);
        }
    }

    @PreDestroy
    public void shutdown() {
        ossClient.shutdown();
    }

    private static PlatformException translateOssException(String objectKey, OSSException exception) {
        if (MISSING_OBJECT_ERROR_CODES.contains(exception.getErrorCode())) {
            return new PlatformException(ErrorCode.STORAGE_OBJECT_NOT_FOUND,
                    "Storage object not found: " + objectKey);
        }
        return objectCheckFailed("Failed to check object: " + objectKey, exception);
    }

    private static PlatformException objectCheckFailed(String message, RuntimeException exception) {
        return new PlatformException(ErrorCode.STORAGE_OBJECT_CHECK_FAILED,
                message + " (" + exception.getMessage() + ")");
    }
}
