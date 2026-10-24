package com.memora.infrastructure.persistence;

import com.memora.application.ports.PhotoAssetRepository;
import com.memora.domain.photo.PhotoAsset;
import com.memora.domain.photo.PhotoStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaPhotoAssetRepository implements PhotoAssetRepository {
    private final SpringDataPhotoAssetRepository repository;

    JpaPhotoAssetRepository(SpringDataPhotoAssetRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public PhotoAsset save(PhotoAsset asset) {
        PhotoAssetEntity entity = repository.findById(asset.id())
                .orElseGet(() -> PhotoAssetEntity.fromDomain(asset));
        entity.updateFromDomain(asset);
        return repository.save(entity).toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PhotoAsset> findByIdAndOwner(UUID id, String ownerId) {
        return repository.findByIdAndOwnerId(id, UUID.fromString(ownerId)).map(PhotoAssetEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PhotoAsset> findReadyByOwner(String ownerId) {
        return repository.findByOwnerIdAndStatusOrderByCreatedAtDesc(UUID.fromString(ownerId), PhotoStatus.READY)
                .stream()
                .map(PhotoAssetEntity::toDomain)
                .toList();
    }
}
