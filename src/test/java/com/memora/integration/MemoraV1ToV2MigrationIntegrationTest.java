package com.memora.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the V1-to-V2 ownership migration against real PostgreSQL in the Compose stack (MEM-002-AC10). */
@EnabledIfEnvironmentVariable(named = "MEMORA_MURAL_INTEGRATION_TESTS", matches = "true")
class MemoraV1ToV2MigrationIntegrationTest {
    private static final String LEGACY_OWNER = "local-development-owner";
    private static final UUID SYNTHETIC_PHOTO_ID = UUID.fromString("fb7441a6-234a-4386-8da6-e945e942cdf0");
    private static final String SYNTHETIC_STORAGE_KEY = "originals/private/fb7441a6-234a-4386-8da6-e945e942cdf0";
    private static final String ISSUER = System.getenv().getOrDefault("MEMORA_TEST_IDENTITY_ISSUER", "urn:memora:local");
    private static final List<String> STATUSES = List.of("UPLOADING", "READY", "FAILED", "DELETED");

    @Test
    void AC10_backfillsAllV1PhotoStatesToOneUuidUserWithoutChangingStorageKeys() throws Exception {
        String jdbcUrl = requiredEnvironment("MEMORA_TEST_JDBC_URL");
        String username = requiredEnvironment("MEMORA_TEST_JDBC_USERNAME");
        String password = requiredEnvironment("MEMORA_TEST_JDBC_PASSWORD");
        String schema = "memora_red_" + UUID.randomUUID().toString().replace("-", "");

        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA \"" + schema + "\"");
        }

        try {
            flyway(jdbcUrl, username, password, schema, true).migrate();
            LegacyPhoto syntheticPhoto = readSyntheticPhoto(jdbcUrl, username, password);
            assertThat(syntheticPhoto.id()).isEqualTo(SYNTHETIC_PHOTO_ID);
            assertThat(syntheticPhoto.ownerSubject()).isEqualTo(LEGACY_OWNER);
            if (syntheticPhoto.internalOwnerId() != null) {
                assertThat(syntheticPhoto.identityIssuer()).isEqualTo(ISSUER);
            }
            assertThat(syntheticPhoto.status()).isEqualTo("READY");
            assertThat(syntheticPhoto.storageKey()).isEqualTo(SYNTHETIC_STORAGE_KEY);
            assertThat(syntheticPhoto.sizeBytes()).isPositive();
            String syntheticSha256Before = syntheticObjectSha256(syntheticPhoto.sizeBytes());

            insertV1PhotoFixtures(jdbcUrl, username, password, schema);
            copySyntheticPhotoToV1Schema(jdbcUrl, username, password, schema, syntheticPhoto);
            flyway(jdbcUrl, username, password, schema, false).migrate();

            assertThat(tableExists(jdbcUrl, username, password, schema, "users"))
                    .as("MEM-002-AC10: V2 creates the users table")
                    .isTrue();
            assertThat(columnType(jdbcUrl, username, password, schema, "photo_assets", "owner_id"))
                    .as("MEM-002-AC10: photo owner is migrated from V1 text subject to internal UUID")
                    .isEqualTo("uuid");
            assertThat(userCount(jdbcUrl, username, password, schema)).isEqualTo(1);
            assertThat(identityForLegacyOwner(jdbcUrl, username, password, schema))
                    .containsExactly(ISSUER, LEGACY_OWNER);
            assertThat(migratedPhotoStates(jdbcUrl, username, password, schema))
                    .containsAll(STATUSES).hasSize(5);
            assertThat(migratedStorageKeys(jdbcUrl, username, password, schema))
                    .containsExactlyInAnyOrder("legacy-key-uploading", "legacy-key-ready",
                            "legacy-key-failed", "legacy-key-deleted", SYNTHETIC_STORAGE_KEY);
            assertThat(allPhotoOwnersEqualUser(jdbcUrl, username, password, schema)).isTrue();
            assertThat(migratedSyntheticPhoto(jdbcUrl, username, password, schema))
                    .isEqualTo(new MigratedSyntheticPhoto(syntheticPhoto.id().toString(), "READY",
                            SYNTHETIC_STORAGE_KEY, syntheticPhoto.sizeBytes(), ISSUER, LEGACY_OWNER));
            assertThat(syntheticObjectSha256(syntheticPhoto.sizeBytes())).isEqualTo(syntheticSha256Before);
            assertThat(readSyntheticPhoto(jdbcUrl, username, password)).isEqualTo(syntheticPhoto);
        } finally {
            try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
                 Statement statement = connection.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS \"" + schema + "\" CASCADE");
            }
        }
    }

    private static Flyway flyway(String url, String username, String password, String schema, boolean v1Only) {
        org.flywaydb.core.api.configuration.FluentConfiguration configuration = Flyway.configure()
                .dataSource(url, username, password)
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .placeholderReplacement(true)
                .placeholders(Map.of("memoraIdentityIssuer", ISSUER));
        if (v1Only) {
            configuration.target(MigrationVersion.fromVersion("1"));
        }
        return configuration.load();
    }

    private static void insertV1PhotoFixtures(String url, String username, String password, String schema)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO \"" + schema + "\"");
        }
        try (Connection connection = DriverManager.getConnection(url, username, password);
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO photo_assets (
                         id, owner_id, storage_key, content_type, size_bytes, status,
                         created_at, intent_expires_at, width, height, ready_at, deleted_at
                     ) VALUES (?, ?, ?, 'image/png', 32, ?,
                               TIMESTAMPTZ '2026-10-24T12:00:00Z',
                               TIMESTAMPTZ '2026-10-24T12:15:00Z', ?, ?, ?, ?)
                     """)) {
            connection.createStatement().execute("SET search_path TO \"" + schema + "\"");
            for (int index = 0; index < STATUSES.size(); index++) {
                String status = STATUSES.get(index);
                insert.setObject(1, UUID.nameUUIDFromBytes(status.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                insert.setString(2, LEGACY_OWNER);
                insert.setString(3, "legacy-key-" + status.toLowerCase(java.util.Locale.ROOT));
                insert.setString(4, status);
                boolean completed = status.equals("READY") || status.equals("DELETED");
                insert.setObject(5, completed ? 640 : null);
                insert.setObject(6, completed ? 480 : null);
                insert.setObject(7, completed ? java.time.OffsetDateTime.parse("2026-10-24T12:05:00Z") : null);
                insert.setObject(8, status.equals("DELETED")
                        ? java.time.OffsetDateTime.parse("2026-10-24T12:10:00Z") : null);
                insert.executeUpdate();
            }
        }
    }

    private static LegacyPhoto readSyntheticPhoto(String url, String username, String password) throws Exception {
        String ownerType = columnType(url, username, password, "public", "photo_assets", "owner_id");
        boolean migrated = "uuid".equals(ownerType);
        String identityColumns = migrated ? ", users.identity_issuer, users.identity_subject" : "";
        String ownerJoin = migrated ? " JOIN public.users AS users ON photos.owner_id = users.id " : " ";
        try (Connection connection = DriverManager.getConnection(url, username, password);
             PreparedStatement query = connection.prepareStatement("""
                     SELECT photos.id, photos.owner_id, photos.storage_key, photos.content_type, photos.size_bytes,
                            photos.status, photos.created_at, photos.intent_expires_at,
                            photos.width, photos.height, photos.ready_at, photos.deleted_at
                     """ + identityColumns + " FROM public.photo_assets AS photos " + ownerJoin + "WHERE photos.id = ?")) {
            query.setObject(1, SYNTHETIC_PHOTO_ID);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    throw new AssertionError("The protected Compose synthetic photo is missing before migration.");
                }
                return new LegacyPhoto(result.getObject("id", UUID.class),
                        migrated ? result.getString("identity_subject") : result.getString("owner_id"),
                        migrated ? result.getObject("owner_id", UUID.class) : null,
                        migrated ? result.getString("identity_issuer") : null,
                        result.getString("storage_key"), result.getString("content_type"),
                        result.getLong("size_bytes"), result.getString("status"),
                        result.getObject("created_at", OffsetDateTime.class).toInstant(),
                        result.getObject("intent_expires_at", OffsetDateTime.class).toInstant(),
                        (Integer) result.getObject("width"), (Integer) result.getObject("height"),
                        instantOrNull(result, "ready_at"), instantOrNull(result, "deleted_at"));
            }
        }
    }

    private static void copySyntheticPhotoToV1Schema(String url, String username, String password, String schema,
                                                     LegacyPhoto photo) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO \"" + schema + "\"");
        }
        try (Connection connection = scopedConnection(url, username, password, schema);
             PreparedStatement insert = connection.prepareStatement("""
                     INSERT INTO photo_assets (
                         id, owner_id, storage_key, content_type, size_bytes, status,
                         created_at, intent_expires_at, width, height, ready_at, deleted_at
                     ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            insert.setObject(1, photo.id());
            insert.setString(2, photo.ownerSubject());
            insert.setString(3, photo.storageKey());
            insert.setString(4, photo.contentType());
            insert.setLong(5, photo.sizeBytes());
            insert.setString(6, photo.status());
            insert.setObject(7, OffsetDateTime.ofInstant(photo.createdAt(), ZoneOffset.UTC));
            insert.setObject(8, OffsetDateTime.ofInstant(photo.intentExpiresAt(), ZoneOffset.UTC));
            insert.setObject(9, photo.width());
            insert.setObject(10, photo.height());
            insert.setObject(11, offsetDateTimeOrNull(photo.readyAt()));
            insert.setObject(12, offsetDateTimeOrNull(photo.deletedAt()));
            assertThat(insert.executeUpdate()).isEqualTo(1);
        }
    }

    private static Instant instantOrNull(ResultSet row, String column) throws Exception {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime offsetDateTimeOrNull(Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static MigratedSyntheticPhoto migratedSyntheticPhoto(String url, String username, String password,
                                                                  String schema) throws Exception {
        try (Connection connection = scopedConnection(url, username, password, schema);
             PreparedStatement query = connection.prepareStatement("""
                     SELECT p.id, p.owner_id, p.status, p.storage_key, p.size_bytes,
                            u.identity_issuer, u.identity_subject
                     FROM photo_assets p JOIN users u ON p.owner_id = u.id WHERE p.id = ?
                     """)) {
            query.setObject(1, SYNTHETIC_PHOTO_ID);
            try (ResultSet result = query.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new MigratedSyntheticPhoto(result.getString("id"), result.getString("status"),
                        result.getString("storage_key"), result.getLong("size_bytes"),
                        result.getString("identity_issuer"), result.getString("identity_subject"));
            }
        }
    }

    private static String syntheticObjectSha256(long expectedBytes) {
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(System.getenv().getOrDefault("MINIO_ENDPOINT_INTERNAL", "http://minio:9000")))
                .region(Region.of(System.getenv().getOrDefault("MINIO_REGION", "us-east-1")))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        System.getenv().getOrDefault("MINIO_ACCESS_KEY", "memora_local_admin"),
                        System.getenv().getOrDefault("MINIO_SECRET_KEY", "memora-local-minio-password"))))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            String bucket = System.getenv().getOrDefault("MINIO_BUCKET", "memora-private");
            long storedBytes = s3.headObject(HeadObjectRequest.builder()
                            .bucket(System.getenv().getOrDefault("MINIO_BUCKET", "memora-private"))
                            .key(SYNTHETIC_STORAGE_KEY).build())
                    .contentLength();
            assertThat(storedBytes).as("the synthetic MinIO object's bytes must remain untouched")
                    .isEqualTo(expectedBytes);
            byte[] bytes = s3.getObjectAsBytes(GetObjectRequest.builder().bucket(bucket)
                    .key(SYNTHETIC_STORAGE_KEY).build()).asByteArray();
            assertThat(bytes.length).isEqualTo(expectedBytes);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static boolean tableExists(String url, String username, String password, String schema, String table)
            throws Exception {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             PreparedStatement query = connection.prepareStatement(
                     "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?)")) {
            query.setString(1, schema);
            query.setString(2, table);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static String columnType(String url, String username, String password, String schema,
                                     String table, String column) throws Exception {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             PreparedStatement query = connection.prepareStatement("""
                     SELECT data_type FROM information_schema.columns
                     WHERE table_schema = ? AND table_name = ? AND column_name = ?
                     """)) {
            query.setString(1, schema);
            query.setString(2, table);
            query.setString(3, column);
            try (ResultSet result = query.executeQuery()) {
                return result.next() ? result.getString(1) : "missing";
            }
        }
    }

    private static long userCount(String url, String username, String password, String schema) throws Exception {
        return queryLong(url, username, password, schema, "SELECT count(*) FROM users");
    }

    private static List<String> identityForLegacyOwner(String url, String username, String password, String schema)
            throws Exception {
        try (Connection connection = scopedConnection(url, username, password, schema);
             Statement query = connection.createStatement();
             ResultSet result = query.executeQuery("SELECT identity_issuer, identity_subject FROM users")) {
            if (!result.next()) {
                return List.of();
            }
            return List.of(result.getString(1), result.getString(2));
        }
    }

    private static List<String> migratedPhotoStates(String url, String username, String password, String schema)
            throws Exception {
        return queryStrings(url, username, password, schema, "SELECT status FROM photo_assets");
    }

    private static List<String> migratedStorageKeys(String url, String username, String password, String schema)
            throws Exception {
        return queryStrings(url, username, password, schema, "SELECT storage_key FROM photo_assets");
    }

    private static boolean allPhotoOwnersEqualUser(String url, String username, String password, String schema)
            throws Exception {
        try (Connection connection = scopedConnection(url, username, password, schema);
             PreparedStatement query = connection.prepareStatement("""
                     SELECT count(*) = 0 FROM photo_assets p
                     LEFT JOIN users u ON p.owner_id = u.id
                     WHERE u.id IS NULL OR u.identity_issuer <> ? OR u.identity_subject <> ?
                     """)) {
            query.setString(1, ISSUER);
            query.setString(2, LEGACY_OWNER);
            try (ResultSet result = query.executeQuery()) {
                result.next();
                return result.getBoolean(1);
            }
        }
    }

    private static long queryLong(String url, String username, String password, String schema, String sql)
            throws Exception {
        try (Connection connection = scopedConnection(url, username, password, schema);
             Statement query = connection.createStatement();
             ResultSet result = query.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static List<String> queryStrings(String url, String username, String password, String schema, String sql)
            throws Exception {
        try (Connection connection = scopedConnection(url, username, password, schema);
             Statement query = connection.createStatement();
             ResultSet result = query.executeQuery(sql)) {
            java.util.ArrayList<String> values = new java.util.ArrayList<>();
            while (result.next()) {
                values.add(result.getString(1));
            }
            return values;
        }
    }

    private static Connection scopedConnection(String url, String username, String password, String schema)
            throws Exception {
        Connection connection = DriverManager.getConnection(url, username, password);
        connection.createStatement().execute("SET search_path TO \"" + schema + "\"");
        return connection;
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set " + name + " to run the real PostgreSQL migration test.");
        }
        return value;
    }

    private record LegacyPhoto(UUID id, String ownerSubject, UUID internalOwnerId, String identityIssuer,
                              String storageKey, String contentType, long sizeBytes, String status,
                              Instant createdAt, Instant intentExpiresAt, Integer width, Integer height,
                              Instant readyAt, Instant deletedAt) { }

    private record MigratedSyntheticPhoto(String id, String status, String storageKey, long sizeBytes,
                                          String issuer, String subject) { }
}
