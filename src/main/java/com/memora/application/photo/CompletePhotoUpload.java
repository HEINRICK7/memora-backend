package com.memora.application.photo;

import com.memora.application.ports.CurrentOwner;
import com.memora.application.ports.PhotoAssetRepository;
import com.memora.application.ports.PhotoStorage;
import com.memora.domain.photo.PhotoAsset;
import com.memora.domain.photo.PhotoStateConflictException;
import com.memora.domain.photo.PhotoStatus;
import com.memora.domain.photo.PhotoValidationException;
import com.memora.domain.photo.ProcessedImage;
import com.memora.domain.photo.UploadExpiredException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public final class CompletePhotoUpload {
    public static final Duration PREVIEW_TTL = Duration.ofMinutes(15);

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
        if (!clock.instant().isBefore(asset.intentExpiresAt())) {
            failAndCleanup(asset);
            throw new UploadExpiredException();
        }

        String shareViewKey = "derived/private/share-view/v1/" + asset.id()
                + ("image/png".equals(asset.contentType()) ? ".png" : ".jpg");
        String thumbnailKey = "derived/private/thumb/v1/" + asset.id() + ".jpg";

        try {
            ProcessedImage processed = storage.processUploadedPhoto(
                    asset.storageKey(), shareViewKey, thumbnailKey, asset.sizeBytes(), asset.contentType()
            );
            Instant now = clock.instant();
            PhotoAsset ready = asset.confirm(processed.original(), now);
            PhotoAsset persisted = repository.saveReadyWithDerivatives(
                    ready, shareViewKey, processed.shareView(), thumbnailKey, processed.thumbnail(), now
            );
            return view(persisted);
        } catch (PhotoValidationException | UploadExpiredException invalidUpload) {
            failAndCleanup(asset);
            throw invalidUpload;
        }
    }

    private void failAndCleanup(PhotoAsset asset) {
        repository.save(asset.fail());
        try {
            storage.delete(asset.storageKey());
        } catch (RuntimeException ignored) {
            // The bucket is private. OrphanUploadCleanupJob handles any remaining objects.
        }
    }

    private PhotoView view(PhotoAsset asset) {
        Instant expiresAt = clock.instant().plus(PREVIEW_TTL);
        String previewKey = asset.bestPreviewKey();
        String thumbKey = asset.bestThumbnailKey();
        String previewUrl = storage.presignGet(previewKey, PREVIEW_TTL, cachePolicyFor(previewKey));
        String thumbnailUrl = previewKey.equals(thumbKey)
                ? previewUrl
                : storage.presignGet(thumbKey, PREVIEW_TTL, cachePolicyFor(thumbKey));
        return new PhotoView(
                asset.id(), asset.status(), asset.contentType(), asset.sizeBytes(),
                previewUrl, thumbnailUrl, expiresAt
        );
    }

    private static String cachePolicyFor(String key) {
        return key.startsWith("derived/") ? PhotoStorage.PRIVATE_DERIVED_CACHE : PhotoStorage.PRIVATE_NO_STORE;
    }
}
