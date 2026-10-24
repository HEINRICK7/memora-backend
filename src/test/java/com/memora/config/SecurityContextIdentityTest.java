package com.memora.config;

import com.memora.application.identity.Identity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityContextIdentityTest {
    private final SecurityContextIdentity identity = new SecurityContextIdentity();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void readsValidatedIssuerAndSubjectSeparatelyFromJwtClaims() {
        authenticate("https://issuer-a.example", "same-subject");

        assertThat(identity.identity()).isEqualTo(new Identity("https://issuer-a.example", "same-subject"));
    }

    @Test
    void distinguishesEqualSubjectsFromDifferentIssuers() {
        authenticate("https://issuer-a.example", "same-subject");
        Identity first = identity.identity();
        authenticate("https://issuer-b.example", "same-subject");
        Identity second = identity.identity();

        assertThat(first.subject()).isEqualTo(second.subject());
        assertThat(first.issuer()).isNotEqualTo(second.issuer());
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void preservesIssuerAndSubjectClaimsWithoutTrimmingOpaqueValues() {
        authenticate("https://issuer.example/", " subject ");

        assertThat(identity.identity()).isEqualTo(new Identity("https://issuer.example/", " subject "));
    }

    private static void authenticate(String issuer, String subject) {
        Instant now = Instant.parse("2026-10-24T12:00:00Z");
        Jwt jwt = Jwt.withTokenValue("unit-test-only")
                .header("alg", "RS256")
                .issuer(issuer)
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .build();
        JwtAuthenticationToken authentication = new JwtAuthenticationToken(jwt);
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }
}
