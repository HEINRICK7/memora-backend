package com.memora.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@EnabledIfEnvironmentVariable(named = "MEMORA_INTEGRATION_TESTS", matches = "true")
class MemoraComposeIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private final String api = System.getenv().getOrDefault("MEMORA_API_BASE_URL", "http://localhost:8080");

    @Test
    void signedPutRejectsAContentLengthDifferentFromTheIntent() throws Exception {
        byte[] png = pngBytes();
        JsonNode intent = postJson(api + "/api/v1/uploads", JSON.createObjectNode()
                .put("fileName", "oversize-probe.png")
                .put("contentType", "image/png")
                .put("sizeBytes", png.length), 201);
        byte[] oversized = java.util.Arrays.copyOf(png, png.length + 1);
        HttpRequest.Builder put = HttpRequest.newBuilder(URI.create(intent.path("uploadUrl").asText()))
                .timeout(Duration.ofSeconds(20)).header("Content-Type", "image/png")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(oversized));
        int status = HTTP.send(put.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        System.out.println("SIGNED_PUT_SIZE_LIMIT_STATUS=" + status);
        if (status >= 200 && status < 300) {
            HttpResponse<String> complete = HTTP.send(HttpRequest.newBuilder(URI.create(api + "/api/v1/uploads/"
                            + intent.path("uploadId").asText() + "/complete"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(complete.statusCode()).as(complete.body())
                    .isEqualTo(400);
        } else {
            assertThat(status).as("MinIO rejeitou o PUT cujo tamanho divergiu").isBetween(400, 499);
        }
        String key = URI.create(intent.path("uploadUrl").asText()).getPath()
                .replaceFirst("^/" + System.getenv().getOrDefault("MINIO_BUCKET", "memora-private") + "/", "");
        try (S3Client s3 = integrationS3Client()) {
            assertThatThrownBy(() -> s3.headObject(HeadObjectRequest.builder()
                    .bucket(System.getenv().getOrDefault("MINIO_BUCKET", "memora-private"))
                    .key(key).build()))
                    .isInstanceOfSatisfying(S3Exception.class,
                            exception -> assertThat(exception.statusCode()).isEqualTo(404));
        }
    }

    @Test
    void completeRejectsInvalidStoredBytesAndRemovesTheObject() throws Exception {
        byte[] invalidPngBytes = new byte[pngBytes().length];
        JsonNode intent = postJson(api + "/api/v1/uploads", JSON.createObjectNode()
                .put("fileName", "invalid-content.png")
                .put("contentType", "image/png")
                .put("sizeBytes", invalidPngBytes.length), 201);
        URI uploadUri = URI.create(intent.path("uploadUrl").asText());
        HttpResponse<Void> put = HTTP.send(HttpRequest.newBuilder(uploadUri)
                .header("Content-Type", "image/png")
                .PUT(HttpRequest.BodyPublishers.ofByteArray(invalidPngBytes)).build(),
                HttpResponse.BodyHandlers.discarding());
        assertThat(put.statusCode()).isBetween(200, 299);

        HttpResponse<String> complete = HTTP.send(HttpRequest.newBuilder(URI.create(api + "/api/v1/uploads/"
                        + intent.path("uploadId").asText() + "/complete"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(complete.statusCode()).as(complete.body()).isEqualTo(400);
        assertThat(complete.headers().firstValue("Content-Type").orElse(""))
                .startsWith("application/problem+json");

        String key = uploadUri.getPath()
                .replaceFirst("^/" + System.getenv().getOrDefault("MINIO_BUCKET", "memora-private") + "/", "");
        try (S3Client s3 = integrationS3Client()) {
            assertThatThrownBy(() -> s3.headObject(HeadObjectRequest.builder()
                    .bucket(System.getenv().getOrDefault("MINIO_BUCKET", "memora-private"))
                    .key(key).build()))
                    .isInstanceOfSatisfying(S3Exception.class,
                            exception -> assertThat(exception.statusCode()).isEqualTo(404));
        }
    }

    @Test
    void uploadsPrivateImageListsItAndDeletesItAgainstComposePostgresAndMinio() throws Exception {
        byte[] png = pngBytes();
        JsonNode intent = postJson(api + "/api/v1/uploads", JSON.createObjectNode()
                .put("fileName", "integration.png")
                .put("contentType", "image/png")
                .put("sizeBytes", png.length), 201);

        Map<String, String> requiredHeaders = new HashMap<>();
        intent.path("requiredHeaders").fields().forEachRemaining(entry ->
                requiredHeaders.put(entry.getKey(), entry.getValue().asText()));
        URI uploadUrl = URI.create(intent.path("uploadUrl").asText());
        String allowedOrigin = allowedCorsOrigin();
        HttpResponse<Void> preflight = HTTP.send(HttpRequest.newBuilder(uploadUrl)
                .header("Origin", allowedOrigin)
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(preflight.statusCode()).isBetween(200, 299);
        assertThat(preflight.headers().firstValue("Access-Control-Allow-Origin")).contains(allowedOrigin);
        HttpResponse<Void> rejectedOrigin = HTTP.send(HttpRequest.newBuilder(uploadUrl)
                .header("Origin", "https://attacker.invalid")
                .header("Access-Control-Request-Method", "PUT")
                .header("Access-Control-Request-Headers", "content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding());
        assertThat(rejectedOrigin.headers().firstValue("Access-Control-Allow-Origin")).isEmpty();
        HttpRequest.Builder put = HttpRequest.newBuilder(uploadUrl)
                .timeout(Duration.ofSeconds(20)).PUT(HttpRequest.BodyPublishers.ofByteArray(png));
        requiredHeaders.forEach(put::header);
        assertThat(HTTP.send(put.build(), HttpResponse.BodyHandlers.discarding()).statusCode())
                .isBetween(200, 299);

        JsonNode photo = postJson(api + "/api/v1/uploads/" + intent.path("uploadId").asText() + "/complete",
                JSON.createObjectNode(), 200);
        assertThat(photo.path("status").asText()).isEqualTo("READY");
        assertThat(photo.path("contentType").asText()).isEqualTo("image/png");
        assertThat(photo.path("previewUrl").asText()).contains("X-Amz-Signature=");

        JsonNode gallery = getJson(api + "/api/v1/photos", 200);
        assertThat(gallery.path("items").findValuesAsText("id")).contains(photo.path("id").asText());
        HttpResponse<byte[]> preview = HTTP.send(HttpRequest.newBuilder(URI.create(photo.path("previewUrl").asText()))
                .GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(preview.statusCode()).isEqualTo(200);
        assertThat(preview.body()).containsExactly(png);

        URI signed = URI.create(photo.path("previewUrl").asText());
        URI anonymousObject = new URI(signed.getScheme(), signed.getAuthority(), signed.getPath(), null, null);
        assertThat(HTTP.send(HttpRequest.newBuilder(anonymousObject).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode()).isBetween(400, 499);

        assertThat(HTTP.send(HttpRequest.newBuilder(URI.create(api + "/api/v1/photos/" + photo.path("id").asText()))
                .DELETE().build(), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(204);
        assertThat(getJson(api + "/api/v1/photos", 200).path("items").findValuesAsText("id"))
                .doesNotContain(photo.path("id").asText());
    }

    private static S3Client integrationS3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(System.getenv().getOrDefault("MINIO_ENDPOINT_INTERNAL",
                        "http://localhost:9000")))
                .region(Region.of(System.getenv().getOrDefault("MINIO_REGION", "us-east-1")))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        System.getenv().getOrDefault("MINIO_ACCESS_KEY", "memora_local_admin"),
                        System.getenv().getOrDefault("MINIO_SECRET_KEY", "memora-local-minio-password"))))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build();
    }

    private static String allowedCorsOrigin() {
        return Arrays.stream(System.getenv().getOrDefault("MEMORA_CORS_ALLOWED_ORIGINS",
                        "http://localhost:3000,http://127.0.0.1:3000").split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("MEMORA_CORS_ALLOWED_ORIGINS precisa conter uma origem."));
    }

    private static JsonNode postJson(String url, JsonNode body, int expectedStatus) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(body))).build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);
        return response.body().isBlank() ? JSON.createObjectNode() : JSON.readTree(response.body());
    }

    private static JsonNode getJson(String url, int expectedStatus) throws Exception {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);
        return JSON.readTree(response.body());
    }

    private static byte[] pngBytes() throws Exception {
        BufferedImage image = new BufferedImage(2, 3, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x123456);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bytes);
        return bytes.toByteArray();
    }
}
