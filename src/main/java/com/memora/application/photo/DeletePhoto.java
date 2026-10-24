package com.memora.application.photo;

import com.memora.application.ports.CurrentOwner;
import com.memora.application.ports.PhotoAssetRepository;
import com.memora.application.ports.PhotoStorage;
import com.memora.domain.photo.PhotoAsset;

import java.time.Clock;
import java.util.UUID;

public final class DeletePhoto {
    private final PhotoAssetRepository repository;
    private final PhotoStorage storage;
    private final CurrentOwner currentOwner;
    private final Clock clock;

    public DeletePhoto(
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

    public void execute(UUID photoId) {
        PhotoAsset asset = repository.findByIdAndOwner(photoId, currentOwner.ownerId())
                .orElseThrow(PhotoNotFoundException::new);
        if (asset.status() == com.memora.domain.photo.PhotoStatus.DELETED) {
            storage.delete(asset.storageKey());
            return;
        }
        PhotoAsset deleted = asset.delete(clock.instant());
        repository.save(deleted);
        storage.delete(deleted.storageKey());
    }
}
