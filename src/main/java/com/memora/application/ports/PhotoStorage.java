package com.memora.application.ports;

import com.memora.domain.photo.InspectedImage;
import com.memora.domain.photo.ProcessedImage;

import java.time.Duration;

public interface PhotoStorage {
    String PRIVATE_NO_STORE = "private, no-store";
    String PRIVATE_DERIVED_CACHE = "private, max-age=600";

    String presignPut(String storageKey, String contentType, long contentLength, Duration ttl);

    void putPrivateObject(String storageKey, String contentType, byte[] content);

    InspectedImage inspect(String storageKey, long expectedSizeBytes);

    InspectedImage prepareShareView(String sourceKey, String targetKey, long expectedSizeBytes);

    ProcessedImage processUploadedPhoto(
            String sourceKey,
            String shareViewKey,
            String thumbnailKey,
            long expectedSizeBytes,
            String expectedContentType
    );

    String presignGet(String storageKey, Duration ttl);

    default String presignGet(String storageKey, Duration ttl, String cacheControl) {
        return presignGet(storageKey, ttl);
    }

    void delete(String storageKey);
}
