package com.memora.application.mural;

import com.memora.application.identity.CurrentUserId;
import com.memora.application.ports.MuralRepository;
import com.memora.domain.mural.Mural;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

public final class UpdateMuralMetadata {
    private final MuralRepository murals;
    private final CurrentUserId currentUserId;
    private final Clock clock;

    public UpdateMuralMetadata(MuralRepository murals, CurrentUserId currentUserId, Clock clock) {
        this.murals = Objects.requireNonNull(murals);
        this.currentUserId = Objects.requireNonNull(currentUserId);
        this.clock = Objects.requireNonNull(clock);
    }

    public Mural execute(UpdateMuralMetadataCommand command) {
        Objects.requireNonNull(command, "command");
        UUID ownerId = currentUserId.get();
        Mural current = murals.findOwnedById(ownerId, command.muralId())
                .orElseThrow(MuralNotFoundException::new);
        if (current.version() != command.expectedVersion()) {
            throw new MuralStaleVersionException();
        }

        Mural updated = current.updateMetadata(command.title(), command.titlePresent(), command.description(),
                command.descriptionPresent(), clock.instant());
        if (updated == current) {
            return current;
        }

        try {
            return murals.updateOwned(updated, command.expectedVersion());
        } catch (MuralWriteConflictException concurrentUpdate) {
            throw new MuralStaleVersionException();
        }
    }
}
