package com.memora.infrastructure.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "memora.storage")
public record StorageProperties(
        String internalEndpoint,
        String publicEndpoint,
        String bucket,
        String accessKey,
        String secretKey,
        String region,
        long maxUploadBytes,
        Integer maxConcurrentProcessing
) {
    public int effectiveMaxConcurrentProcessing() {
        return maxConcurrentProcessing == null || maxConcurrentProcessing <= 0 ? 4 : maxConcurrentProcessing;
    }
}
