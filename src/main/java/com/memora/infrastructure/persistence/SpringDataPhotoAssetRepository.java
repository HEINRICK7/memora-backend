package com.memora.infrastructure.persistence;

import com.memora.domain.photo.PhotoStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataPhotoAssetRepository extends JpaRepository<PhotoAssetEntity, UUID> {
    Optional<PhotoAssetEntity> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<PhotoAssetEntity> findByOwnerIdAndStatusOrderByCreatedAtDesc(
            UUID ownerId,
            PhotoStatus status
    );
}
