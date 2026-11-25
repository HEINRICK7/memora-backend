package com.memora.application.photo;

import com.memora.application.ports.PhotoAssetRepository;
import com.memora.application.ports.PhotoStorage;
import com.memora.domain.photo.PhotoAsset;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

public final class CleanupOrphanUploads {
    private final PhotoAssetRepository repository;
    private final PhotoStorage storage;
    private final Clock clock;

    public CleanupOrphanUploads(PhotoAssetRepository repository, PhotoStorage storage, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.storage = Objects.requireNonNull(storage);
        this.clock = Objects.requireNonNull(clock);
    }

    public int execute(int batchSize) {
        int effectiveBatch = Math.max(1, batchSize);
        Instant now = clock.instant();
        List<PhotoAsset> expired = repository.findExpiredUploading(now, effectiveBatch);
        int cleaned = 0;
        for (PhotoAsset asset : expired) {
            repository.save(asset.fail());
            try {
                storage.delete(asset.storageKey());
            } catch (RuntimeException ignored) {
                // Storage cleanup is best-effort; object stays private in the bucket.
            }
            cleaned++;
        }
        return cleaned;
    }
}
