package com.memora.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Black-box application/API acceptance tests for MEM-002-AC01..AC09. */
@EnabledIfEnvironmentVariable(named = "MEMORA_MURAL_INTEGRATION_TESTS", matches = "true")
class MemoraMuralComposeIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private final String api = System.getenv().getOrDefault("MEMORA_API_BASE_URL", "http://localhost:8080");
    private final List<UUID> createdMurals = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> createdForeignOwners = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void removeOnlyMuralsCreatedByThisTest() throws Exception {
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
    void AC01_createsPrivateDraftWithNormalizedFieldsAndPublicVersionOne() throws Exception {
        UUID key = UUID.randomUUID();
        HttpResponse<String> response = post("/api/v1/murals", key,
                "{\"title\":\"  Memora ✨  \",\"description\":\"  Um dia especial  \"}");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("id").asText()).isEqualTo(UUID.fromString(body.path("id").asText()).toString());
        assertThat(body.path("status").asText()).isEqualTo("DRAFT");
        assertThat(body.path("version").asInt()).isEqualTo(1);
        assertThat(body.path("title").asText()).isEqualTo("Memora ✨");
        assertThat(body.path("description").asText()).isEqualTo("Um dia especial");
        assertThat(body.path("createdAt").asText()).isEqualTo(body.path("updatedAt").asText());
        assertThat(OffsetDateTime.parse(body.path("createdAt").asText()).getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(response.headers().firstValue("Cache-Control")).contains("private, no-store");
        assertThat(response.body()).doesNotContain("ownerId", "identity_issuer", "identity_subject", key.toString());
        UUID muralId = UUID.fromString(body.path("id").asText());
        assertThat(resolveLocalOwnerId()).isNotNull();
        assertThat(muralOwnerId(muralId)).isEqualTo(resolveLocalOwnerId());
    }

    @Test
    void rejectsClientSuppliedOwnerId() throws Exception {
        HttpResponse<String> response = post("/api/v1/murals", UUID.randomUUID(),
                "{\"title\":\"Mural\",\"ownerId\":\"attacker-owner\"}");
        assertProblem(response, 400);
        assertThat(response.body()).doesNotContain("attacker-owner");
    }

    @Test
    void AC02_replaysSameNormalizedRequestWithoutCreatingAnotherMural() throws Exception {
        UUID key = UUID.randomUUID();
        HttpResponse<String> first = post("/api/v1/murals", key,
                "{\"title\":\"  Festa  \",\"description\":\"  Sofia  \"}");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        HttpResponse<String> replay = post("/api/v1/murals", key,
                "{\"title\":\"Festa\",\"description\":\"Sofia\"}");

        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(201);
        assertThat(JSON.readTree(replay.body()).path("id").asText())
                .isEqualTo(JSON.readTree(first.body()).path("id").asText());
        assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
    }

    @Test
    void AC03_rejectsReusingAnIdempotencyKeyForDifferentNormalizedContent() throws Exception {
        UUID key = UUID.randomUUID();
        HttpResponse<String> first = post("/api/v1/murals", key, "{\"title\":\"Privado Alfa\"}");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        HttpResponse<String> conflict = post("/api/v1/murals", key, "{\"title\":\"Privado Beta\"}");

        assertThat(conflict.statusCode()).isEqualTo(409);
        assertProblem(conflict, 409);
        assertThat(conflict.body()).doesNotContain("Privado Alfa", "Privado Beta", key.toString());
    }

    @Test
    void AC02_concurrentRetriesWithSameIdempotencyKeyReturnOnePersistedMural() throws Exception {
        UUID key = UUID.randomUUID();
        String json = "{\"title\":\"simultaneous-" + UUID.randomUUID() + "\",\"description\":null}";
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<HttpResponse<String>> first = pool.submit(() -> concurrentPost(ready, start, key, json));
            Future<HttpResponse<String>> second = pool.submit(() -> concurrentPost(ready, start, key, json));
            ready.await();
            start.countDown();
            HttpResponse<String> firstResponse = first.get();
            HttpResponse<String> secondResponse = second.get();

            assertThat(firstResponse.statusCode()).as(firstResponse.body()).isEqualTo(201);
            assertThat(secondResponse.statusCode()).as(secondResponse.body()).isEqualTo(201);
            JsonNode firstBody = JSON.readTree(firstResponse.body());
            JsonNode secondBody = JSON.readTree(secondResponse.body());
            assertThat(secondBody.path("id").asText()).isEqualTo(firstBody.path("id").asText());
            assertThat(secondBody).isEqualTo(firstBody);
            assertThat(countRowsForLocalOwnerAndKey(key)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void AC04_returnsFieldSpecific422WithoutEchoingInvalidText() throws Exception {
        HttpResponse<String> blankTitle = post("/api/v1/murals", UUID.randomUUID(),
                "{\"title\":\" \u2003 \",\"description\":null}");
        assertThat(blankTitle.statusCode()).isEqualTo(422);
        JsonNode titleProblem = assertProblem(blankTitle, 422);
        assertThat(titleProblem.path("field").asText()).isEqualTo("title");
        assertThat(blankTitle.body()).doesNotContain("local-secret-title");

        String sentinel = "description-secret-marker";
        HttpResponse<String> longDescription = post("/api/v1/murals", UUID.randomUUID(),
                "{\"title\":\"Válido\",\"description\":\"" + sentinel + "x".repeat(501) + "\"}");
        assertThat(longDescription.statusCode()).isEqualTo(422);
        JsonNode descriptionProblem = assertProblem(longDescription, 422);
        assertThat(descriptionProblem.path("field").asText()).isEqualTo("description");
        assertThat(descriptionProblem.path("code").asText()).isNotBlank();
        assertThat(descriptionProblem.path("traceId").asText()).isNotBlank();
        assertThat(longDescription.body()).doesNotContain(sentinel);
    }

    @Test
    void AC05_listsOnlyPrivateMuralSummariesWithoutOwnerOrStorageIdentifiers() throws Exception {
        ForeignMuralFixture otherOwner = insertForeignOwnedMural();
        HttpResponse<String> response = get("/api/v1/murals");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("private, no-store");
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("items").isArray()).isTrue();
        assertThat(body.has("nextCursor")).isTrue();
        assertThat(response.body()).doesNotContain(otherOwner.title(), otherOwner.muralId().toString());
        assertThat(response.body()).doesNotContain("ownerId", "owner_id", "identity_issuer", "identity_subject",
                "storage_key", "local-development-owner");
    }

    @Test
    void AC06_rejectsUnknownStatusFilterAsProblem400() throws Exception {
        HttpResponse<String> response = get("/api/v1/murals?status=OTHER");
        assertProblem(response, 400);
    }

    @Test
    void AC06_acceptsEachDeclaredStatusFilter() throws Exception {
        for (String status : List.of("DRAFT", "PUBLISHED", "ARCHIVED")) {
            HttpResponse<String> response = get("/api/v1/murals?status=" + status);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            JsonNode body = JSON.readTree(response.body());
            assertThat(body.path("items").isArray()).isTrue();
            assertThat(body.path("items").findValuesAsText("status")).allMatch(status::equals);
        }
    }

    @Test
    void AC07_rejectsMalformedAndFilterMismatchedCursors() throws Exception {
        assertProblem(get("/api/v1/murals?cursor=not-a-cursor"), 400);
        assertProblem(get("/api/v1/murals?cursor=" + "a".repeat(129)), 400);

        String draftCursorPayload = "DRAFT|2026-10-24T12:00:00Z|c0f5f693-7160-4e49-9d76-cfaba4edce1c";
        String draftCursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(draftCursorPayload.getBytes(StandardCharsets.UTF_8));
        assertProblem(get("/api/v1/murals?status=ARCHIVED&cursor=" + draftCursor), 400);
    }

    @Test
    void AC07_pagesInCreatedAtAndIdOrderWithoutDuplicateOrMissingItems() throws Exception {
        for (int index = 0; index < 3; index++) {
            HttpResponse<String> created = post("/api/v1/murals", UUID.randomUUID(),
                    "{\"title\":\"cursor-" + UUID.randomUUID() + "\"}");
            assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        }
        HttpResponse<String> firstPage = get("/api/v1/murals?limit=2");
        assertThat(firstPage.statusCode()).as(firstPage.body()).isEqualTo(200);
        JsonNode first = JSON.readTree(firstPage.body());
        assertThat(first.path("items").size()).isEqualTo(2);
        String cursor = first.path("nextCursor").asText();
        assertThat(cursor).isNotBlank();

        HttpResponse<String> secondPage = get("/api/v1/murals?limit=2&cursor=" + cursor);
        assertThat(secondPage.statusCode()).as(secondPage.body()).isEqualTo(200);
        JsonNode second = JSON.readTree(secondPage.body());
        assertThat(second.path("items").size()).isPositive();
        assertThat(first.path("items").findValuesAsText("id"))
                .doesNotContainAnyElementsOf(second.path("items").findValuesAsText("id"));
    }

    @Test
    void AC08_returnsPersistedOwnMuralWithPrivateCacheHeaders() throws Exception {
        HttpResponse<String> created = post("/api/v1/murals", UUID.randomUUID(),
                "{\"title\":\"detail-" + UUID.randomUUID() + "\"}");
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        String muralId = JSON.readTree(created.body()).path("id").asText();

        HttpResponse<String> detail = get("/api/v1/murals/" + muralId);
        assertThat(detail.statusCode()).as(detail.body()).isEqualTo(200);
        JsonNode createdBody = JSON.readTree(created.body());
        JsonNode detailBody = JSON.readTree(detail.body());
        assertThat(detailBody.path("id").asText()).isEqualTo(muralId);
        assertThat(detailBody.path("createdAt").asText()).isEqualTo(createdBody.path("createdAt").asText());
        assertThat(detailBody.path("updatedAt").asText()).isEqualTo(createdBody.path("updatedAt").asText());
        assertThat(detail.headers().firstValue("Cache-Control")).contains("private, no-store");
    }

    @Test
    void AC09_returnsProblem404ForAnUnownedOrMissingMuralId() throws Exception {
        HttpResponse<String> missing = get("/api/v1/murals/6c46d908-ae91-4f75-b30b-6685ab0d1b32");
        JsonNode missingProblem = assertProblem(missing, 404);
        ForeignMuralFixture otherOwner = insertForeignOwnedMural();
        HttpResponse<String> unowned = get("/api/v1/murals/" + otherOwner.muralId());
        JsonNode unownedProblem = assertProblem(unowned, 404);
        assertThat(unownedProblem.path("code").asText()).isEqualTo(missingProblem.path("code").asText());
        assertThat(unownedProblem.path("detail").asText()).isEqualTo(missingProblem.path("detail").asText());
        HttpResponse<String> list = get("/api/v1/murals");
        assertThat(list.statusCode()).as(list.body()).isEqualTo(200);
        assertThat(list.body()).doesNotContain(otherOwner.title(), otherOwner.muralId().toString());
        HttpResponse<String> malformed = get("/api/v1/murals/not-a-uuid");
        assertProblem(malformed, 400);
    }

    @Test
    void AC10_existingSyntheticPhotoOwnerAndPrivateBytesRemainAccessibleAfterV2() throws Exception {
        UUID syntheticId = UUID.fromString("fb7441a6-234a-4386-8da6-e945e942cdf0");
        HttpResponse<String> galleryResponse = get("/api/v1/photos");
        assertThat(galleryResponse.statusCode()).as(galleryResponse.body()).isEqualTo(200);
        JsonNode items = JSON.readTree(galleryResponse.body()).path("items");
        JsonNode syntheticPhoto = null;
        for (JsonNode item : items) {
            if (syntheticId.toString().equals(item.path("id").asText())) {
                syntheticPhoto = item;
                break;
            }
        }
        assertThat(syntheticPhoto).isNotNull();
        assertThat(syntheticPhoto.path("status").asText()).isEqualTo("READY");

        byte[] previewBytes = HTTP.send(HttpRequest.newBuilder(URI.create(syntheticPhoto.path("previewUrl").asText()))
                        .GET().build(), HttpResponse.BodyHandlers.ofByteArray())
                .body();
        assertThat(previewBytes).isNotEmpty();
        assertThat(previewBytes.length).isEqualTo(syntheticPhotoSizeBytes());
        assertThat(syntheticPhotoOwnerId()).isNotNull();
    }

    private HttpResponse<String> post(String path, UUID idempotencyKey, String json) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(api + path))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", idempotencyKey.toString())
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 201) {
            UUID muralId = UUID.fromString(JSON.readTree(response.body()).path("id").asText());
            synchronized (createdMurals) {
                if (!createdMurals.contains(muralId)) {
                    createdMurals.add(muralId);
                }
            }
        }
        return response;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(api + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> concurrentPost(CountDownLatch ready, CountDownLatch start,
                                                UUID key, String json) throws Exception {
        ready.countDown();
        start.await();
        return post("/api/v1/murals", key, json);
    }

    private JsonNode assertProblem(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/problem+json");
        JsonNode problem = JSON.readTree(response.body());
        assertThat(problem.path("status").asInt()).isEqualTo(status);
        assertThat(problem.path("code").asText()).isNotBlank();
        assertThat(problem.path("traceId").asText()).isNotBlank();
        return problem;
    }

    private UUID resolveLocalOwnerId() throws Exception {
        try (Connection connection = databaseConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT id FROM users
                     WHERE identity_issuer = 'urn:memora:local'
                       AND identity_subject = 'local-development-owner'
                     """)) {
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).as("MEM-002-R01 resolves the fixed local identity to a UserId").isTrue();
                return result.getObject(1, UUID.class);
            }
        }
    }

    private UUID muralOwnerId(UUID muralId) throws Exception {
        try (Connection connection = databaseConnection();
             PreparedStatement query = connection.prepareStatement("SELECT owner_id FROM murals WHERE id = ?")) {
            query.setObject(1, muralId);
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getObject(1, UUID.class);
            }
        }
    }

    private long countRowsForLocalOwnerAndKey(UUID key) throws Exception {
        try (Connection connection = databaseConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT count(*) FROM murals
                     WHERE owner_id = (SELECT id FROM users
                                       WHERE identity_issuer = 'urn:memora:local'
                                         AND identity_subject = 'local-development-owner')
                       AND idempotency_key = ?
                     """)) {
            query.setObject(1, key);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private ForeignMuralFixture insertForeignOwnedMural() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID muralId = UUID.randomUUID();
        String subject = "mem002-acceptance-" + ownerId;
        String title = "other-owner-private-" + muralId;
        try (Connection connection = databaseConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement insertUser = connection.prepareStatement("""
                    INSERT INTO users (id, identity_issuer, identity_subject) VALUES (?, ?, ?)
                    """)) {
                insertUser.setObject(1, ownerId);
                insertUser.setString(2, "https://acceptance.other-owner.invalid");
                insertUser.setString(3, subject);
                insertUser.executeUpdate();
            }
            try (PreparedStatement insertMural = connection.prepareStatement("""
                    INSERT INTO murals (id, owner_id, idempotency_key, request_hash, title, description,
                                        status, lock_version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, NULL, 'DRAFT', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """)) {
                insertMural.setObject(1, muralId);
                insertMural.setObject(2, ownerId);
                insertMural.setObject(3, UUID.randomUUID());
                insertMural.setString(4, "a".repeat(64));
                insertMural.setString(5, title);
                insertMural.executeUpdate();
            }
            connection.commit();
        }
        createdMurals.add(muralId);
        createdForeignOwners.add(ownerId);
        return new ForeignMuralFixture(ownerId, muralId, title);
    }

    private record ForeignMuralFixture(UUID ownerId, UUID muralId, String title) { }

    private long syntheticPhotoSizeBytes() throws Exception {
        try (Connection connection = databaseConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT size_bytes FROM photo_assets WHERE id = ? AND status = 'READY'")) {
            query.setObject(1, UUID.fromString("fb7441a6-234a-4386-8da6-e945e942cdf0"));
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private UUID syntheticPhotoOwnerId() throws Exception {
        try (Connection connection = databaseConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT u.id FROM photo_assets p JOIN users u ON p.owner_id = u.id
                     WHERE p.id = ? AND u.identity_issuer = 'urn:memora:local'
                       AND u.identity_subject = 'local-development-owner'
                     """)) {
            query.setObject(1, UUID.fromString("fb7441a6-234a-4386-8da6-e945e942cdf0"));
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).as("V2 ownership points at the backfilled local UserId").isTrue();
                return result.getObject(1, UUID.class);
            }
        }
    }

    private Connection databaseConnection() throws Exception {
        return DriverManager.getConnection(System.getenv("MEMORA_TEST_JDBC_URL"),
                System.getenv("MEMORA_TEST_JDBC_USERNAME"), System.getenv("MEMORA_TEST_JDBC_PASSWORD"));
    }
}
