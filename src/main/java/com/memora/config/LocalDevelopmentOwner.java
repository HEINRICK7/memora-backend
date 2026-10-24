package com.memora.config;

import com.memora.application.identity.CurrentUserId;
import com.memora.application.ports.CurrentOwner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public final class LocalDevelopmentOwner implements CurrentOwner {
    public static final String OWNER_ID = "local-development-owner";
    private final CurrentUserId currentUserId;

    public LocalDevelopmentOwner(CurrentUserId currentUserId) {
        this.currentUserId = currentUserId;
    }

    @Override
    public String ownerId() {
        return currentUserId.get().toString();
    }
}
