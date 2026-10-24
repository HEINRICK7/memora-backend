package com.memora.infrastructure.storage;

import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("local")
class S3BucketInitializer {
    @Bean
    ApplicationRunner initializePrivatePhotoBucket(S3PhotoStorage storage) {
        return args -> storage.ensurePrivateBucket();
    }
}
