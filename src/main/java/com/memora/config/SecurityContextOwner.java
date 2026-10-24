package com.memora.config;

import com.memora.application.identity.CurrentUserId;
import com.memora.application.ports.CurrentOwner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!local")
public final class SecurityContextOwner implements CurrentOwner {
    private final CurrentUserId currentUserId;

    public SecurityContextOwner(CurrentUserId currentUserId) {
        this.currentUserId = currentUserId;
    }

    @Override
    public String ownerId() {
        return currentUserId.get().toString();
    }
}
