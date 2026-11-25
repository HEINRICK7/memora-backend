package com.memora.application.photo;

import com.memora.application.ports.CurrentOwner;
import com.memora.application.ports.PhotoAssetRepository;
import com.memora.application.ports.PhotoStorage;
import com.memora.domain.photo.PhotoAsset;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

public final class ListPhotos {
    public static final Duration PREVIEW_TTL = Duration.ofMinutes(15);
    public static final int DEFAULT_LIMIT = 50;
    public static final int MAX_LIMIT = 100;

    private final PhotoAssetRepository repository;
    private final PhotoStorage storage;
    private final CurrentOwner currentOwner;
    private final Clock clock;

    public ListPhotos(
            PhotoAssetRepository repository,
            PhotoStorage storage,
            CurrentOwner currentOwner,
            Clock clock
    ) {
        this.repository = repository;
        this.storage = storage;
        this.currentOwner = currentOwner;
        this.clock = clock;
    }

    public List<PhotoView> execute() {
        return execute(DEFAULT_LIMIT, null).items();
    }

    public PhotoPage execute(Integer requestedLimit, String rawCursor) {
        int limit = requestedLimit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(requestedLimit, MAX_LIMIT));
        DecodedCursor cursor = decodeCursor(rawCursor);

        List<PhotoAsset> fetched = repository.findReadyByOwnerPage(
                currentOwner.ownerId(),
                limit + 1,
                cursor == null ? null : cursor.createdAt(),
                cursor == null ? null : cursor.id()
        );

        boolean hasMore = fetched.size() > limit;
        List<PhotoAsset> pageItems = hasMore ? fetched.subList(0, limit) : fetched;
        List<PhotoView> views = pageItems.stream().map(this::view).toList();
        String nextCursor = hasMore && !pageItems.isEmpty()
                ? encodeCursor(pageItems.getLast())
                : null;
        return new PhotoPage(views, nextCursor);
    }

    private PhotoView view(PhotoAsset asset) {
        Instant expiresAt = clock.instant().plus(PREVIEW_TTL);
        String previewKey = asset.bestPreviewKey();
        String thumbKey = asset.bestThumbnailKey();
        String previewUrl = storage.presignGet(previewKey, PREVIEW_TTL, cachePolicyFor(previewKey));
        String thumbnailUrl = previewKey.equals(thumbKey)
                ? previewUrl
                : storage.presignGet(thumbKey, PREVIEW_TTL, cachePolicyFor(thumbKey));
        return new PhotoView(
                asset.id(), asset.status(), asset.contentType(), asset.sizeBytes(),
                previewUrl, thumbnailUrl, expiresAt
        );
    }

    private static String cachePolicyFor(String key) {
        return key.startsWith("derived/") ? PhotoStorage.PRIVATE_DERIVED_CACHE : PhotoStorage.PRIVATE_NO_STORE;
    }

    private static String encodeCursor(PhotoAsset asset) {
        String raw = asset.createdAt().toEpochMilli() + ":" + asset.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private static DecodedCursor decodeCursor(String rawCursor) {
        if (rawCursor == null || rawCursor.isBlank()) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(rawCursor), StandardCharsets.UTF_8);
            int separator = decoded.indexOf(':');
            if (separator <= 0 || separator >= decoded.length() - 1) {
                throw new IllegalArgumentException("Cursor inválido.");
            }
            Instant createdAt = Instant.ofEpochMilli(Long.parseLong(decoded.substring(0, separator)));
            UUID id = UUID.fromString(decoded.substring(separator + 1));
            return new DecodedCursor(createdAt, id);
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("Cursor de paginação inválido.");
        }
    }

    private record DecodedCursor(Instant createdAt, UUID id) {
    }
}
