package com.memora.config;

import com.memora.application.identity.Identity;
import com.memora.application.ports.CurrentIdentity;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

@Component
@Profile("!local")
public final class SecurityContextIdentity implements CurrentIdentity {
    @Override
    public Identity identity() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)
                || !authentication.isAuthenticated()) {
            throw new IllegalStateException("Uma identidade JWT validada não está disponível.");
        }
        var jwt = jwtAuthentication.getToken();
        if (jwt.getIssuer() == null || jwt.getSubject() == null || jwt.getSubject().isBlank()) {
            throw new IllegalStateException("O JWT validado não contém issuer e subject.");
        }
        return new Identity(jwt.getIssuer().toString(), jwt.getSubject());
    }
}
