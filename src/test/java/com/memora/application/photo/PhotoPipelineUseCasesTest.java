package com.memora.application.photo;

import com.memora.application.identity.CurrentUserId;
import com.memora.application.identity.Identity;
import com.memora.application.mural.ListMuralPhotos;
import com.memora.application.mural.MuralPhotoSnapshot;
import com.memora.application.mural.MuralPhotoViewCollection;
import com.memora.application.ports.MuralPhotoRepository;
import com.memora.application.ports.PhotoAssetRepository;
import com.memora.application.ports.PhotoStorage;
import com.memora.domain.mural.Mural;
import com.memora.domain.mural.MuralPhoto;
import com.memora.domain.mural.MuralStatus;
import com.memora.domain.photo.InspectedImage;
import com.memora.domain.photo.PhotoAsset;
import com.memora.domain.photo.PhotoQuotaExceededException;
import com.memora.domain.photo.PhotoStatus;
import com.memora.domain.photo.ProcessedImage;
import com.memora.domain.photo.UploadMetadata;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PhotoPipelineUseCasesTest {
    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UUID OWNER_UUID = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final String OWNER_ID = OWNER_UUID.toString();

    @Test
    void prepareUploadRejectsWhenOwnerQuotaIsReached() {
        InMemoryPhotoRepo repo = new InMemoryPhotoRepo();
        FakeStorage storage = new FakeStorage();
        repo.activeCountOverride = 2;

        PreparePhotoUpload useCase = new PreparePhotoUpload(
                repo, storage, () -> OWNER_ID, CLOCK, UUID::randomUUID, 20L * 1024 * 1024, 2
        );

        assertThatThrownBy(() -> useCase.execute(new PreparePhotoUploadCommand("a.jpg", "image/jpeg", 1024)))
                .isInstanceOf(PhotoQuotaExceededException.class);
    }

    @Test
    void completeUploadUsesSinglePassProcessingAndReturnsThumbnailAndShareViewUrls() {
        InMemoryPhotoRepo repo = new InMemoryPhotoRepo();
        FakeStorage storage = new FakeStorage();
        UUID id = UUID.randomUUID();
        PhotoAsset uploading = PhotoAsset.beginUpload(
                id, OWNER_ID, "originals/private/" + id,
                UploadMetadata.request("image/jpeg", 5000, 20L * 1024 * 1024),
                NOW.minusSeconds(30), NOW.plusSeconds(600)
        );
        repo.save(uploading);

        CompletePhotoUpload complete = new CompletePhotoUpload(repo, storage, () -> OWNER_ID, CLOCK);
        PhotoView view = complete.execute(id);

        assertThat(storage.processCalls.get()).isEqualTo(1);
        assertThat(storage.inspectCalls.get()).isZero();
        assertThat(storage.shareViewCalls.get()).isZero();
        assertThat(view.previewUrl()).contains("derived/private/share-view/v1/" + id + ".jpg");
        assertThat(view.thumbnailUrl()).contains("derived/private/thumb/v1/" + id + ".jpg");
        assertThat(view.previewExpiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void listPhotosPaginatesWithCursorAndUsesDerivedUrls() {
        InMemoryPhotoRepo repo = new InMemoryPhotoRepo();
        FakeStorage storage = new FakeStorage();
        for (int i = 0; i < 3; i++) {
            UUID id = UUID.randomUUID();
            repo.save(PhotoAsset.restore(
                    id, OWNER_ID, "originals/private/" + id, "image/jpeg", 5000,
                    PhotoStatus.READY, NOW.minusSeconds(i * 10L), NOW.plusSeconds(300),
                    2000, 1000, NOW, null,
                    "derived/private/share-view/v1/" + id + ".jpg",
                    "derived/private/thumb/v1/" + id + ".jpg"
            ));
        }

        ListPhotos list = new ListPhotos(repo, storage, () -> OWNER_ID, CLOCK);
        PhotoPage firstPage = list.execute(2, null);
        assertThat(firstPage.items()).hasSize(2);
        assertThat(firstPage.nextCursor()).isNotNull();
        assertThat(firstPage.items().getFirst().thumbnailUrl()).contains("derived/private/thumb/v1/");

        PhotoPage secondPage = list.execute(2, firstPage.nextCursor());
        assertThat(secondPage.items()).hasSize(1);
        assertThat(secondPage.nextCursor()).isNull();
    }

    @Test
    void listMuralPhotosLoadsPhotosInBatchWithoutNPlusOne() {
        InMemoryPhotoRepo repo = new InMemoryPhotoRepo();
        FakeStorage storage = new FakeStorage();
        UUID muralId = UUID.randomUUID();
        UUID p1 = UUID.randomUUID();
        UUID p2 = UUID.randomUUID();
        for (UUID id : List.of(p1, p2)) {
            repo.save(PhotoAsset.restore(
                    id, OWNER_ID, "originals/private/" + id, "image/jpeg", 4000,
                    PhotoStatus.READY, NOW, NOW.plusSeconds(300), 1600, 1200, NOW, null,
                    "derived/private/share-view/v1/" + id + ".jpg",
                    "derived/private/thumb/v1/" + id + ".jpg"
            ));
        }
        Mural mural = Mural.restore(muralId, OWNER_UUID, MuralStatus.DRAFT, 1, "Festa", null, NOW, NOW);
        MuralPhotoSnapshot snapshot = new MuralPhotoSnapshot(
                mural,
                List.of(new MuralPhoto(muralId, p1, 0), new MuralPhoto(muralId, p2, 1))
        );
        MuralPhotoRepository muralRepo = new MuralPhotoRepository() {
            @Override
            public MuralPhotoSnapshot findOwned(UUID ownerId, UUID id) {
                return snapshot;
            }
            @Override
            public com.memora.application.mural.MuralPhotoAttachment attach(UUID o, UUID m, UUID p, int v, Instant n) {
                throw new UnsupportedOperationException();
            }
            @Override
            public MuralPhotoSnapshot reorder(UUID o, UUID m, List<UUID> ps, int v, Instant n) {
                throw new UnsupportedOperationException();
            }
            @Override
            public MuralPhotoSnapshot remove(UUID o, UUID m, UUID p, int v, Instant n) {
                throw new UnsupportedOperationException();
            }
        };

        CurrentUserId currentUser = new CurrentUserId(
                () -> new Identity("urn:test", "sub-1"),
                identity -> OWNER_UUID
        );
        ListMuralPhotos listMuralPhotos = new ListMuralPhotos(muralRepo, repo, storage, currentUser, CLOCK);
        MuralPhotoViewCollection result = listMuralPhotos.execute(muralId);

        assertThat(result.items()).hasSize(2);
        assertThat(repo.singleFindCalls.get()).isZero();
        assertThat(repo.batchFindCalls.get()).isEqualTo(1);
        assertThat(result.items().getFirst().thumbnailUrl()).contains("derived/private/thumb/v1/" + p1 + ".jpg");
    }

    @Test
    void cleanupOrphanUploadsFailsExpiredIntentsAndDeletesStorageObjects() {
        InMemoryPhotoRepo repo = new InMemoryPhotoRepo();
        FakeStorage storage = new FakeStorage();
        UUID expiredId = UUID.randomUUID();
        repo.save(PhotoAsset.beginUpload(
                expiredId, OWNER_ID, "originals/private/" + expiredId,
                UploadMetadata.request("image/jpeg", 1000, 20L * 1024 * 1024),
                NOW.minusSeconds(1200), NOW.minusSeconds(60)
        ));

        CleanupOrphanUploads cleanup = new CleanupOrphanUploads(repo, storage, CLOCK);
        int cleaned = cleanup.execute(50);

        assertThat(cleaned).isEqualTo(1);
        assertThat(repo.store.get(expiredId).status()).isEqualTo(PhotoStatus.FAILED);
        assertThat(storage.deletedKeys).containsExactly("originals/private/" + expiredId);
    }

    private static final class InMemoryPhotoRepo implements PhotoAssetRepository {
        final Map<UUID, PhotoAsset> store = new LinkedHashMap<>();
        long activeCountOverride = -1;
        final AtomicInteger singleFindCalls = new AtomicInteger();
        final AtomicInteger batchFindCalls = new AtomicInteger();

        @Override
        public PhotoAsset save(PhotoAsset asset) {
            store.put(asset.id(), asset);
            return asset;
        }

        @Override
        public PhotoAsset saveReadyWithShareView(PhotoAsset asset, String key, InspectedImage img, Instant readyAt) {
            PhotoAsset updated = PhotoAsset.restore(
                    asset.id(), asset.ownerId(), asset.storageKey(), asset.contentType(), asset.sizeBytes(),
                    asset.status(), asset.createdAt(), asset.intentExpiresAt(), asset.width(), asset.height(),
                    asset.readyAt(), asset.deletedAt(), key, asset.thumbnailStorageKey()
            );
            store.put(updated.id(), updated);
            return updated;
        }

        @Override
        public PhotoAsset saveReadyWithDerivatives(PhotoAsset asset, String shareViewKey, InspectedImage shareView,
                                                   String thumbnailKey, InspectedImage thumbnail, Instant readyAt) {
            PhotoAsset updated = PhotoAsset.restore(
                    asset.id(), asset.ownerId(), asset.storageKey(), asset.contentType(), asset.sizeBytes(),
                    asset.status(), asset.createdAt(), asset.intentExpiresAt(), asset.width(), asset.height(),
                    asset.readyAt(), asset.deletedAt(), shareViewKey, thumbnailKey
            );
            store.put(updated.id(), updated);
            return updated;
        }

        @Override
        public Optional<PhotoAsset> findByIdAndOwner(UUID id, String ownerId) {
            singleFindCalls.incrementAndGet();
            return Optional.ofNullable(store.get(id)).filter(a -> a.ownerId().equals(ownerId));
        }

        @Override
        public List<PhotoAsset> findReadyByIdsAndOwner(List<UUID> ids, String ownerId) {
            batchFindCalls.incrementAndGet();
            return ids.stream().map(store::get)
                    .filter(a -> a != null && a.ownerId().equals(ownerId) && a.status() == PhotoStatus.READY)
                    .toList();
        }

        @Override
        public List<PhotoAsset> findReadyByOwner(String ownerId) {
            return store.values().stream()
                    .filter(a -> a.ownerId().equals(ownerId) && a.status() == PhotoStatus.READY)
                    .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                    .toList();
        }

        @Override
        public List<PhotoAsset> findReadyByOwnerPage(String ownerId, int limit, Instant beforeCreatedAt, UUID beforeId) {
            return findReadyByOwner(ownerId).stream()
                    .filter(a -> beforeCreatedAt == null || a.createdAt().isBefore(beforeCreatedAt)
                            || (a.createdAt().equals(beforeCreatedAt) && a.id().compareTo(beforeId) < 0))
                    .limit(limit)
                    .toList();
        }

        @Override
        public long countActiveByOwner(String ownerId) {
            if (activeCountOverride >= 0) return activeCountOverride;
            return store.values().stream()
                    .filter(a -> a.ownerId().equals(ownerId)
                            && (a.status() == PhotoStatus.READY || a.status() == PhotoStatus.UPLOADING))
                    .count();
        }

        @Override
        public List<PhotoAsset> findExpiredUploading(Instant now, int limit) {
            return store.values().stream()
                    .filter(a -> a.status() == PhotoStatus.UPLOADING && !now.isBefore(a.intentExpiresAt()))
                    .limit(limit)
                    .toList();
        }

        @Override
        public PhotoAsset deleteIfUnattached(UUID id, String ownerId, Instant now) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeStorage implements PhotoStorage {
        final AtomicInteger inspectCalls = new AtomicInteger();
        final AtomicInteger shareViewCalls = new AtomicInteger();
        final AtomicInteger processCalls = new AtomicInteger();
        final List<String> deletedKeys = new ArrayList<>();

        @Override
        public String presignPut(String storageKey, String contentType, long contentLength, Duration ttl) {
            return "https://s3.local/put/" + storageKey;
        }

        @Override
        public void putPrivateObject(String storageKey, String contentType, byte[] content) {
        }

        @Override
        public InspectedImage inspect(String storageKey, long expectedSizeBytes) {
            inspectCalls.incrementAndGet();
            return new InspectedImage("image/jpeg", expectedSizeBytes, 2000, 1000);
        }

        @Override
        public InspectedImage prepareShareView(String sourceKey, String targetKey, long expectedSizeBytes) {
            shareViewCalls.incrementAndGet();
            return new InspectedImage("image/jpeg", 2000, 2000, 1000);
        }

        @Override
        public ProcessedImage processUploadedPhoto(String sourceKey, String shareViewKey, String thumbnailKey,
                                                   long expectedSizeBytes, String expectedContentType) {
            processCalls.incrementAndGet();
            return new ProcessedImage(
                    new InspectedImage(expectedContentType, expectedSizeBytes, 2000, 1000),
                    new InspectedImage(expectedContentType, 1800, 2000, 1000),
                    new InspectedImage("image/jpeg", 400, 480, 240)
            );
        }

        @Override
        public String presignGet(String storageKey, Duration ttl) {
            return "https://s3.local/get/" + storageKey + "?ttl=" + ttl.toSeconds();
        }

        @Override
        public void delete(String storageKey) {
            deletedKeys.add(storageKey);
        }
    }
}
