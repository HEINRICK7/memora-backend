package com.memora.config;

import com.memora.application.identity.Identity;
import com.memora.application.ports.CurrentIdentity;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
@Profile("local")
public final class LocalDevelopmentIdentity implements CurrentIdentity {
    public static final String ISSUER = "urn:memora:local";

    @Override
    public Identity identity() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !authentication.getAuthorities().stream().anyMatch(authority -> "ROLE_ADMIN".equals(authority.getAuthority()))) {
            throw new IllegalStateException("Uma sessão administrativa local não está disponível.");
        }
        return new Identity(ISSUER, authentication.getName().strip().toLowerCase(Locale.ROOT));
    }
}
