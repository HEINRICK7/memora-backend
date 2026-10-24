package com.memora.application.identity;

import com.memora.application.ports.CurrentIdentity;
import com.memora.application.ports.UserRepository;

import java.util.Objects;
import java.util.UUID;

public final class CurrentUserId {
    private final CurrentIdentity currentIdentity;
    private final UserRepository users;

    public CurrentUserId(CurrentIdentity currentIdentity, UserRepository users) {
        this.currentIdentity = Objects.requireNonNull(currentIdentity);
        this.users = Objects.requireNonNull(users);
    }

    public UUID get() {
        return users.resolveOrCreate(currentIdentity.identity());
    }
}
