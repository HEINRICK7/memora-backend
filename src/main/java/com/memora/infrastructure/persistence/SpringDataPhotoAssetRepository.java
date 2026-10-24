package com.memora.infrastructure.persistence;

import com.memora.domain.photo.PhotoStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataPhotoAssetRepository extends JpaRepository<PhotoAssetEntity, UUID> {
    Optional<PhotoAssetEntity> findByIdAndOwnerId(UUID id, UUID ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT photo FROM PhotoAssetEntity photo WHERE photo.id = :id AND photo.ownerId = :ownerId")
    Optional<PhotoAssetEntity> lockByIdAndOwnerId(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    List<PhotoAssetEntity> findByOwnerIdAndStatusOrderByCreatedAtDesc(
            UUID ownerId,
            PhotoStatus status
    );
}
