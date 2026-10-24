package com.memora.infrastructure.storage;

import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

@Component("minio")
class MinioHealthIndicator implements HealthIndicator {
    private final S3Client s3;
    private final StorageProperties properties;

    MinioHealthIndicator(S3Client s3, StorageProperties properties) {
        this.s3 = s3;
        this.properties = properties;
    }

    @Override
    public Health health() {
        try {
            s3.headBucket(HeadBucketRequest.builder().bucket(properties.bucket()).build());
            return Health.up().build();
        } catch (RuntimeException unavailable) {
            return Health.down().build();
        }
    }
}
