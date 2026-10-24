package com.memora.config;

import com.memora.application.photo.CompletePhotoUpload;
import com.memora.application.photo.DeletePhoto;
import com.memora.application.photo.ListPhotos;
import com.memora.application.photo.PreparePhotoUpload;
import com.memora.application.ports.CurrentOwner;
import com.memora.application.ports.PhotoAssetRepository;
import com.memora.application.ports.PhotoStorage;
import com.memora.infrastructure.storage.StorageProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.util.UUID;

@Configuration
class PhotoUseCaseConfiguration {
    @Bean
    Clock applicationClock() {
        return Clock.systemUTC();
    }

    @Bean
    PreparePhotoUpload preparePhotoUpload(
            PhotoAssetRepository repository,
            PhotoStorage storage,
            CurrentOwner currentOwner,
            Clock clock,
            StorageProperties properties
    ) {
        return new PreparePhotoUpload(repository, storage, currentOwner, clock, UUID::randomUUID, properties.maxUploadBytes());
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
}
