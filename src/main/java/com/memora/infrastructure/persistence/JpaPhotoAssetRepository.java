package com.memora.infrastructure.persistence;

import com.memora.application.ports.PhotoAssetRepository;
import com.memora.application.photo.PhotoAttachedException;
import com.memora.application.photo.PhotoNotFoundException;
import com.memora.domain.photo.PhotoAsset;
import com.memora.domain.photo.PhotoStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

@Repository
class JpaPhotoAssetRepository implements PhotoAssetRepository {
    private final SpringDataPhotoAssetRepository repository;
    private final SpringDataMuralPhotoRepository muralPhotos;

    JpaPhotoAssetRepository(SpringDataPhotoAssetRepository repository, SpringDataMuralPhotoRepository muralPhotos) {
        this.repository = repository;
        this.muralPhotos = muralPhotos;
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

    @Override
    @Transactional
    public PhotoAsset deleteIfUnattached(UUID id, String ownerId, Instant now) {
        PhotoAssetEntity entity = repository.lockByIdAndOwnerId(id, UUID.fromString(ownerId))
                .orElseThrow(PhotoNotFoundException::new);
        if (muralPhotos.existsByPhotoId(id)) {
            throw new PhotoAttachedException();
        }
        PhotoAsset asset = entity.toDomain();
        if (asset.status() == PhotoStatus.DELETED) {
            return asset;
        }
        PhotoAsset deleted = asset.delete(now);
        entity.updateFromDomain(deleted);
        repository.saveAndFlush(entity);
        return entity.toDomain();
    }
}
