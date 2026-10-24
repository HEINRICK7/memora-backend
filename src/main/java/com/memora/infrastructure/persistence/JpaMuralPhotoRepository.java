package com.memora.infrastructure.persistence;

import com.memora.application.mural.MuralPhotoAttachment;
import com.memora.application.mural.MuralPhotoResourceNotFoundException;
import com.memora.application.mural.MuralPhotoSnapshot;
import com.memora.application.mural.MuralStaleVersionException;
import com.memora.application.ports.MuralPhotoRepository;
import com.memora.application.mural.PhotoNotReadyException;
import com.memora.domain.mural.InvalidMuralPhotoOrderException;
import com.memora.domain.mural.Mural;
import com.memora.domain.mural.MuralNotEditableException;
import com.memora.domain.mural.MuralPhoto;
import com.memora.domain.mural.MuralPhotoCollection;
import com.memora.domain.mural.MuralStatus;
import com.memora.domain.photo.PhotoAsset;
import com.memora.domain.photo.PhotoStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
class JpaMuralPhotoRepository implements MuralPhotoRepository {
    private final SpringDataMuralRepository murals;
    private final SpringDataMuralPhotoRepository links;
    private final SpringDataPhotoAssetRepository photos;
    private final EntityManager entityManager;

    JpaMuralPhotoRepository(SpringDataMuralRepository murals, SpringDataMuralPhotoRepository links,
                            SpringDataPhotoAssetRepository photos, EntityManager entityManager) {
        this.murals = murals;
        this.links = links;
        this.photos = photos;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public MuralPhotoSnapshot findOwned(UUID ownerId, UUID muralId) {
        MuralEntity mural = murals.findByOwnerIdAndId(ownerId, muralId)
                .orElseThrow(MuralPhotoResourceNotFoundException::new);
        return snapshot(mural, links.findByMuralIdOrderByPositionAsc(muralId));
    }

    @Override
    @Transactional
    public MuralPhotoAttachment attach(UUID ownerId, UUID muralId, UUID photoId, int expectedVersion, Instant now) {
        MuralEntity mural = lockEditableMural(ownerId, muralId, expectedVersion);
        PhotoAssetEntity photoEntity = photos.lockByIdAndOwnerId(photoId, ownerId)
                .orElseThrow(MuralPhotoResourceNotFoundException::new);
        PhotoAsset photo = photoEntity.toDomain();
        if (photo.status() != PhotoStatus.READY) {
            throw new PhotoNotReadyException();
        }

        List<MuralPhotoEntity> existingLinks = links.findByMuralIdOrderByPositionAsc(muralId);
        MuralPhotoCollection collection = restore(muralId, existingLinks);
        MuralPhotoCollection updated = collection.append(photoId);
        boolean created = updated != collection;
        if (created) {
            MuralPhoto added = updated.photos().get(updated.photos().size() - 1);
            links.save(MuralPhotoEntity.fromDomain(added));
            touchAndFlush(mural, now);
        }
        List<MuralPhotoEntity> currentLinks = links.findByMuralIdOrderByPositionAsc(muralId);
        MuralPhotoSnapshot result = snapshot(mural, currentLinks);
        MuralPhoto link = result.photos().stream().filter(item -> item.photoId().equals(photoId))
                .findFirst().orElseThrow(MuralPhotoResourceNotFoundException::new);
        return new MuralPhotoAttachment(result, link, created);
    }

    @Override
    @Transactional
    public MuralPhotoSnapshot reorder(UUID ownerId, UUID muralId, List<UUID> photoIds,
                                      int expectedVersion, Instant now) {
        MuralEntity mural = lockEditableMural(ownerId, muralId, expectedVersion);
        List<MuralPhotoEntity> currentLinks = links.findByMuralIdOrderByPositionAsc(muralId);
        MuralPhotoCollection collection = restore(muralId, currentLinks);
        MuralPhotoCollection reordered = collection.reorder(photoIds);
        if (reordered != collection) {
            for (int index = 0; index < reordered.photos().size(); index++) {
                UUID photoId = reordered.photos().get(index).photoId();
                MuralPhotoEntity entity = currentLinks.get(index);
                if (!entity.photoId().equals(photoId)) {
                    entity = currentLinks.stream().filter(link -> link.photoId().equals(photoId))
                            .findFirst().orElseThrow(InvalidMuralPhotoOrderException::new);
                }
                entity.setPosition(index);
            }
            touchAndFlush(mural, now);
        }
        return snapshot(mural, links.findByMuralIdOrderByPositionAsc(muralId));
    }

    @Override
    @Transactional
    public MuralPhotoSnapshot remove(UUID ownerId, UUID muralId, UUID photoId, int expectedVersion, Instant now) {
        MuralEntity mural = lockEditableMural(ownerId, muralId, expectedVersion);
        List<MuralPhotoEntity> currentLinks = links.findByMuralIdOrderByPositionAsc(muralId);
        MuralPhotoCollection collection = restore(muralId, currentLinks);
        MuralPhotoCollection remaining = collection.remove(photoId);
        MuralPhotoEntity removed = currentLinks.stream().filter(link -> link.photoId().equals(photoId))
                .findFirst().orElseThrow(MuralPhotoResourceNotFoundException::new);
        links.delete(removed);
        for (int index = 0; index < remaining.photos().size(); index++) {
            UUID remainingPhotoId = remaining.photos().get(index).photoId();
            currentLinks.stream().filter(link -> link.photoId().equals(remainingPhotoId)).findFirst()
                    .orElseThrow(MuralPhotoResourceNotFoundException::new).setPosition(index);
        }
        touchAndFlush(mural, now);
        return snapshot(mural, links.findByMuralIdOrderByPositionAsc(muralId));
    }

    private MuralEntity lockEditableMural(UUID ownerId, UUID muralId, int expectedVersion) {
        MuralEntity mural = murals.lockOwnedById(ownerId, muralId)
                .orElseThrow(MuralPhotoResourceNotFoundException::new);
        Mural current = mural.toDomain();
        if (current.version() != expectedVersion) {
            throw new MuralStaleVersionException();
        }
        if (current.status() != MuralStatus.DRAFT) {
            throw new MuralNotEditableException();
        }
        return mural;
    }

    private void touchAndFlush(MuralEntity mural, Instant now) {
        mural.touch(now);
        try {
            entityManager.flush();
        } catch (OptimisticLockException | OptimisticLockingFailureException conflict) {
            throw new MuralStaleVersionException();
        }
    }

    private static MuralPhotoCollection restore(UUID muralId, List<MuralPhotoEntity> entities) {
        return MuralPhotoCollection.restore(muralId, entities.stream().map(MuralPhotoEntity::toDomain).toList());
    }

    private static MuralPhotoSnapshot snapshot(MuralEntity mural, List<MuralPhotoEntity> entities) {
        return new MuralPhotoSnapshot(mural.toDomain(), entities.stream().map(MuralPhotoEntity::toDomain).toList());
    }
}
