package com.memora.application.photo;

import com.memora.application.ports.CurrentOwner;
import com.memora.application.ports.PhotoAssetRepository;
import com.memora.application.ports.PhotoStorage;
import com.memora.domain.photo.PhotoAsset;
import com.memora.domain.photo.PhotoStateConflictException;
import com.memora.domain.photo.PhotoStatus;
import com.memora.domain.photo.PhotoValidationException;
import com.memora.domain.photo.UploadExpiredException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class CompletePhotoUpload {
    public static final Duration PREVIEW_TTL = Duration.ofMinutes(5);

    private final PhotoAssetRepository repository;
    private final PhotoStorage storage;
    private final CurrentOwner currentOwner;
    private final Clock clock;

    public CompletePhotoUpload(
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

    public PhotoView execute(UUID uploadId) {
        PhotoAsset asset = repository.findByIdAndOwner(uploadId, currentOwner.ownerId())
                .orElseThrow(PhotoNotFoundException::new);

        if (asset.status() == PhotoStatus.READY) {
            return view(asset);
        }
        if (asset.status() != PhotoStatus.UPLOADING) {
            throw new PhotoStateConflictException("A intenção não pode ser concluída neste estado.");
        }

        try {
            PhotoAsset ready = asset.confirm(storage.inspect(asset.storageKey(), asset.sizeBytes()), clock.instant());
            repository.save(ready);
            return view(ready);
        } catch (PhotoValidationException | UploadExpiredException invalidUpload) {
            repository.save(asset.fail());
            try {
                storage.delete(asset.storageKey());
            } catch (RuntimeException ignored) {
                // The bucket is private. A later cleanup job can remove an orphan without exposing it.
            }
            throw invalidUpload;
        }
    }

    private PhotoView view(PhotoAsset asset) {
        Instant expiresAt = clock.instant().plus(PREVIEW_TTL);
        String previewUrl = storage.presignGet(asset.storageKey(), PREVIEW_TTL);
        return new PhotoView(
                asset.id(), asset.status(), asset.contentType(), asset.sizeBytes(), previewUrl, expiresAt
        );
    }
}
