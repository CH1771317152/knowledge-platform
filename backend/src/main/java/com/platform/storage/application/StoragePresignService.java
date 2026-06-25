package com.platform.storage.application;

import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import com.platform.storage.config.StorageProperties;
import com.platform.storage.domain.ObjectKeyPolicy;
import com.platform.storage.domain.PresignedUpload;
import com.platform.storage.dto.PresignRequest;
import java.time.Duration;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class StoragePresignService {

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/webp",
            "image/gif",
            "application/pdf",
            "text/plain",
            "application/zip"
    );

    private final StorageProperties storageProperties;
    private final ObjectStorageService objectStorageService;

    public StoragePresignService(StorageProperties storageProperties,
                                 ObjectStorageService objectStorageService) {
        this.storageProperties = storageProperties;
        this.objectStorageService = objectStorageService;
    }

    public PresignedUpload presignForUser(Long userId, PresignRequest request) {
        String objectKey = request.objectKey();
        String contentType = request.contentType();

        if (!ObjectKeyPolicy.isOwnedObjectKey(objectKey, userId) || !isAllowedContentType(contentType)) {
            throw new PlatformException(ErrorCode.STORAGE_PRESIGN_FORBIDDEN, "Object key is not allowed");
        }

        int minutes = Math.min(request.expiresMinutes(), storageProperties.presignExpireMinutes());
        return objectStorageService.presignPut(objectKey, contentType, Duration.ofMinutes(minutes));
    }

    private static boolean isAllowedContentType(String contentType) {
        return contentType != null && ALLOWED_CONTENT_TYPES.contains(contentType);
    }
}
