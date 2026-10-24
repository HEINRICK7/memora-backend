package com.memora.config;

import com.memora.application.identity.Identity;
import com.memora.application.ports.CurrentIdentity;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("local")
public final class LocalDevelopmentIdentity implements CurrentIdentity {
    public static final String ISSUER = "urn:memora:local";
    public static final String SUBJECT = "local-development-owner";

    @Override
    public Identity identity() {
        return new Identity(ISSUER, SUBJECT);
    }
}
