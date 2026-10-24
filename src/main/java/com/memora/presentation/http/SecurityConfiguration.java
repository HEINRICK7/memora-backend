package com.memora.presentation.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

@Configuration
class SecurityConfiguration {
    private final ObjectMapper objectMapper;

    SecurityConfiguration(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    @Profile("local")
    SecurityFilterChain localSecurity(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().permitAll())
                .build();
    }

    @Bean
    @Profile("!local")
    JwtDecoder jwtDecoder(@Value("${OIDC_ISSUER_URI}") String issuerUri) {
        return JwtDecoders.fromIssuerLocation(issuerUri);
    }

    @Bean
    @Profile("!local")
    SecurityFilterChain authenticatedSecurity(HttpSecurity http) throws Exception {
        return http
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(problemEntryPoint()))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(problemEntryPoint())
                        .accessDeniedHandler(problemDeniedHandler()))
                .build();
    }

    private AuthenticationEntryPoint problemEntryPoint() {
        return (request, response, exception) -> writeProblem(response, HttpStatus.UNAUTHORIZED,
                "unauthenticated", "Autenticação necessária.");
    }

    private AccessDeniedHandler problemDeniedHandler() {
        return (request, response, exception) -> writeProblem(response, HttpStatus.FORBIDDEN,
                "forbidden", "Acesso não permitido.");
    }

    private void writeProblem(jakarta.servlet.http.HttpServletResponse response, HttpStatus status,
                              String code, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), Map.of(
                "type", URI.create("about:blank"),
                "title", status.getReasonPhrase(),
                "status", status.value(),
                "detail", detail,
                "code", code,
                "traceId", UUID.randomUUID().toString()));
    }
}
