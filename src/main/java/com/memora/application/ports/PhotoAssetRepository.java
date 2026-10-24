package com.memora.application.ports;

import com.memora.domain.photo.PhotoAsset;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PhotoAssetRepository {
    PhotoAsset save(PhotoAsset asset);

    Optional<PhotoAsset> findByIdAndOwner(UUID id, String ownerId);

    List<PhotoAsset> findReadyByOwner(String ownerId);
}
