package com.memora.infrastructure.persistence;

import com.memora.application.mural.DuplicateMuralIdempotencyKeyException;
import com.memora.application.mural.IdempotentMural;
import com.memora.application.mural.MuralNotFoundException;
import com.memora.application.ports.MuralRepository;
import com.memora.application.mural.MuralWriteConflictException;
import com.memora.domain.mural.Mural;
import com.memora.domain.mural.MuralStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.OptimisticLockException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaMuralRepository implements MuralRepository {
    private final SpringDataMuralRepository repository;
    private final EntityManager entityManager;

    JpaMuralRepository(SpringDataMuralRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IdempotentMural> findByOwnerAndIdempotencyKey(UUID ownerId, UUID idempotencyKey) {
        return repository.findByOwnerIdAndIdempotencyKey(ownerId, idempotencyKey)
                .map(entity -> new IdempotentMural(entity.toDomain(), entity.requestHash()));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Mural save(Mural mural, UUID idempotencyKey, String requestHash) {
        List<?> inserted = entityManager.createNativeQuery("""
                        INSERT INTO murals
                            (id, owner_id, idempotency_key, request_hash, title, description,
                             status, lock_version, created_at, updated_at)
                        VALUES (:id, :ownerId, :idempotencyKey, :requestHash, :title, :description,
                                :status, 0, :createdAt, :updatedAt)
                        ON CONFLICT ON CONSTRAINT uq_murals_owner_idempotency DO NOTHING
                        RETURNING id
                        """)
                .setParameter("id", mural.id())
                .setParameter("ownerId", mural.ownerId())
                .setParameter("idempotencyKey", idempotencyKey)
                .setParameter("requestHash", requestHash)
                .setParameter("title", mural.title())
                .setParameter("description", mural.description())
                .setParameter("status", mural.status().name())
                .setParameter("createdAt", mural.createdAt())
                .setParameter("updatedAt", mural.updatedAt())
                .getResultList();
        if (inserted.isEmpty()) {
            throw new DuplicateMuralIdempotencyKeyException();
        }
        MuralEntity entity = repository.findById(mural.id()).orElseThrow();
        entityManager.refresh(entity);
        return entity.toDomain();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Mural> findOwned(UUID ownerId, MuralStatus status, Instant beforeCreatedAt,
                                 UUID beforeId, int maxResults) {
        var page = beforeCreatedAt == null
                ? repository.findFirstOwnedPage(ownerId, status, PageRequest.of(0, maxResults))
                : repository.findOwnedPageAfterCursor(ownerId, status, beforeCreatedAt, beforeId,
                        PageRequest.of(0, maxResults));
        return page
                .stream().map(MuralEntity::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Mural> findOwnedById(UUID ownerId, UUID muralId) {
        return repository.findByOwnerIdAndId(ownerId, muralId).map(MuralEntity::toDomain);
    }

    @Override
    @Transactional
    public Mural updateOwned(Mural mural, int expectedVersion) {
        MuralEntity entity = repository.findByOwnerIdAndId(mural.ownerId(), mural.id())
                .orElseThrow(MuralNotFoundException::new);
        if (entity.toDomain().version() != expectedVersion) {
            throw new MuralWriteConflictException();
        }
        entity.updateMetadata(mural);
        try {
            entityManager.flush();
            entityManager.refresh(entity);
            return entity.toDomain();
        } catch (OptimisticLockException | OptimisticLockingFailureException conflict) {
            throw new MuralWriteConflictException();
        }
    }

}
