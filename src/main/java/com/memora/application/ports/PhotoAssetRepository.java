package com.memora.application.ports;

import com.memora.domain.photo.InspectedImage;
import com.memora.domain.photo.PhotoAsset;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PhotoAssetRepository {
    PhotoAsset save(PhotoAsset asset);

    PhotoAsset saveReadyWithShareView(PhotoAsset asset, String storageKey, InspectedImage image, Instant readyAt);

    PhotoAsset saveReadyWithDerivatives(
            PhotoAsset asset,
            String shareViewKey,
            InspectedImage shareView,
            String thumbnailKey,
            InspectedImage thumbnail,
            Instant readyAt
    );

    Optional<PhotoAsset> findByIdAndOwner(UUID id, String ownerId);

    List<PhotoAsset> findReadyByIdsAndOwner(List<UUID> ids, String ownerId);

    List<PhotoAsset> findReadyByOwner(String ownerId);

    List<PhotoAsset> findReadyByOwnerPage(String ownerId, int limit, Instant beforeCreatedAt, UUID beforeId);

    long countActiveByOwner(String ownerId);

    List<PhotoAsset> findExpiredUploading(Instant now, int limit);

    /** Atomically marks an unlinked owned asset deleted and returns it for post-commit storage cleanup. */
    PhotoAsset deleteIfUnattached(UUID id, String ownerId, Instant now);
}
