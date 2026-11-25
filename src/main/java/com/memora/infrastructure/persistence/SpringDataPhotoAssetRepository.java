package com.memora.infrastructure.persistence;

import com.memora.domain.photo.PhotoStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataPhotoAssetRepository extends JpaRepository<PhotoAssetEntity, UUID> {
    Optional<PhotoAssetEntity> findByIdAndOwnerId(UUID id, UUID ownerId);

    List<PhotoAssetEntity> findByIdInAndOwnerIdAndStatus(Collection<UUID> ids, UUID ownerId, PhotoStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT photo FROM PhotoAssetEntity photo WHERE photo.id = :id AND photo.ownerId = :ownerId")
    Optional<PhotoAssetEntity> lockByIdAndOwnerId(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    List<PhotoAssetEntity> findByOwnerIdAndStatusOrderByCreatedAtDesc(
            UUID ownerId,
            PhotoStatus status
    );

    @Query(value = """
            SELECT photo.* FROM photo_assets photo
            WHERE photo.owner_id = :ownerId AND photo.status = 'READY'
              AND NOT EXISTS (SELECT 1 FROM contributions contribution WHERE contribution.photo_id = photo.id)
            ORDER BY photo.created_at DESC, photo.id DESC
            """, nativeQuery = true)
    List<PhotoAssetEntity> findOwnerLibraryPhotos(@Param("ownerId") UUID ownerId);

    @Query(value = """
            SELECT photo.* FROM photo_assets photo
            WHERE photo.owner_id = :ownerId AND photo.status = 'READY'
              AND NOT EXISTS (SELECT 1 FROM contributions contribution WHERE contribution.photo_id = photo.id)
              AND (
                  CAST(:beforeCreatedAt AS TIMESTAMPTZ) IS NULL
                  OR photo.created_at < CAST(:beforeCreatedAt AS TIMESTAMPTZ)
                  OR (photo.created_at = CAST(:beforeCreatedAt AS TIMESTAMPTZ) AND photo.id < CAST(:beforeId AS UUID))
              )
            ORDER BY photo.created_at DESC, photo.id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<PhotoAssetEntity> findOwnerLibraryPhotosPage(
            @Param("ownerId") UUID ownerId,
            @Param("limit") int limit,
            @Param("beforeCreatedAt") Instant beforeCreatedAt,
            @Param("beforeId") UUID beforeId
    );

    @Query(value = """
            SELECT count(*) FROM photo_assets photo
            WHERE photo.owner_id = :ownerId
              AND photo.status IN ('READY', 'UPLOADING')
              AND NOT EXISTS (SELECT 1 FROM contributions contribution WHERE contribution.photo_id = photo.id)
            """, nativeQuery = true)
    long countActiveOwnerLibraryPhotos(@Param("ownerId") UUID ownerId);

    @Query(value = """
            SELECT photo.* FROM photo_assets photo
            WHERE photo.status = 'UPLOADING'
              AND photo.intent_expires_at <= :now
              AND NOT EXISTS (SELECT 1 FROM contributions contribution WHERE contribution.photo_id = photo.id)
            ORDER BY photo.intent_expires_at ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<PhotoAssetEntity> findExpiredUploadingOwnerPhotos(
            @Param("now") Instant now,
            @Param("limit") int limit
    );
}
