package com.memora.config;

import com.memora.application.photo.CleanupOrphanUploads;
import com.memora.application.photo.CompletePhotoUpload;
import com.memora.application.photo.DeletePhoto;
import com.memora.application.photo.ListPhotos;
import com.memora.application.photo.PreparePhotoUpload;
import com.memora.application.ports.CurrentOwner;
import com.memora.application.ports.PhotoAssetRepository;
import com.memora.application.ports.PhotoStorage;
import com.memora.infrastructure.storage.StorageProperties;
import com.memora.presentation.http.UploadRateLimiter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

@Configuration
@EnableConfigurationProperties(PhotoLimitsProperties.class)
class PhotoUseCaseConfiguration {
    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }

    @Bean
    UploadRateLimiter uploadRateLimiter(Clock clock) {
        return new UploadRateLimiter(clock, Duration.ofMinutes(1));
    }

    @Bean
    PreparePhotoUpload preparePhotoUpload(
            PhotoAssetRepository repository,
            PhotoStorage storage,
            CurrentOwner currentOwner,
            Clock clock,
            StorageProperties properties,
            PhotoLimitsProperties limits
    ) {
        return new PreparePhotoUpload(
                repository,
                storage,
                currentOwner,
                clock,
                UUID::randomUUID,
                properties.maxUploadBytes(),
                limits.effectiveMaxPhotosPerOwner()
        );
    }

    @Bean
    CompletePhotoUpload completePhotoUpload(
            PhotoAssetRepository repository, PhotoStorage storage, CurrentOwner owner, Clock clock
    ) {
        return new CompletePhotoUpload(repository, storage, owner, clock);
    }

    @Bean
    ListPhotos listPhotos(PhotoAssetRepository repository, PhotoStorage storage, CurrentOwner owner, Clock clock) {
        return new ListPhotos(repository, storage, owner, clock);
    }

    @Bean
    DeletePhoto deletePhoto(PhotoAssetRepository repository, PhotoStorage storage, CurrentOwner owner, Clock clock) {
        return new DeletePhoto(repository, storage, owner, clock);
    }

    @Bean
    CleanupOrphanUploads cleanupOrphanUploads(PhotoAssetRepository repository, PhotoStorage storage, Clock clock) {
        return new CleanupOrphanUploads(repository, storage, clock);
    }
}
