package com.memora.application.photo;

import com.memora.application.ports.PhotoAssetRepository;
import com.memora.application.ports.PhotoStorage;
import com.memora.domain.photo.PhotoAsset;
import com.memora.domain.photo.PhotoValidationException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PreparePhotoUploadTest {
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");

    @Test
    void rejectsUnsupportedMetadataBeforeTouchingStorageOrRepository() {
        InMemoryPhotoRepository repository = new InMemoryPhotoRepository();
        RecordingPhotoStorage storage = new RecordingPhotoStorage();
        PreparePhotoUpload useCase = useCase(repository, storage);

        assertThatThrownBy(() -> useCase.execute(
                new PreparePhotoUploadCommand("portrait.webp", "image/webp", 400)
        )).isInstanceOf(PhotoValidationException.class);

        assertThat(storage.presignCalls).isZero();
        assertThat(repository.saved).isEmpty();
    }

    @Test
    void createsAnOpaquePrivateIntentAndReturnsTheSignedPutContract() {
        InMemoryPhotoRepository repository = new InMemoryPhotoRepository();
        RecordingPhotoStorage storage = new RecordingPhotoStorage();
        UUID uploadId = UUID.fromString("39c535b7-8a4e-47a9-8dce-4463efb8f88a");
        PreparePhotoUpload useCase = new PreparePhotoUpload(
                repository,
                storage,
                () -> "local-development-owner",
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> uploadId,
                20L * 1024 * 1024
        );

        UploadIntentResult result = useCase.execute(
                new PreparePhotoUploadCommand("family.png", "image/png", 1024)
        );

        assertThat(result.uploadId()).isEqualTo(uploadId);
        assertThat(result.method()).isEqualTo("PUT");
        assertThat(result.uploadUrl()).isEqualTo("https://minio.example/signed-upload");
        assertThat(result.requiredHeaders()).containsEntry("Content-Type", "image/png");
        assertThat(result.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(5)));
        assertThat(storage.presignCalls).isEqualTo(1);
        assertThat(storage.lastKey).startsWith("originals/private/");
        assertThat(storage.lastKey).doesNotContain("family.png");
        assertThat(repository.saved).hasSize(1);
        assertThat(repository.saved.getFirst().status().name()).isEqualTo("UPLOADING");
    }

    private static PreparePhotoUpload useCase(
            InMemoryPhotoRepository repository, RecordingPhotoStorage storage
    ) {
        return new PreparePhotoUpload(
                repository, storage, () -> "local-development-owner",
                Clock.fixed(NOW, ZoneOffset.UTC), UUID::randomUUID, 20L * 1024 * 1024
        );
    }

    private static final class InMemoryPhotoRepository implements PhotoAssetRepository {
        private final java.util.List<PhotoAsset> saved = new java.util.ArrayList<>();

        @Override
        public PhotoAsset save(PhotoAsset asset) {
            saved.add(asset);
            return asset;
        }

        @Override
        public Optional<PhotoAsset> findByIdAndOwner(UUID id, String ownerId) {
            return saved.stream()
                    .filter(asset -> asset.id().equals(id) && asset.ownerId().equals(ownerId))
                    .findFirst();
        }

        @Override
        public java.util.List<PhotoAsset> findReadyByOwner(String ownerId) {
            return saved.stream()
                    .filter(asset -> asset.ownerId().equals(ownerId)
                            && asset.status().name().equals("READY"))
                    .toList();
        }

        @Override
        public PhotoAsset deleteIfUnattached(UUID id, String ownerId, java.time.Instant now) {
            PhotoAsset asset = findByIdAndOwner(id, ownerId).orElseThrow(PhotoNotFoundException::new);
            return save(asset.delete(now));
        }
    }

    private static final class RecordingPhotoStorage implements PhotoStorage {
        private int presignCalls;
        private String lastKey;

        @Override
        public String presignPut(String key, String contentType, long contentLength, Duration ttl) {
            presignCalls++;
            lastKey = key;
            return "https://minio.example/signed-upload";
        }

        @Override
        public com.memora.domain.photo.InspectedImage inspect(String key, long expectedSizeBytes) {
            return new com.memora.domain.photo.InspectedImage("image/png", 1024, 640, 480);
        }

        @Override
        public String presignGet(String key, Duration ttl) {
            return "https://minio.example/signed-preview";
        }

        @Override
        public void delete(String key) {
        }
    }
}
