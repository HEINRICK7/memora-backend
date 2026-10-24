package com.memora.application.mural;

import com.memora.application.identity.CurrentUserId;
import com.memora.application.ports.MuralPhotoRepository;

import java.time.Clock;
import java.util.Objects;

public final class ReorderMuralPhotos {
    private final MuralPhotoRepository repository;
    private final CurrentUserId currentUser;
    private final Clock clock;

    public ReorderMuralPhotos(MuralPhotoRepository repository, CurrentUserId currentUser, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.currentUser = Objects.requireNonNull(currentUser);
        this.clock = Objects.requireNonNull(clock);
    }

    public MuralPhotoSnapshot execute(ReorderMuralPhotosCommand command) {
        Objects.requireNonNull(command, "command");
        return repository.reorder(currentUser.get(), command.muralId(), command.photoIds(),
                command.expectedVersion(), clock.instant());
    }
}
