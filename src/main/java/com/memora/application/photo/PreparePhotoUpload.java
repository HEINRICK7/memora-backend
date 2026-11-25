package com.memora.application.photo;

import com.memora.application.ports.CurrentOwner;
import com.memora.application.ports.PhotoAssetRepository;
import com.memora.application.ports.PhotoStorage;
import com.memora.domain.photo.PhotoAsset;
import com.memora.domain.photo.PhotoQuotaExceededException;
import com.memora.domain.photo.UploadMetadata;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

public final class PreparePhotoUpload {
    public static final Duration SIGNED_PUT_TTL = Duration.ofMinutes(5);
    public static final Duration INTENT_TTL = Duration.ofMinutes(15);
    public static final int DEFAULT_MAX_PHOTOS_PER_OWNER = 500;

    private final PhotoAssetRepository repository;
    private final PhotoStorage storage;
    private final CurrentOwner currentOwner;
    private final Clock clock;
    private final Supplier<UUID> idGenerator;
    private final long maxBytes;
    private final int maxPhotosPerOwner;

    public PreparePhotoUpload(
            PhotoAssetRepository repository,
            PhotoStorage storage,
            CurrentOwner currentOwner,
            Clock clock,
            Supplier<UUID> idGenerator,
            long maxBytes
    ) {
        this(repository, storage, currentOwner, clock, idGenerator, maxBytes, DEFAULT_MAX_PHOTOS_PER_OWNER);
    }

    public PreparePhotoUpload(
            PhotoAssetRepository repository,
            PhotoStorage storage,
            CurrentOwner currentOwner,
            Clock clock,
            Supplier<UUID> idGenerator,
            long maxBytes,
            int maxPhotosPerOwner
    ) {
        this.repository = repository;
        this.storage = storage;
        this.currentOwner = currentOwner;
        this.clock = clock;
        this.idGenerator = idGenerator;
        this.maxBytes = maxBytes;
        this.maxPhotosPerOwner = maxPhotosPerOwner;
    }

    public UploadIntentResult execute(PreparePhotoUploadCommand command) {
        UploadMetadata metadata = UploadMetadata.request(
                command.contentType(), command.sizeBytes(), maxBytes
        );
        String ownerId = currentOwner.ownerId();
        if (maxPhotosPerOwner > 0 && repository.countActiveByOwner(ownerId) >= maxPhotosPerOwner) {
            throw new PhotoQuotaExceededException(
                    "Sua biblioteca atingiu o limite máximo de " + maxPhotosPerOwner + " fotografias."
            );
        }
        Instant now = clock.instant();
        UUID id = idGenerator.get();
        String storageKey = "originals/private/" + id;
        Instant urlExpiresAt = now.plus(SIGNED_PUT_TTL);
        PhotoAsset asset = PhotoAsset.beginUpload(
                id, ownerId, storageKey, metadata, now, now.plus(INTENT_TTL)
        );
        String uploadUrl = storage.presignPut(
                storageKey, metadata.contentType(), metadata.sizeBytes(), SIGNED_PUT_TTL
        );
        repository.save(asset);

        return new UploadIntentResult(
                id, "PUT", uploadUrl, Map.of("Content-Type", metadata.contentType()), urlExpiresAt
        );
    }
}
