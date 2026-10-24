package com.memora.application.photo;

import com.memora.application.ports.CurrentOwner;
import com.memora.application.ports.PhotoAssetRepository;
import com.memora.application.ports.PhotoStorage;
import com.memora.domain.photo.PhotoAsset;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public final class ListPhotos {
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(5);

    private final PhotoAssetRepository repository;
    private final PhotoStorage storage;
    private final CurrentOwner currentOwner;
    private final Clock clock;

    public ListPhotos(
            PhotoAssetRepository repository,
            PhotoStorage storage,
            CurrentOwner currentOwner,
            Clock clock
    ) {
        this.repository = repository;
        this.storage = storage;
        this.currentOwner = currentOwner;
        this.clock = clock;
    }

    public List<PhotoView> execute() {
        return repository.findReadyByOwner(currentOwner.ownerId()).stream()
                .map(this::view)
                .toList();
    }

    private PhotoView view(PhotoAsset asset) {
        Instant expiresAt = clock.instant().plus(PREVIEW_TTL);
        String previewUrl = storage.presignGet(asset.storageKey(), PREVIEW_TTL);
        return new PhotoView(
                asset.id(), asset.status(), asset.contentType(), asset.sizeBytes(), previewUrl, expiresAt
        );
    }
}
