package com.memora.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.assertj.core.api.Assertions.assertThat;

class LocalAdminAuthenticationConfigurationTest {

    @Test
    void allowsConsecutiveAdminLoginsAfterCredentialsErasure() {
        LocalAdminAuthenticationConfiguration config = new LocalAdminAuthenticationConfiguration();
        PasswordEncoder encoder = config.localAdminPasswordEncoder();
        UserDetailsService users = config.localAdminUserDetailsService(
                "admin@example.com",
                "super-secret-password-123",
                encoder
        );
        AuthenticationManager authManager = config.localAdminAuthenticationManager(users, encoder);

        Authentication first = authManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("admin@example.com", "super-secret-password-123")
        );
        Authentication second = authManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated("admin@example.com", "super-secret-password-123")
        );

        assertThat(first.isAuthenticated()).isTrue();
        assertThat(second.isAuthenticated()).isTrue();
    }
}
