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
        PhotoAsset deleted = repository.deleteIfUnattached(photoId, currentOwner.ownerId(), clock.instant());
        storage.delete(deleted.storageKey());
        if (deleted.shareViewStorageKey() != null && !deleted.shareViewStorageKey().isBlank()) {
            try {
                storage.delete(deleted.shareViewStorageKey());
            } catch (RuntimeException ignored) {
                // Best-effort derivative cleanup.
            }
        }
        if (deleted.thumbnailStorageKey() != null && !deleted.thumbnailStorageKey().isBlank()) {
            try {
                storage.delete(deleted.thumbnailStorageKey());
            } catch (RuntimeException ignored) {
                // Best-effort derivative cleanup.
            }
        }
    }
}
