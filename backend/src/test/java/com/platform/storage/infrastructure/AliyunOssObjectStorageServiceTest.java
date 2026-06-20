package com.platform.storage.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.platform.common.exception.ErrorCode;
import com.platform.common.exception.PlatformException;
import com.platform.storage.config.StorageProperties;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class AliyunOssObjectStorageServiceTest {

    private final OSS ossClient = Mockito.mock(OSS.class);
    private final AliyunOssObjectStorageService storageService = new AliyunOssObjectStorageService(
            new StorageProperties("aliyun-oss", "https://oss.example.com", "cn-hangzhou",
                    "knowledge-platform-test", "access-key", "access-secret", 10),
            ossClient);

    @Test
    void mapsMissingObjectToStorageObjectNotFound() {
        when(ossClient.getObjectMetadata("knowledge-platform-test", "missing.txt"))
                .thenThrow(ossException("NoSuchKey"));

        assertThatThrownBy(() -> storageService.statObject("missing.txt"))
                .isInstanceOfSatisfying(PlatformException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.STORAGE_OBJECT_NOT_FOUND));
    }

    @Test
    void mapsMissingBucketToStorageObjectCheckFailed() {
        when(ossClient.getObjectMetadata("knowledge-platform-test", "content.txt"))
                .thenThrow(ossException("NoSuchBucket"));

        assertThatThrownBy(() -> storageService.statObject("content.txt"))
                .isInstanceOfSatisfying(PlatformException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ErrorCode.STORAGE_OBJECT_CHECK_FAILED));
    }

    private static OSSException ossException(String errorCode) {
        return new OSSException("OSS error", errorCode, "request-id", "host-id",
                "resource", "GET", "header");
    }
}
