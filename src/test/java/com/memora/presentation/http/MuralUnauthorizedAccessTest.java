package com.memora.presentation.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/** Verifies MEM-002-R01/AC16's unauthenticated production-profile behavior without an external IdP. */
@ActiveProfiles("red-security")
@SpringBootTest(classes = MuralUnauthorizedAccessTest.SecurityOnlyApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.endpoint.health.group.readiness.include=readinessState")
class MuralUnauthorizedAccessTest {
    private static final HttpServer OIDC_DISCOVERY = startOidcDiscovery();
    private static final String ISSUER = "http://localhost:" + OIDC_DISCOVERY.getAddress().getPort() + "/issuer";
    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void issuer(DynamicPropertyRegistry registry) {
        registry.add("OIDC_ISSUER_URI", () -> ISSUER);
    }

    @Test
    void AC16_unautenticatedPrivateApiRequestReturns401ProblemDetails() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/murals"))
                        .GET().build(), HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/problem+json");
        JsonNode problem = JSON.readTree(response.body());
        assertThat(problem.path("status").asInt()).isEqualTo(401);
        assertThat(problem.path("code").asText()).isEqualTo("unauthenticated");
        assertThat(problem.path("traceId").asText()).isNotBlank();
    }

    private static HttpServer startOidcDiscovery() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            RSAPublicKey publicKey = (RSAPublicKey) generator.generateKeyPair().getPublic();
            String modulus = Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned(publicKey.getModulus()));
            String exponent = Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned(publicKey.getPublicExponent()));
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String issuer = "http://localhost:" + server.getAddress().getPort() + "/issuer";
                String body = exchange.getRequestURI().getPath().equals("/issuer/jwks")
                        ? "{\"keys\":[{\"kty\":\"RSA\",\"n\":\"" + modulus + "\",\"e\":\""
                                + exponent + "\",\"alg\":\"RS256\",\"use\":\"sig\",\"kid\":\"red-test-key\"}]}"
                        : "{\"issuer\":\"" + issuer + "\",\"jwks_uri\":\"" + issuer + "/jwks\"}";
                byte[] response = body.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(response);
                }
            });
            server.start();
            return server;
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    private static byte[] unsigned(java.math.BigInteger integer) {
        byte[] bytes = integer.toByteArray();
        if (bytes.length > 1 && bytes[0] == 0) {
            return java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return bytes;
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration(exclude = {
            DataSourceAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class
    })
    @Import(SecurityConfiguration.class)
    static class SecurityOnlyApplication { }
}
