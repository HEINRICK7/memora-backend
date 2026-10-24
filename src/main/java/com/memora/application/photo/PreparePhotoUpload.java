package com.memora.application.photo;

import com.memora.application.ports.CurrentOwner;
import com.memora.application.ports.PhotoAssetRepository;
import com.memora.application.ports.PhotoStorage;
import com.memora.domain.photo.PhotoAsset;
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

    private final PhotoAssetRepository repository;
    private final PhotoStorage storage;
    private final CurrentOwner currentOwner;
    private final Clock clock;
    private final Supplier<UUID> idGenerator;
    private final long maxBytes;

    public PreparePhotoUpload(
            PhotoAssetRepository repository,
            PhotoStorage storage,
            CurrentOwner currentOwner,
            Clock clock,
            Supplier<UUID> idGenerator,
            long maxBytes
    ) {
        this.repository = repository;
        this.storage = storage;
        this.currentOwner = currentOwner;
        this.clock = clock;
        this.idGenerator = idGenerator;
        this.maxBytes = maxBytes;
    }

    public UploadIntentResult execute(PreparePhotoUploadCommand command) {
        UploadMetadata metadata = UploadMetadata.request(
                command.contentType(), command.sizeBytes(), maxBytes
        );
        Instant now = clock.instant();
        UUID id = idGenerator.get();
        String storageKey = "originals/private/" + id;
        Instant urlExpiresAt = now.plus(SIGNED_PUT_TTL);
        PhotoAsset asset = PhotoAsset.beginUpload(
                id, currentOwner.ownerId(), storageKey, metadata, now, now.plus(INTENT_TTL)
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
