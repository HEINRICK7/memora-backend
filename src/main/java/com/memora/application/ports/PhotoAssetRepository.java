package com.memora.application.ports;

import com.memora.domain.photo.PhotoAsset;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

public interface PhotoAssetRepository {
    PhotoAsset save(PhotoAsset asset);

    Optional<PhotoAsset> findByIdAndOwner(UUID id, String ownerId);

    List<PhotoAsset> findReadyByOwner(String ownerId);

    /** Atomically marks an unlinked owned asset deleted and returns it for post-commit storage cleanup. */
    PhotoAsset deleteIfUnattached(UUID id, String ownerId, Instant now);
}
