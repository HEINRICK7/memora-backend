package com.memora.application.ports;

import com.memora.application.mural.IdempotentMural;
import com.memora.domain.mural.Mural;
import com.memora.domain.mural.MuralStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MuralRepository {
    Optional<IdempotentMural> findByOwnerAndIdempotencyKey(UUID ownerId, UUID idempotencyKey);

    Mural save(Mural mural, UUID idempotencyKey, String requestHash);

    List<Mural> findOwned(UUID ownerId, MuralStatus status, Instant beforeCreatedAt, UUID beforeId, int maxResults);

    Optional<Mural> findOwnedById(UUID ownerId, UUID muralId);

    Mural updateOwned(Mural mural, int expectedVersion);
}
