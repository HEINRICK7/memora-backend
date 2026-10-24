package com.memora.application.mural;

import com.memora.application.identity.CurrentUserId;
import com.memora.application.ports.MuralRepository;
import com.memora.domain.mural.Mural;

import java.util.Objects;
import java.util.UUID;

public final class GetOwnedMural {
    private final MuralRepository murals;
    private final CurrentUserId currentUserId;

    public GetOwnedMural(MuralRepository murals, CurrentUserId currentUserId) {
        this.murals = Objects.requireNonNull(murals);
        this.currentUserId = Objects.requireNonNull(currentUserId);
    }

    public Mural execute(UUID muralId) {
        return murals.findOwnedById(currentUserId.get(), muralId).orElseThrow(MuralNotFoundException::new);
    }
}
