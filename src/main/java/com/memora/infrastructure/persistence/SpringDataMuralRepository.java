package com.memora.infrastructure.persistence;

import com.memora.domain.mural.MuralStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataMuralRepository extends JpaRepository<MuralEntity, UUID> {
    Optional<MuralEntity> findByOwnerIdAndIdempotencyKey(UUID ownerId, UUID idempotencyKey);

    Optional<MuralEntity> findByOwnerIdAndId(UUID ownerId, UUID id);

    @Query("""
            SELECT mural FROM MuralEntity mural
            WHERE mural.ownerId = :ownerId
              AND (:status IS NULL OR mural.status = :status)
            ORDER BY mural.createdAt DESC, mural.id DESC
            """)
    List<MuralEntity> findFirstOwnedPage(
            @Param("ownerId") UUID ownerId,
            @Param("status") MuralStatus status,
            org.springframework.data.domain.Pageable pageable
    );

    @Query("""
            SELECT mural FROM MuralEntity mural
            WHERE mural.ownerId = :ownerId
              AND (:status IS NULL OR mural.status = :status)
              AND (mural.createdAt < :beforeCreatedAt
                   OR (mural.createdAt = :beforeCreatedAt AND mural.id < :beforeId))
            ORDER BY mural.createdAt DESC, mural.id DESC
            """)
    List<MuralEntity> findOwnedPageAfterCursor(
            @Param("ownerId") UUID ownerId,
            @Param("status") MuralStatus status,
            @Param("beforeCreatedAt") Instant beforeCreatedAt,
            @Param("beforeId") UUID beforeId,
            org.springframework.data.domain.Pageable pageable
    );
}
