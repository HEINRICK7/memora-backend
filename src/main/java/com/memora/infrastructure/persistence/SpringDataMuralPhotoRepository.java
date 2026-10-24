package com.memora.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface SpringDataMuralPhotoRepository extends JpaRepository<MuralPhotoEntity, UUID> {
    List<MuralPhotoEntity> findByMuralIdOrderByPositionAsc(UUID muralId);

    Optional<MuralPhotoEntity> findByMuralIdAndPhotoId(UUID muralId, UUID photoId);

    boolean existsByPhotoId(UUID photoId);
}
