package com.memora.integration;

import com.memora.application.identity.Identity;
import com.memora.application.ports.CurrentIdentity;
import com.memora.application.ports.UserRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** RED/green check for MEM-002-R01 and ADR-08 using an isolated real PostgreSQL schema. */
@EnabledIfEnvironmentVariable(named = "MEMORA_MURAL_INTEGRATION_TESTS", matches = "true")
@ActiveProfiles("local")
@SpringBootTest
class UserIdentityResolutionPostgresTest {
    private static volatile String isolatedSchema;

    @Autowired
    private UserRepository users;

    @Autowired
    private CurrentIdentity currentIdentity;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", UserIdentityResolutionPostgresTest::schemaJdbcUrl);
        registry.add("spring.datasource.username", () -> requiredEnvironment("MEMORA_TEST_JDBC_USERNAME"));
        registry.add("spring.datasource.password", () -> requiredEnvironment("MEMORA_TEST_JDBC_PASSWORD"));
        registry.add("spring.flyway.schemas", UserIdentityResolutionPostgresTest::schemaName);
        registry.add("spring.flyway.default-schema", UserIdentityResolutionPostgresTest::schemaName);
        registry.add("spring.flyway.placeholders.memoraIdentityIssuer", () -> "urn:memora:local");
        registry.add("memora.storage.internal-endpoint", () -> "http://minio:9000");
        registry.add("memora.storage.public-endpoint", () -> "http://localhost:9000");
        registry.add("memora.storage.bucket", () -> "memora-private");
        registry.add("memora.storage.access-key", () -> System.getenv().getOrDefault("MINIO_ACCESS_KEY", "memora_local_admin"));
        registry.add("memora.storage.secret-key", () -> System.getenv().getOrDefault("MINIO_SECRET_KEY", "memora-local-minio-password"));
    }

    @Test
    void resolvesLocalAndJwtIssuerSubjectPairsIdempotentlyAndConcurrently() throws Exception {
        assertThat(currentIdentity.identity()).isEqualTo(new Identity("urn:memora:local", "local-development-owner"));
        assertThat(countUsers()).isZero();

        UUID localId = users.resolveOrCreate(currentIdentity.identity());
        assertThat(localId).isNotNull();
        assertThat(users.resolveOrCreate(currentIdentity.identity())).isEqualTo(localId);

        UUID issuerA = users.resolveOrCreate(new Identity("https://issuer-a.example", "same-subject"));
        UUID issuerB = users.resolveOrCreate(new Identity("https://issuer-b.example", "same-subject"));
        assertThat(issuerA).isNotEqualTo(issuerB);
        assertThat(users.resolveOrCreate(new Identity("https://issuer-a.example", "same-subject")))
                .isEqualTo(issuerA);

        Identity concurrentIdentity = new Identity("https://issuer-concurrent.example", "same-subject");
        ExecutorService pool = Executors.newFixedThreadPool(12);
        CountDownLatch ready = new CountDownLatch(12);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<UUID>> resolutions = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                resolutions.add(pool.submit(() -> {
                    ready.countDown();
                    start.await();
                    return users.resolveOrCreate(concurrentIdentity);
                }));
            }
            ready.await();
            start.countDown();
            List<UUID> resolved = new ArrayList<>();
            for (Future<UUID> resolution : resolutions) {
                resolved.add(resolution.get());
            }
            assertThat(resolved).hasSize(12).doesNotContainNull().containsOnly(resolved.getFirst());
        } finally {
            pool.shutdownNow();
        }

        assertThat(countUsers()).isEqualTo(4);
    }

    @AfterAll
    static void dropIsolatedSchema() throws Exception {
        if (isolatedSchema == null) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(requiredEnvironment("MEMORA_TEST_JDBC_URL"),
                requiredEnvironment("MEMORA_TEST_JDBC_USERNAME"), requiredEnvironment("MEMORA_TEST_JDBC_PASSWORD"));
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS \"" + isolatedSchema + "\" CASCADE");
        }
    }

    private long countUsers() throws Exception {
        try (Connection connection = scopedConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT count(*) FROM users")) {
            result.next();
            return result.getLong(1);
        }
    }

    private Connection scopedConnection() throws Exception {
        Connection connection = DriverManager.getConnection(schemaJdbcUrl(),
                requiredEnvironment("MEMORA_TEST_JDBC_USERNAME"), requiredEnvironment("MEMORA_TEST_JDBC_PASSWORD"));
        connection.createStatement().execute("SET search_path TO \"" + schemaName() + "\"");
        return connection;
    }

    private static String schemaJdbcUrl() {
        String baseUrl = requiredEnvironment("MEMORA_TEST_JDBC_URL");
        String parameterSeparator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + parameterSeparator + "currentSchema=" + schemaName();
    }

    private static String schemaName() {
        if (isolatedSchema == null) {
            synchronized (UserIdentityResolutionPostgresTest.class) {
                if (isolatedSchema == null) {
                    isolatedSchema = "memora_identity_red_" + UUID.randomUUID().toString().replace("-", "");
                    try (Connection connection = DriverManager.getConnection(requiredEnvironment("MEMORA_TEST_JDBC_URL"),
                            requiredEnvironment("MEMORA_TEST_JDBC_USERNAME"),
                            requiredEnvironment("MEMORA_TEST_JDBC_PASSWORD"));
                         Statement statement = connection.createStatement()) {
                        statement.execute("CREATE SCHEMA \"" + isolatedSchema + "\"");
                    } catch (Exception failure) {
                        isolatedSchema = null;
                        throw new IllegalStateException("Could not create isolated identity test schema.", failure);
                    }
                }
            }
        }
        return isolatedSchema;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set " + name + " to run the real PostgreSQL identity test.");
        }
        return value;
    }
}
