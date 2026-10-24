package com.memora.application.ports;

import com.memora.application.mural.MuralPhotoSnapshot;
import com.memora.application.mural.MuralPhotoAttachment;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Transaction boundary for an owned mural and its ordered photo links. */
public interface MuralPhotoRepository {
    MuralPhotoSnapshot findOwned(UUID ownerId, UUID muralId);

    MuralPhotoAttachment attach(UUID ownerId, UUID muralId, UUID photoId, int expectedVersion, Instant now);

    MuralPhotoSnapshot reorder(UUID ownerId, UUID muralId, List<UUID> photoIds, int expectedVersion, Instant now);

    MuralPhotoSnapshot remove(UUID ownerId, UUID muralId, UUID photoId, int expectedVersion, Instant now);
}
