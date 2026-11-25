package com.memora.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Locale;

@Configuration
@Profile("local")
public class LocalAdminAuthenticationConfiguration {
    @Bean
    PasswordEncoder localAdminPasswordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    UserDetailsService localAdminUserDetailsService(
            @Value("${memora.admin.email:}") String configuredEmail,
            @Value("${memora.admin.password:}") String configuredPassword,
            PasswordEncoder passwordEncoder
    ) {
        if (configuredEmail == null || configuredEmail.isBlank()
                || configuredPassword == null || configuredPassword.length() < 12) {
            throw new IllegalStateException("Configure MEMORA_ADMIN_EMAIL e uma senha local com pelo menos 12 caracteres.");
        }

        String adminEmail = normalizeEmail(configuredEmail);
        String encodedPassword = passwordEncoder.encode(configuredPassword);

        return submittedEmail -> {
            if (!adminEmail.equals(normalizeEmail(submittedEmail))) {
                throw new UsernameNotFoundException("Credenciais inválidas.");
            }
            return User.withUsername(adminEmail)
                    .password(encodedPassword)
                    .roles("ADMIN")
                    .build();
        };
    }

    @Bean
    AuthenticationManager localAdminAuthenticationManager(
            UserDetailsService localAdminUserDetailsService,
            PasswordEncoder localAdminPasswordEncoder
    ) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(localAdminUserDetailsService);
        provider.setPasswordEncoder(localAdminPasswordEncoder);
        return new ProviderManager(provider);
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }
}
