package com.memora.application.mural;

import com.memora.application.identity.CurrentUserId;
import com.memora.application.ports.MuralPhotoRepository;

import java.time.Clock;
import java.util.Objects;

public final class AttachPhotoToMural {
    private final MuralPhotoRepository repository;
    private final CurrentUserId currentUser;
    private final Clock clock;

    public AttachPhotoToMural(MuralPhotoRepository repository, CurrentUserId currentUser, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.currentUser = Objects.requireNonNull(currentUser);
        this.clock = Objects.requireNonNull(clock);
    }

    public MuralPhotoAttachment execute(AttachPhotoToMuralCommand command) {
        Objects.requireNonNull(command, "command");
        return repository.attach(currentUser.get(), command.muralId(), command.photoId(),
                command.expectedVersion(), clock.instant());
    }
}
