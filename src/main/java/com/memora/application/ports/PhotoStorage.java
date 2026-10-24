package com.memora.application.ports;

import com.memora.domain.photo.InspectedImage;

import java.time.Duration;

public interface PhotoStorage {
    String presignPut(String storageKey, String contentType, long contentLength, Duration ttl);

    InspectedImage inspect(String storageKey, long expectedSizeBytes);

    String presignGet(String storageKey, Duration ttl);

    void delete(String storageKey);
}
