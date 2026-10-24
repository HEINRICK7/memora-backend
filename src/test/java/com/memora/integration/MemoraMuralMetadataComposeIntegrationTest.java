package com.memora.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Black-box RED acceptance tests for MEM-004-AC01..AC12 and AC14..AC16 using Compose API and PostgreSQL. */
@EnabledIfEnvironmentVariable(named = "MEMORA_MURAL_METADATA_INTEGRATION_TESTS", matches = "true")
class MemoraMuralMetadataComposeIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String api = System.getenv().getOrDefault("MEMORA_API_BASE_URL", "http://localhost:8080");
    private final List<UUID> createdMurals = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> createdForeignOwners = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void removeOnlyFixturesCreatedByThisTest() throws Exception {
        String jdbcUrl = System.getenv("MEMORA_TEST_JDBC_URL");
        String username = System.getenv("MEMORA_TEST_JDBC_USERNAME");
        String password = System.getenv("MEMORA_TEST_JDBC_PASSWORD");
        if (jdbcUrl == null || username == null || password == null) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            try (PreparedStatement deleteMural = connection.prepareStatement("DELETE FROM murals WHERE id = ?")) {
                for (UUID id : createdMurals) {
                    deleteMural.setObject(1, id);
                    deleteMural.executeUpdate();
                }
            }
            try (PreparedStatement deleteOwner = connection.prepareStatement("DELETE FROM users WHERE id = ?")) {
                for (UUID id : createdForeignOwners) {
                    deleteOwner.setObject(1, id);
                    deleteOwner.executeUpdate();
                }
            }
        }
    }

    @Test
    void AC01_AC02_AC03_AC04_updatesOnlyPresentFieldsAndPersistsTheNewRepresentation() throws Exception {
        JsonNode created = createDraft("Keep description", "Original description");
        UUID muralId = UUID.fromString(created.path("id").asText());

        HttpResponse<String> titlePatch = patch(muralId, "{\"title\":\"  New title  \"}", "\"1\"");
        assertThat(titlePatch.statusCode()).as(titlePatch.body()).isEqualTo(200);
        JsonNode updated = JSON.readTree(titlePatch.body());
        assertThat(updated.path("title").asText()).isEqualTo("New title");
        assertThat(updated.path("description").asText()).isEqualTo("Original description");
        assertThat(updated.path("status").asText()).isEqualTo("DRAFT");
        assertThat(updated.path("version").asInt()).isEqualTo(2);
        assertThat(updated.path("createdAt").asText()).isEqualTo(created.path("createdAt").asText());
        assertThat(updated.path("updatedAt").asText()).isNotEqualTo(created.path("updatedAt").asText());
        assertPrivatePatchHeaders(titlePatch, "\"2\"");

        HttpResponse<String> descriptionPatch = patch(muralId,
                "{\"description\":\"  New description  \"}", "\"2\"");
        assertThat(descriptionPatch.statusCode()).as(descriptionPatch.body()).isEqualTo(200);
        JsonNode descriptionUpdated = JSON.readTree(descriptionPatch.body());
        assertThat(descriptionUpdated.path("title").asText()).isEqualTo("New title");
        assertThat(descriptionUpdated.path("description").asText()).isEqualTo("New description");

        HttpResponse<String> clearDescription = patch(muralId, "{\"description\":null}", "\"3\"");
        assertThat(clearDescription.statusCode()).as(clearDescription.body()).isEqualTo(200);
        assertThat(JSON.readTree(clearDescription.body()).path("description").isNull()).isTrue();

        HttpResponse<String> persisted = get("/api/v1/murals/" + muralId);
        assertThat(persisted.statusCode()).as(persisted.body()).isEqualTo(200);
        assertThat(JSON.readTree(persisted.body())).isEqualTo(JSON.readTree(clearDescription.body()));
    }

    @Test
    void AC05_semanticallyUnchangedPatchDoesNotAdvanceVersionOrUpdatedAt() throws Exception {
        JsonNode created = createDraft("Same title", "Same description");
        UUID muralId = UUID.fromString(created.path("id").asText());
        HttpResponse<String> before = get("/api/v1/murals/" + muralId);
        assertThat(before.statusCode()).as(before.body()).isEqualTo(200);

        HttpResponse<String> noOp = patch(muralId,
                "{\"title\":\"  Same title  \",\"description\":\"\\u2003Same description\\u2003\"}",
                before.headers().firstValue("ETag").orElse("\"1\""));

        assertThat(noOp.statusCode()).as(noOp.body()).isEqualTo(200);
        JsonNode after = JSON.readTree(noOp.body());
        JsonNode beforeBody = JSON.readTree(before.body());
        assertThat(after.path("version").asInt()).isEqualTo(beforeBody.path("version").asInt());
        assertThat(after.path("updatedAt").asText()).isEqualTo(beforeBody.path("updatedAt").asText());
        assertThat(noOp.headers().firstValue("ETag")).isEqualTo(before.headers().firstValue("ETag"));
    }

    @Test
    void AC06_invalidTitleAndDescriptionReturnFieldSpecific422WithoutChangingTheMural() throws Exception {
        JsonNode created = createDraft("Safe title", "Safe description");
        UUID muralId = UUID.fromString(created.path("id").asText());
        String secret = "private-invalid-text-marker";

        assertValidationPatch(muralId, "{\"title\":\" \u2003 \"}", "title", secret, "\"1\"");
        assertValidationPatch(muralId, "{\"title\":null}", "title", secret, "\"1\"");
        assertValidationPatch(muralId, "{\"title\":\"" + "x".repeat(101) + "\"}", "title", secret, "\"1\"");
        assertValidationPatch(muralId, "{\"description\":\"" + secret + "x".repeat(501) + "\"}",
                "description", secret, "\"1\"");

        HttpResponse<String> persisted = get("/api/v1/murals/" + muralId);
        JsonNode finalState = JSON.readTree(persisted.body());
        assertThat(finalState.path("title").asText()).isEqualTo("Safe title");
        assertThat(finalState.path("description").asText()).isEqualTo("Safe description");
        assertThat(finalState.path("version").asInt()).isEqualTo(1);
    }

    @Test
    void AC07_rejectsEmptyNonObjectAndUnknownPatchBodies() throws Exception {
        UUID muralId = UUID.fromString(createDraft("Safe title", null).path("id").asText());

        assertProblem(patch(muralId, "{}", "\"1\""), 400);
        assertProblem(patch(muralId, "[]", "\"1\""), 400);
        HttpResponse<String> unknown = patch(muralId, "{\"unknown\":\"body-secret\"}", "\"1\"");
        assertProblem(unknown, 400);
        assertThat(unknown.body()).doesNotContain("body-secret");
    }

    @Test
    void AC08_AC09_AC15_requiresWellFormedCurrentIfMatchAndReturnsStableErrors() throws Exception {
        UUID muralId = UUID.fromString(createDraft("Safe title", null).path("id").asText());

        JsonNode missing = assertProblem(patch(muralId, "{\"title\":\"New title\"}", null), 428);
        assertThat(missing.path("code").asText()).isEqualTo("precondition_required");

        JsonNode malformed = assertProblem(patch(muralId, "{\"title\":\"New title\"}", "\"not-a-version\""), 400);
        assertThat(malformed.path("code").asText()).isEqualTo("invalid_if_match");

        JsonNode stale = assertProblem(patch(muralId, "{\"title\":\"New title\"}", "\"9\""), 409);
        assertThat(stale.path("code").asText()).isEqualTo("stale_mural_version");
        JsonNode persisted = JSON.readTree(get("/api/v1/murals/" + muralId).body());
        assertThat(persisted.path("title").asText()).isEqualTo("Safe title");
        assertThat(persisted.path("version").asInt()).isEqualTo(1);
    }

    @Test
    void AC10_concurrentUpdatesWithOneETagDoNotSilentlyOverwrite() throws Exception {
        UUID muralId = UUID.fromString(createDraft("Concurrent original", null).path("id").asText());
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<HttpResponse<String>> first = pool.submit(() -> concurrentPatch(ready, start, muralId, "First"));
            Future<HttpResponse<String>> second = pool.submit(() -> concurrentPatch(ready, start, muralId, "Second"));
            ready.await();
            start.countDown();
            HttpResponse<String> firstResponse = first.get();
            HttpResponse<String> secondResponse = second.get();

            assertThat(List.of(firstResponse.statusCode(), secondResponse.statusCode()))
                    .containsExactlyInAnyOrder(200, 409);
            HttpResponse<String> persisted = get("/api/v1/murals/" + muralId);
            JsonNode current = JSON.readTree(persisted.body());
            assertThat(current.path("title").asText()).isIn("First", "Second");
            assertThat(current.path("version").asInt()).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void AC11_foreignAndMissingMuralsHaveTheSameNotFoundResponse() throws Exception {
        ForeignMural fixture = insertForeignMural();
        UUID missingId = UUID.randomUUID();

        HttpResponse<String> foreign = patch(fixture.muralId(), "{\"title\":\"Change\"}", "\"1\"");
        HttpResponse<String> missing = patch(missingId, "{\"title\":\"Change\"}", "\"1\"");
        JsonNode foreignProblem = assertProblem(foreign, 404);
        JsonNode missingProblem = assertProblem(missing, 404);
        assertThat(foreignProblem.path("code").asText()).isEqualTo(missingProblem.path("code").asText());
        assertThat(foreignProblem.path("detail").asText()).isEqualTo(missingProblem.path("detail").asText());
        assertThat(foreign.body()).doesNotContain(fixture.title(), fixture.ownerId().toString());
    }

    @Test
    void AC12_publishedAndArchivedMuralsCannotBeEdited() throws Exception {
        for (String status : List.of("PUBLISHED", "ARCHIVED")) {
            UUID muralId = insertLocalMuralWithStatus(status);
            JsonNode problem = assertProblem(patch(muralId, "{\"title\":\"Change\"}", "\"1\""), 409);
            assertThat(problem.path("code").asText()).isEqualTo("mural_not_editable");
            assertThat(readMuralTitle(muralId)).isEqualTo("status-fixture-" + status);
        }
    }

    @Test
    void AC14_detailGetEmitsStrongETagMatchingThePublicVersion() throws Exception {
        UUID muralId = UUID.fromString(createDraft("ETag title", null).path("id").asText());

        HttpResponse<String> detail = get("/api/v1/murals/" + muralId);

        assertThat(detail.statusCode()).as(detail.body()).isEqualTo(200);
        JsonNode body = JSON.readTree(detail.body());
        assertThat(detail.headers().firstValue("ETag")).contains("\"" + body.path("version").asInt() + "\"");
        assertThat(detail.headers().firstValue("Cache-Control")).contains("private, no-store");
    }

    @Test
    void AC16_corsAllowsConfiguredPatchAndIfMatchAndExposesEtagOnlyToApprovedOrigins() throws Exception {
        UUID muralId = UUID.fromString(createDraft("CORS title", null).path("id").asText());
        String configuredOrigins = System.getenv().getOrDefault("MEMORA_CORS_ALLOWED_ORIGINS",
                "http://localhost:3000,http://127.0.0.1:3000");
        String allowedOrigin = configuredOrigins.split(",")[0].trim();

        HttpResponse<String> preflight = preflight(muralId, allowedOrigin);
        assertThat(preflight.statusCode()).as(preflight.body()).isBetween(200, 299);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).contains(allowedOrigin);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Methods").orElse("")).contains("PATCH");
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Headers").orElse("").toLowerCase())
                .contains("if-match");

        HttpResponse<String> detail = get("/api/v1/murals/" + muralId, allowedOrigin);
        assertThat(detail.headers().firstValue("Access-Control-Expose-Headers").orElse("").toLowerCase())
                .contains("etag");

        HttpResponse<String> forbidden = preflight(muralId, "https://not-allowed.invalid");
        assertThat(forbidden.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    private JsonNode createDraft(String title, String description) throws Exception {
        String json = "{\"title\":\"" + title + "\",\"description\":"
                + (description == null ? "null" : "\"" + description + "\"") + "}";
        HttpResponse<String> response = post("/api/v1/murals", json);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(api + path))
                        .header("Content-Type", "application/json")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 201) {
            UUID id = UUID.fromString(JSON.readTree(response.body()).path("id").asText());
            if (!createdMurals.contains(id)) {
                createdMurals.add(id);
            }
        }
        return response;
    }

    private HttpResponse<String> patch(UUID muralId, String body, String ifMatch) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(api + "/api/v1/murals/" + muralId))
                .header("Content-Type", "application/json");
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return HTTP.send(request.method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(api + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String origin) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(api + path)).header("Origin", origin).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> preflight(UUID muralId, String origin) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(api + "/api/v1/murals/" + muralId))
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "PATCH")
                        .header("Access-Control-Request-Headers", "If-Match, Content-Type")
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> concurrentPatch(CountDownLatch ready, CountDownLatch start,
                                                 UUID muralId, String title) throws Exception {
        ready.countDown();
        start.await();
        return patch(muralId, "{\"title\":\"" + title + "\"}", "\"1\"");
    }

    private void assertPrivatePatchHeaders(HttpResponse<String> response, String etag) {
        assertThat(response.headers().firstValue("ETag")).contains(etag);
        assertThat(response.headers().firstValue("Cache-Control")).contains("private, no-store");
    }

    private void assertValidationPatch(UUID muralId, String body, String field, String secret, String etag)
            throws Exception {
        HttpResponse<String> response = patch(muralId, body, etag);
        JsonNode problem = assertProblem(response, 422);
        assertThat(problem.path("field").asText()).isEqualTo(field);
        assertThat(problem.path("code").asText()).isNotBlank();
        assertThat(response.body()).doesNotContain(secret);
    }

    private JsonNode assertProblem(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
        JsonNode problem = JSON.readTree(response.body());
        assertThat(problem.path("status").asInt()).isEqualTo(status);
        assertThat(problem.path("code").asText()).isNotBlank();
        assertThat(problem.path("traceId").asText()).isNotBlank();
        return problem;
    }

    private ForeignMural insertForeignMural() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID muralId = UUID.randomUUID();
        String title = "foreign-private-" + UUID.randomUUID();
        try (Connection connection = databaseConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement user = connection.prepareStatement(
                    "INSERT INTO users (id, identity_issuer, identity_subject) VALUES (?, ?, ?)")) {
                user.setObject(1, ownerId);
                user.setString(2, "https://mem004.acceptance.invalid");
                user.setString(3, "foreign-" + ownerId);
                user.executeUpdate();
            }
            try (PreparedStatement mural = connection.prepareStatement("""
                    INSERT INTO murals (id, owner_id, idempotency_key, request_hash, title, description,
                                        status, lock_version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, NULL, 'DRAFT', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """)) {
                mural.setObject(1, muralId);
                mural.setObject(2, ownerId);
                mural.setObject(3, UUID.randomUUID());
                mural.setString(4, "a".repeat(64));
                mural.setString(5, title);
                mural.executeUpdate();
            }
            connection.commit();
        }
        createdMurals.add(muralId);
        createdForeignOwners.add(ownerId);
        return new ForeignMural(ownerId, muralId, title);
    }

    private UUID insertLocalMuralWithStatus(String status) throws Exception {
        UUID muralId = UUID.randomUUID();
        try (Connection connection = databaseConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO murals (id, owner_id, idempotency_key, request_hash, title, description,
                                         status, lock_version, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, NULL, ?, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                     """)) {
            insert.setObject(1, muralId);
            insert.setObject(2, localOwnerId());
            insert.setObject(3, UUID.randomUUID());
            insert.setString(4, "b".repeat(64));
            insert.setString(5, "status-fixture-" + status);
            insert.setString(6, status);
            insert.executeUpdate();
        }
        createdMurals.add(muralId);
        return muralId;
    }

    private String readMuralTitle(UUID muralId) throws Exception {
        try (Connection connection = databaseConnection();
             PreparedStatement query = connection.prepareStatement("SELECT title FROM murals WHERE id = ?")) {
            query.setObject(1, muralId);
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private UUID localOwnerId() throws Exception {
        try (Connection connection = databaseConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT id FROM users
                     WHERE identity_issuer = 'urn:memora:local'
                       AND identity_subject = 'local-development-owner'
                     """)) {
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getObject(1, UUID.class);
            }
        }
    }

    private Connection databaseConnection() throws Exception {
        return DriverManager.getConnection(System.getenv("MEMORA_TEST_JDBC_URL"),
                System.getenv("MEMORA_TEST_JDBC_USERNAME"), System.getenv("MEMORA_TEST_JDBC_PASSWORD"));
    }

    private record ForeignMural(UUID ownerId, UUID muralId, String title) { }
}
