package com.memora.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real Compose acceptance for MEM-006 and the MEM-001 asset-deletion guard. */
@EnabledIfEnvironmentVariable(named = "MEMORA_MURAL_PHOTO_INTEGRATION_TESTS", matches = "true")
class MemoraMuralPhotoAssociationComposeIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String api = System.getenv().getOrDefault("MEMORA_API_BASE_URL", "http://localhost:8080");
    private final String bucket = System.getenv().getOrDefault("MINIO_BUCKET", "memora-private");
    private final List<UUID> createdMurals = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> createdPhotos = Collections.synchronizedList(new ArrayList<>());
    private final List<String> createdStorageKeys = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> createdForeignOwners = Collections.synchronizedList(new ArrayList<>());
    private final List<UUID> createdForeignPhotos = Collections.synchronizedList(new ArrayList<>());

    @AfterEach
    void removeOnlyFixturesCreatedByThisTest() throws Exception {
        String jdbcUrl = System.getenv("MEMORA_TEST_JDBC_URL");
        String username = System.getenv("MEMORA_TEST_JDBC_USERNAME");
        String password = System.getenv("MEMORA_TEST_JDBC_PASSWORD");
        if (jdbcUrl == null || username == null || password == null) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password)) {
            dropInjectedFailureTrigger(connection);
            boolean hasPhotoLinks = tableExists(connection, "mural_photos");
            if (hasPhotoLinks) {
                try (PreparedStatement deleteLink = connection.prepareStatement(
                        "DELETE FROM mural_photos WHERE mural_id = ? OR photo_id = ?")) {
                    for (UUID muralId : createdMurals) {
                        deleteLink.setObject(1, muralId);
                        deleteLink.setObject(2, new UUID(0, 0));
                        deleteLink.executeUpdate();
                    }
                    for (UUID photoId : createdPhotos) {
                        deleteLink.setObject(1, new UUID(0, 0));
                        deleteLink.setObject(2, photoId);
                        deleteLink.executeUpdate();
                    }
                    for (UUID photoId : createdForeignPhotos) {
                        deleteLink.setObject(1, new UUID(0, 0));
                        deleteLink.setObject(2, photoId);
                        deleteLink.executeUpdate();
                    }
                }
            }
            try (PreparedStatement deleteMural = connection.prepareStatement("DELETE FROM murals WHERE id = ?")) {
                for (UUID muralId : createdMurals) {
                    deleteMural.setObject(1, muralId);
                    deleteMural.executeUpdate();
                }
            }
            try (PreparedStatement deletePhoto = connection.prepareStatement("DELETE FROM photo_assets WHERE id = ?")) {
                for (UUID photoId : createdPhotos) {
                    deletePhoto.setObject(1, photoId);
                    deletePhoto.executeUpdate();
                }
            }
            try (PreparedStatement deletePhoto = connection.prepareStatement("DELETE FROM photo_assets WHERE id = ?")) {
                for (UUID photoId : createdForeignPhotos) {
                    deletePhoto.setObject(1, photoId);
                    deletePhoto.executeUpdate();
                }
            }
            try (PreparedStatement deleteOwner = connection.prepareStatement("DELETE FROM users WHERE id = ?")) {
                for (UUID ownerId : createdForeignOwners) {
                    deleteOwner.setObject(1, ownerId);
                    deleteOwner.executeUpdate();
                }
            }
        }

        for (UUID photoId : createdPhotos) {
            try {
                delete("/api/v1/photos/" + photoId);
            } catch (Exception ignored) {
                // Direct DB/object cleanup below is restricted to IDs created by this test.
            }
        }
        try (S3Client s3 = integrationS3Client()) {
            for (String key : createdStorageKeys) {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            }
        }
    }

    @Test
    void AC01_AC02_listsOnlyOwnedAttachedPhotosAndPresignsThroughPrivateMinio() throws Exception {
        UUID muralId = createDraft();
        ReadyPhoto photo = createReadyPhoto();
        HttpResponse<String> initial = get("/api/v1/murals/" + muralId);
        assertThat(initial.statusCode()).as(initial.body()).isEqualTo(200);

        HttpResponse<String> attached = attach(muralId, photo.photoId(), initial.headers().firstValue("ETag").orElseThrow());

        assertThat(attached.statusCode()).as(attached.body()).isEqualTo(201);
        assertThat(attached.headers().firstValue("ETag")).contains("\"2\"");
        assertThat(attached.headers().firstValue("Cache-Control")).contains("private, no-store");
        assertThat(attached.body()).doesNotContain("previewUrl", "X-Amz-Signature", photo.storageKey());

        HttpResponse<String> listed = get("/api/v1/murals/" + muralId + "/photos");
        assertThat(listed.statusCode()).as(listed.body()).isEqualTo(200);
        assertThat(listed.headers().firstValue("Cache-Control")).contains("private, no-store");
        JsonNode body = JSON.readTree(listed.body());
        assertThat(body.path("muralVersion").asInt()).isEqualTo(2);
        JsonNode item = body.path("items").get(0);
        assertThat(item.path("photoId").asText()).isEqualTo(photo.photoId().toString());
        assertThat(item.path("position").asInt()).isZero();
        assertThat(item.path("previewUrl").asText()).contains("X-Amz-Signature=");
        assertThat(item.path("previewExpiresAt").asText()).isNotBlank();
        assertThat(item.has("ownerId")).isFalse();
        assertThat(item.has("storageKey")).isFalse();

        URI signed = URI.create(item.path("previewUrl").asText());
        HttpResponse<byte[]> image = HTTP.send(HttpRequest.newBuilder(signed).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(image.statusCode()).isEqualTo(200);
        assertThat(image.body()).containsExactly(photo.bytes());
        URI unsigned = new URI(signed.getScheme(), signed.getAuthority(), signed.getPath(), null, null);
        assertThat(HTTP.send(HttpRequest.newBuilder(unsigned).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode()).isBetween(400, 499);

        HttpResponse<Void> allowedCors = corsPreflight(signed, "http://localhost:3300");
        assertThat(allowedCors.statusCode()).isBetween(200, 299);
        assertThat(allowedCors.headers().firstValue("Access-Control-Allow-Origin")).contains("http://localhost:3300");
        HttpResponse<Void> rejectedCors = corsPreflight(signed, "https://attacker.invalid");
        assertThat(rejectedCors.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void AC03_AC04_returnsSameNotFoundForForeignAndMissingResources() throws Exception {
        UUID muralId = createDraft();
        ForeignPhoto foreign = insertForeignPhoto();
        UUID missingPhoto = UUID.randomUUID();
        HttpResponse<String> foreignPhotoResponse = attach(muralId, foreign.photoId(), "\"1\"");
        HttpResponse<String> missingPhotoResponse = attach(muralId, missingPhoto, "\"1\"");
        assertSameProblem(foreignPhotoResponse, missingPhotoResponse, 404);
        assertThat(foreignPhotoResponse.body()).doesNotContain(foreign.ownerId().toString(), foreign.storageKey());

        ForeignMural foreignMural = insertForeignMural();
        HttpResponse<String> foreignMuralResponse = get("/api/v1/murals/" + foreignMural.muralId() + "/photos");
        HttpResponse<String> missingMuralResponse = get("/api/v1/murals/" + UUID.randomUUID() + "/photos");
        assertSameProblem(foreignMuralResponse, missingMuralResponse, 404);
        assertThat(foreignMuralResponse.body()).doesNotContain(foreignMural.ownerId().toString());
    }

    @Test
    void AC05_rejectsNonReadyPhotosWithoutChangingMural() throws Exception {
        UUID muralId = createDraft();
        UUID photoId = insertOwnedUploadingPhoto();

        HttpResponse<String> response = attach(muralId, photoId, "\"1\"");

        assertProblem(response, 409, "photo_not_ready");
        assertThat(JSON.readTree(get("/api/v1/murals/" + muralId).body()).path("version").asInt()).isEqualTo(1);
        assertThat(JSON.readTree(get("/api/v1/murals/" + muralId + "/photos").body()).path("items").isEmpty()).isTrue();
    }

    @Test
    void AC06_AC08_AC09_AC10_reordersAnExactPermutationWithDeferredPostgresUniqueness() throws Exception {
        UUID muralId = createDraft();
        ReadyPhoto first = createReadyPhoto();
        ReadyPhoto second = createReadyPhoto();
        ReadyPhoto third = createReadyPhoto();
        attach(muralId, first.photoId(), "\"1\"");
        attach(muralId, second.photoId(), "\"2\"");
        attach(muralId, third.photoId(), "\"3\"");

        HttpResponse<String> reordered = reorder(muralId, "\"4\"",
                List.of(third.photoId(), first.photoId(), second.photoId()));

        assertThat(reordered.statusCode()).as(reordered.body()).isEqualTo(200);
        assertThat(reordered.headers().firstValue("ETag")).contains("\"5\"");
        assertThat(reordered.body()).doesNotContain("previewUrl", "X-Amz-Signature");
        assertThat(position(muralId, third.photoId())).isZero();
        assertThat(position(muralId, first.photoId())).isEqualTo(1);
        assertThat(position(muralId, second.photoId())).isEqualTo(2);
        assertThat(deferredPositionConstraintExists()).isTrue();

        HttpResponse<String> invalid = reorder(muralId, "\"5\"",
                List.of(first.photoId(), first.photoId(), third.photoId()));
        assertProblem(invalid, 422, "invalid_photo_order");
        assertThat(position(muralId, third.photoId())).isZero();
        assertThat(JSON.readTree(get("/api/v1/murals/" + muralId).body()).path("version").asInt()).isEqualTo(5);

        HttpResponse<String> sameOrder = reorder(muralId, "\"5\"",
                List.of(third.photoId(), first.photoId(), second.photoId()));
        assertThat(sameOrder.statusCode()).isEqualTo(200);
        assertThat(sameOrder.headers().firstValue("ETag")).contains("\"5\"");
    }

    @Test
    void AC07_AC11_AC12_detachDoesNotDeleteAssetButPhotoDeleteIsGuarded() throws Exception {
        UUID muralId = createDraft();
        ReadyPhoto photo = createReadyPhoto();
        attach(muralId, photo.photoId(), "\"1\"");

        HttpResponse<String> blocked = delete("/api/v1/photos/" + photo.photoId());
        assertProblem(blocked, 409, "photo_attached");
        assertThat(photoStatus(photo.photoId())).isEqualTo("READY");
        assertObjectExists(photo.storageKey());

        HttpResponse<String> detached = deleteMuralPhoto(muralId, photo.photoId(), "\"2\"");
        assertThat(detached.statusCode()).isEqualTo(204);
        assertThat(detached.headers().firstValue("ETag")).contains("\"3\"");
        assertThat(photoStatus(photo.photoId())).isEqualTo("READY");
        assertObjectExists(photo.storageKey());

        HttpResponse<String> deleted = delete("/api/v1/photos/" + photo.photoId());
        assertThat(deleted.statusCode()).isEqualTo(204);
        assertThat(photoStatus(photo.photoId())).isEqualTo("DELETED");
        assertObjectMissing(photo.storageKey());
    }

    @Test
    void AC13_AC17_serializesAttachAgainstDeleteOnThePhotoRow() throws Exception {
        UUID muralId = createDraft();
        ReadyPhoto photo = createReadyPhoto();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<HttpResponse<String>> attach = pool.submit(() -> {
                ready.countDown();
                start.await();
                return attach(muralId, photo.photoId(), "\"1\"");
            });
            Future<HttpResponse<String>> delete = pool.submit(() -> {
                ready.countDown();
                start.await();
                return delete("/api/v1/photos/" + photo.photoId());
            });
            ready.await();
            start.countDown();
            HttpResponse<String> attached = attach.get();
            HttpResponse<String> deleted = delete.get();

            if (attached.statusCode() == 201) {
                assertProblem(deleted, 409, "photo_attached");
                assertThat(photoStatus(photo.photoId())).isEqualTo("READY");
                assertThat(linkCount(photo.photoId())).isEqualTo(1);
                assertObjectExists(photo.storageKey());
            } else {
                assertThat(attached.statusCode()).as(attached.body()).isEqualTo(409);
                assertProblem(attached, 409, "photo_not_ready");
                assertThat(deleted.statusCode()).as(deleted.body()).isEqualTo(204);
                assertThat(photoStatus(photo.photoId())).isEqualTo("DELETED");
                assertThat(linkCount(photo.photoId())).isZero();
                assertObjectMissing(photo.storageKey());
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void staleVersionAndInvalidHeadersDoNotChangeTheLinkSet() throws Exception {
        UUID muralId = createDraft();
        ReadyPhoto photo = createReadyPhoto();
        assertProblem(attach(muralId, photo.photoId(), null), 428, "precondition_required");
        assertProblem(attach(muralId, photo.photoId(), "\"bad\""), 400, "invalid_if_match");
        assertProblem(attach(muralId, photo.photoId(), "\"2\""), 409, "stale_mural_version");
        assertThat(linkCount(photo.photoId())).isZero();
    }

    @Test
    void AC19_corsAllowsConfiguredPutAndIfMatchWithoutAddingOrigins() throws Exception {
        UUID muralId = createDraft();
        String allowedOrigin = System.getenv().getOrDefault("MEMORA_CORS_ALLOWED_ORIGINS",
                "http://localhost:3300,http://127.0.0.1:3300").split(",")[0].trim();

        HttpResponse<String> allowed = apiPreflight(muralId, "PUT", allowedOrigin);
        assertThat(allowed.statusCode()).as(allowed.body()).isBetween(200, 299);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Origin")).contains(allowedOrigin);
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Methods").orElse("")).contains("PUT");
        assertThat(allowed.headers().firstValue("Access-Control-Allow-Headers").orElse("").toLowerCase())
                .contains("if-match");

        HttpResponse<String> rejected = apiPreflight(muralId, "PUT", "https://attacker.invalid");
        assertThat(rejected.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
    }

    @Test
    void AC16_postDatabaseFailureRollsBackLinksVersionAndUpdatedAt() throws Exception {
        UUID muralId = createDraft();
        ReadyPhoto photo = createReadyPhoto();
        MuralState before = muralState(muralId);
        installInjectedFailureTrigger(muralId);
        try {
            HttpResponse<String> response = attach(muralId, photo.photoId(), "\"1\"");

            assertSanitizedDependencyFailure(response);
            assertThat(linkedPhotoIds(muralId)).isEmpty();
            assertThat(muralState(muralId)).isEqualTo(before);
            assertThat(photoStatus(photo.photoId())).isEqualTo("READY");
            assertObjectExists(photo.storageKey());
        } finally {
            dropInjectedFailureTrigger();
        }
    }

    @Test
    void AC16_putDatabaseFailureRollsBackPositionsVersionAndUpdatedAt() throws Exception {
        UUID muralId = createDraft();
        ReadyPhoto first = createReadyPhoto();
        ReadyPhoto second = createReadyPhoto();
        attach(muralId, first.photoId(), "\"1\"");
        attach(muralId, second.photoId(), "\"2\"");
        List<UUID> linksBefore = linkedPhotoIds(muralId);
        MuralState muralBefore = muralState(muralId);
        installInjectedFailureTrigger(muralId);
        try {
            HttpResponse<String> response = reorder(muralId, "\"3\"",
                    List.of(second.photoId(), first.photoId()));

            assertSanitizedDependencyFailure(response);
            assertThat(linkedPhotoIds(muralId)).isEqualTo(linksBefore);
            assertThat(muralState(muralId)).isEqualTo(muralBefore);
        } finally {
            dropInjectedFailureTrigger();
        }
    }

    @Test
    void AC16_deleteDatabaseFailureRollsBackLinksPositionsVersionAndUpdatedAt() throws Exception {
        UUID muralId = createDraft();
        ReadyPhoto first = createReadyPhoto();
        ReadyPhoto second = createReadyPhoto();
        attach(muralId, first.photoId(), "\"1\"");
        attach(muralId, second.photoId(), "\"2\"");
        List<UUID> linksBefore = linkedPhotoIds(muralId);
        MuralState muralBefore = muralState(muralId);
        installInjectedFailureTrigger(muralId);
        try {
            HttpResponse<String> response = deleteMuralPhoto(muralId, first.photoId(), "\"3\"");

            assertSanitizedDependencyFailure(response);
            assertThat(linkedPhotoIds(muralId)).isEqualTo(linksBefore);
            assertThat(muralState(muralId)).isEqualTo(muralBefore);
            assertThat(photoStatus(first.photoId())).isEqualTo("READY");
            assertObjectExists(first.storageKey());
        } finally {
            dropInjectedFailureTrigger();
        }
    }

    private UUID createDraft() throws Exception {
        UUID key = UUID.randomUUID();
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(api + "/api/v1/murals"))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", key.toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"title\":\"MEM-006 " + key + "\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        UUID id = UUID.fromString(JSON.readTree(response.body()).path("id").asText());
        createdMurals.add(id);
        return id;
    }

    private ReadyPhoto createReadyPhoto() throws Exception {
        byte[] png = pngBytes();
        HttpResponse<String> intentResponse = post("/api/v1/uploads", "{\"fileName\":\"mem006.png\","
                + "\"contentType\":\"image/png\",\"sizeBytes\":" + png.length + "}");
        assertThat(intentResponse.statusCode()).as(intentResponse.body()).isEqualTo(201);
        JsonNode intent = JSON.readTree(intentResponse.body());
        Map<String, String> headers = new HashMap<>();
        intent.path("requiredHeaders").fields().forEachRemaining(field -> headers.put(field.getKey(), field.getValue().asText()));
        URI uploadUrl = URI.create(intent.path("uploadUrl").asText());
        String key = uploadUrl.getPath().replaceFirst("^/" + bucket + "/", "");
        createdStorageKeys.add(key);
        HttpRequest.Builder put = HttpRequest.newBuilder(uploadUrl).timeout(Duration.ofSeconds(20));
        headers.forEach(put::header);
        HttpResponse<String> uploaded = HTTP.send(put.PUT(HttpRequest.BodyPublishers.ofByteArray(png)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(uploaded.statusCode()).as(uploaded.body()).isBetween(200, 299);
        HttpResponse<String> completed = HTTP.send(HttpRequest.newBuilder(URI.create(api + "/api/v1/uploads/"
                        + intent.path("uploadId").asText() + "/complete"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(completed.statusCode()).as(completed.body()).isEqualTo(200);
        UUID photoId = UUID.fromString(JSON.readTree(completed.body()).path("id").asText());
        createdPhotos.add(photoId);
        return new ReadyPhoto(photoId, key, png);
    }

    private HttpResponse<String> attach(UUID muralId, UUID photoId, String etag) throws Exception {
        return jsonRequest("POST", "/api/v1/murals/" + muralId + "/photos", etag,
                "{\"photoId\":\"" + photoId + "\"}");
    }

    private HttpResponse<String> reorder(UUID muralId, String etag, List<UUID> photos) throws Exception {
        String ids = photos.stream().map(id -> "\"" + id + "\"").reduce((left, right) -> left + "," + right).orElse("");
        return jsonRequest("PUT", "/api/v1/murals/" + muralId + "/photos/order", etag,
                "{\"photoIds\":[" + ids + "]}");
    }

    private HttpResponse<String> deleteMuralPhoto(UUID muralId, UUID photoId, String etag) throws Exception {
        return request("DELETE", "/api/v1/murals/" + muralId + "/photos/" + photoId, etag, null);
    }

    private HttpResponse<String> delete(String path) throws Exception {
        return request("DELETE", path, null, null);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return request("GET", path, null, null);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return jsonRequest("POST", path, null, body);
    }

    private HttpResponse<String> jsonRequest(String method, String path, String etag, String body) throws Exception {
        return request(method, path, etag, body);
    }

    private HttpResponse<String> request(String method, String path, String etag, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(api + path));
        if (etag != null) request.header("If-Match", etag);
        if (body != null) request.header("Content-Type", "application/json");
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body);
        return HTTP.send(request.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<Void> corsPreflight(URI signedUrl, String origin) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(signedUrl)
                .header("Origin", origin)
                .header("Access-Control-Request-Method", "GET")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
    }

    private HttpResponse<String> apiPreflight(UUID muralId, String method, String origin) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(api + "/api/v1/murals/" + muralId + "/photos/order"))
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", method)
                        .header("Access-Control-Request-Headers", "If-Match, Content-Type")
                        .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private void assertSameProblem(HttpResponse<String> left, HttpResponse<String> right, int status) throws Exception {
        JsonNode leftProblem = assertProblem(left, status, null);
        JsonNode rightProblem = assertProblem(right, status, null);
        assertThat(leftProblem.path("code").asText()).isEqualTo(rightProblem.path("code").asText());
        assertThat(leftProblem.path("detail").asText()).isEqualTo(rightProblem.path("detail").asText());
    }

    private JsonNode assertProblem(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/problem+json");
        JsonNode body = JSON.readTree(response.body());
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("traceId").asText()).isNotBlank();
        if (code != null) assertThat(body.path("code").asText()).isEqualTo(code);
        return body;
    }

    private UUID insertOwnedUploadingPhoto() throws Exception {
        UUID photoId = UUID.randomUUID();
        try (Connection connection = databaseConnection();
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO photo_assets (id, owner_id, storage_key, content_type, size_bytes, status,
                                               created_at, intent_expires_at, width, height, ready_at, deleted_at, version)
                     VALUES (?, ?, ?, 'image/png', 32, 'UPLOADING', CURRENT_TIMESTAMP,
                             CURRENT_TIMESTAMP + INTERVAL '15 minutes', NULL, NULL, NULL, NULL, 0)
                     """)) {
            insert.setObject(1, photoId);
            insert.setObject(2, localOwnerId());
            insert.setString(3, "originals/private/" + photoId + ".png");
            insert.executeUpdate();
        }
        createdPhotos.add(photoId);
        return photoId;
    }

    private ForeignPhoto insertForeignPhoto() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID photoId = UUID.randomUUID();
        String storageKey = "originals/private/foreign-" + photoId + ".png";
        createdForeignOwners.add(ownerId);
        createdForeignPhotos.add(photoId);
        try (Connection connection = databaseConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement user = connection.prepareStatement(
                    "INSERT INTO users (id, identity_issuer, identity_subject) VALUES (?, ?, ?)")) {
                user.setObject(1, ownerId);
                user.setString(2, "https://mem006.foreign.invalid");
                user.setString(3, ownerId.toString());
                user.executeUpdate();
            }
            try (PreparedStatement photo = connection.prepareStatement("""
                    INSERT INTO photo_assets (id, owner_id, storage_key, content_type, size_bytes, status,
                                              created_at, intent_expires_at, width, height, ready_at, deleted_at, version)
                    VALUES (?, ?, ?, 'image/png', 32, 'READY', CURRENT_TIMESTAMP,
                            CURRENT_TIMESTAMP + INTERVAL '15 minutes', 1, 1, CURRENT_TIMESTAMP, NULL, 0)
                    """)) {
                photo.setObject(1, photoId);
                photo.setObject(2, ownerId);
                photo.setString(3, storageKey);
                photo.executeUpdate();
            }
            connection.commit();
        }
        return new ForeignPhoto(ownerId, photoId, storageKey);
    }

    private ForeignMural insertForeignMural() throws Exception {
        UUID ownerId = UUID.randomUUID();
        UUID muralId = UUID.randomUUID();
        createdForeignOwners.add(ownerId);
        createdMurals.add(muralId);
        try (Connection connection = databaseConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement user = connection.prepareStatement(
                    "INSERT INTO users (id, identity_issuer, identity_subject) VALUES (?, ?, ?)")) {
                user.setObject(1, ownerId);
                user.setString(2, "https://mem006.foreign.invalid");
                user.setString(3, ownerId.toString());
                user.executeUpdate();
            }
            try (PreparedStatement mural = connection.prepareStatement("""
                    INSERT INTO murals (id, owner_id, idempotency_key, request_hash, title, description,
                                        status, lock_version, created_at, updated_at)
                    VALUES (?, ?, ?, ?, 'foreign-private-mural', NULL, 'DRAFT', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    """)) {
                mural.setObject(1, muralId);
                mural.setObject(2, ownerId);
                mural.setObject(3, UUID.randomUUID());
                mural.setString(4, "f".repeat(64));
                mural.executeUpdate();
            }
            connection.commit();
        }
        return new ForeignMural(ownerId, muralId);
    }

    private int position(UUID muralId, UUID photoId) throws Exception {
        try (Connection connection = databaseConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT position FROM mural_photos WHERE mural_id = ? AND photo_id = ?")) {
            query.setObject(1, muralId);
            query.setObject(2, photoId);
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getInt(1);
            }
        }
    }

    private int linkCount(UUID photoId) throws Exception {
        try (Connection connection = databaseConnection();
             PreparedStatement query = connection.prepareStatement("SELECT count(*) FROM mural_photos WHERE photo_id = ?")) {
            query.setObject(1, photoId);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private List<UUID> linkedPhotoIds(UUID muralId) throws Exception {
        try (Connection connection = databaseConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT photo_id FROM mural_photos WHERE mural_id = ? ORDER BY position")) {
            query.setObject(1, muralId);
            try (ResultSet result = query.executeQuery()) {
                List<UUID> ids = new ArrayList<>();
                while (result.next()) ids.add(result.getObject(1, UUID.class));
                return ids;
            }
        }
    }

    private MuralState muralState(UUID muralId) throws Exception {
        try (Connection connection = databaseConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT lock_version, updated_at FROM murals WHERE id = ?")) {
            query.setObject(1, muralId);
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                Timestamp updatedAt = result.getTimestamp("updated_at");
                return new MuralState(result.getLong("lock_version"), updatedAt.toInstant());
            }
        }
    }

    private void installInjectedFailureTrigger(UUID muralId) throws Exception {
        dropInjectedFailureTrigger();
        try (Connection connection = databaseConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE OR REPLACE FUNCTION public.mem006_test_fail_mural_photos() RETURNS trigger
                    LANGUAGE plpgsql AS $$
                    DECLARE affected_mural UUID;
                    BEGIN
                        IF TG_OP = 'DELETE' THEN
                            affected_mural := OLD.mural_id;
                        ELSE
                            affected_mural := NEW.mural_id;
                        END IF;
                        IF affected_mural = '%s'::uuid THEN
                            RAISE EXCEPTION 'mem006 injected persistence failure';
                        END IF;
                        IF TG_OP = 'DELETE' THEN RETURN OLD; ELSE RETURN NEW; END IF;
                    END;
                    $$
                    """.formatted(muralId));
            statement.execute("""
                    CREATE TRIGGER mem006_test_fail_mural_photos_trigger
                    BEFORE INSERT OR UPDATE OR DELETE ON public.mural_photos
                    FOR EACH ROW EXECUTE FUNCTION public.mem006_test_fail_mural_photos()
                    """);
        }
    }

    private void dropInjectedFailureTrigger() throws Exception {
        try (Connection connection = databaseConnection()) {
            dropInjectedFailureTrigger(connection);
        }
    }

    private void dropInjectedFailureTrigger(Connection connection) throws SQLException {
        try {
            if (tableExists(connection, "mural_photos")) {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("DROP TRIGGER IF EXISTS mem006_test_fail_mural_photos_trigger ON public.mural_photos");
                    statement.execute("DROP FUNCTION IF EXISTS public.mem006_test_fail_mural_photos()");
                }
            }
        } catch (SQLException | RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new SQLException("Could not clean up MEM-006 failure trigger", failure);
        }
    }

    private void assertSanitizedDependencyFailure(HttpResponse<String> response) throws Exception {
        JsonNode problem = assertProblem(response, 503, "dependency_unavailable");
        assertThat(response.body()).doesNotContain("mem006 injected persistence failure", "SQLException", "mural_photos");
        assertThat(problem.path("detail").asText()).doesNotContain("mem006", "mural_photos", "SQLException");
    }

    private String photoStatus(UUID photoId) throws Exception {
        try (Connection connection = databaseConnection();
             PreparedStatement query = connection.prepareStatement("SELECT status FROM photo_assets WHERE id = ?")) {
            query.setObject(1, photoId);
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getString(1);
            }
        }
    }

    private boolean deferredPositionConstraintExists() throws Exception {
        try (Connection connection = databaseConnection();
             PreparedStatement query = connection.prepareStatement("""
                     SELECT condeferrable AND condeferred
                     FROM pg_constraint WHERE conname = 'uq_mural_photos_position'
                     """)) {
            try (ResultSet result = query.executeQuery()) {
                return result.next() && result.getBoolean(1);
            }
        }
    }

    private boolean tableExists(Connection connection, String table) throws Exception {
        try (PreparedStatement query = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
            query.setString(1, "public." + table);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private void assertObjectExists(String key) {
        try (S3Client s3 = integrationS3Client()) {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build());
        }
    }

    private void assertObjectMissing(String key) {
        try (S3Client s3 = integrationS3Client()) {
            assertThatThrownBy(() -> s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()))
                    .isInstanceOfSatisfying(S3Exception.class,
                            exception -> assertThat(exception.statusCode()).isEqualTo(404));
        }
    }

    private Connection databaseConnection() throws Exception {
        return DriverManager.getConnection(System.getenv("MEMORA_TEST_JDBC_URL"),
                System.getenv("MEMORA_TEST_JDBC_USERNAME"), System.getenv("MEMORA_TEST_JDBC_PASSWORD"));
    }

    private UUID localOwnerId() throws Exception {
        try (Connection connection = databaseConnection();
             PreparedStatement query = connection.prepareStatement(
                     "SELECT id FROM users WHERE identity_issuer = 'urn:memora:local' AND identity_subject = 'local-development-owner'")) {
            try (ResultSet result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                return (UUID) result.getObject(1);
            }
        }
    }

    private S3Client integrationS3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(System.getenv().getOrDefault("MINIO_ENDPOINT_INTERNAL", "http://minio:9000")))
                .region(Region.of(System.getenv().getOrDefault("MINIO_REGION", "us-east-1")))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        System.getenv().getOrDefault("MINIO_ACCESS_KEY", "memora_local_admin"),
                        System.getenv().getOrDefault("MINIO_SECRET_KEY", "memora-local-minio-password"))))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    private static byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(3, 4, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x243D3A);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private record ReadyPhoto(UUID photoId, String storageKey, byte[] bytes) { }
    private record ForeignPhoto(UUID ownerId, UUID photoId, String storageKey) { }
    private record ForeignMural(UUID ownerId, UUID muralId) { }
    private record MuralState(long lockVersion, Instant updatedAt) { }
}
