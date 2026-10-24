package com.memora.application.photo;

import com.memora.application.ports.CurrentOwner;
import com.memora.application.ports.PhotoAssetRepository;
import com.memora.application.ports.PhotoStorage;
import com.memora.domain.photo.InspectedImage;
import com.memora.domain.photo.PhotoAsset;
import com.memora.application.photo.PhotoNotFoundException;
import com.memora.domain.photo.PhotoStatus;
import com.memora.domain.photo.UploadMetadata;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompletePhotoUploadTest {
    private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    @Test
    void marksInvalidStoredContentFailedAndDeletesThePrivateObject() {
        UUID id = UUID.randomUUID();
        PhotoAsset uploading = PhotoAsset.beginUpload(
                id,
                "local-development-owner",
                "originals/private/" + id,
                UploadMetadata.request("image/png", 128, MAX_BYTES),
                NOW,
                NOW.plusSeconds(900)
        );
        InMemoryRepository repository = new InMemoryRepository(uploading);
        RecordingStorage storage = new RecordingStorage();
        storage.inspected = new InspectedImage("image/jpeg", 128, 640, 480);

        assertThatThrownBy(() -> useCase(repository, storage).execute(id))
                .isInstanceOf(com.memora.domain.photo.PhotoValidationException.class);

        assertThat(repository.findByIdAndOwner(id, "local-development-owner").orElseThrow().status())
                .isEqualTo(PhotoStatus.FAILED);
        assertThat(storage.deletedKeys).containsExactly(uploading.storageKey());
        assertThat(storage.presignGetCalls).isZero();
    }

    @Test
    void completingReadyPhotoAgainReturnsNewPreviewUrlWithoutReinspectingObject() {
        UUID id = UUID.randomUUID();
        PhotoAsset ready = PhotoAsset.beginUpload(
                id,
                "local-development-owner",
                "originals/private/" + id,
                UploadMetadata.request("image/png", 128, MAX_BYTES),
                NOW,
                NOW.plusSeconds(900)
        ).confirm(new InspectedImage("image/png", 128, 640, 480), NOW.plusSeconds(10));
        InMemoryRepository repository = new InMemoryRepository(ready);
        RecordingStorage storage = new RecordingStorage();

        PhotoView result = useCase(repository, storage).execute(id);

        assertThat(result.id()).isEqualTo(id);
        assertThat(result.status()).isEqualTo(PhotoStatus.READY);
        assertThat(result.previewUrl()).isEqualTo("https://minio.example/signed-preview");
        assertThat(storage.inspectCalls).isZero();
        assertThat(storage.presignGetCalls).isEqualTo(1);
    }

    @Test
    void doesNotRevealAssetsOwnedByAnotherPrincipal() {
        UUID id = UUID.randomUUID();
        PhotoAsset uploading = PhotoAsset.beginUpload(
                id,
                "another-owner",
                "originals/private/" + id,
                UploadMetadata.request("image/png", 128, MAX_BYTES),
                NOW,
                NOW.plusSeconds(900)
        );
        InMemoryRepository repository = new InMemoryRepository(uploading);
        RecordingStorage storage = new RecordingStorage();

        assertThatThrownBy(() -> useCase(repository, storage).execute(id))
                .isInstanceOf(PhotoNotFoundException.class);
        assertThat(storage.inspectCalls).isZero();
    }

    private static CompletePhotoUpload useCase(
            InMemoryRepository repository,
            RecordingStorage storage
    ) {
        return new CompletePhotoUpload(
                repository,
                storage,
                () -> "local-development-owner",
                Clock.fixed(NOW.plusSeconds(10), ZoneOffset.UTC)
        );
    }

    private static final class InMemoryRepository implements PhotoAssetRepository {
        private final List<PhotoAsset> assets = new ArrayList<>();

        private InMemoryRepository(PhotoAsset asset) {
            assets.add(asset);
        }

        @Override
        public PhotoAsset save(PhotoAsset asset) {
            assets.removeIf(existing -> existing.id().equals(asset.id()));
            assets.add(asset);
            return asset;
        }

        @Override
        public Optional<PhotoAsset> findByIdAndOwner(UUID id, String ownerId) {
            return assets.stream()
                    .filter(asset -> asset.id().equals(id) && asset.ownerId().equals(ownerId))
                    .findFirst();
        }

        @Override
        public List<PhotoAsset> findReadyByOwner(String ownerId) {
            return assets.stream()
                    .filter(asset -> asset.ownerId().equals(ownerId)
                            && asset.status() == PhotoStatus.READY)
                    .toList();
        }

        @Override
        public PhotoAsset deleteIfUnattached(UUID id, String ownerId, java.time.Instant now) {
            PhotoAsset asset = findByIdAndOwner(id, ownerId).orElseThrow(PhotoNotFoundException::new);
            return save(asset.delete(now));
        }
    }

    private static final class RecordingStorage implements PhotoStorage {
        private InspectedImage inspected = new InspectedImage("image/png", 128, 640, 480);
        private int inspectCalls;
        private int presignGetCalls;
        private final List<String> deletedKeys = new ArrayList<>();

        @Override
        public String presignPut(String storageKey, String contentType, long contentLength, Duration ttl) {
            return "https://minio.example/signed-upload";
        }

        @Override
        public InspectedImage inspect(String storageKey, long expectedSizeBytes) {
            inspectCalls++;
            return inspected;
        }

        @Override
        public String presignGet(String storageKey, Duration ttl) {
            presignGetCalls++;
            return "https://minio.example/signed-preview";
        }

        @Override
        public void delete(String storageKey) {
            deletedKeys.add(storageKey);
        }
    }
}
