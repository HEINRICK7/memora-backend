package com.memora.application.mural;

import com.memora.application.identity.CurrentUserId;
import com.memora.application.photo.PhotoNotFoundException;
import com.memora.application.ports.MuralPhotoRepository;
import com.memora.application.ports.PhotoAssetRepository;
import com.memora.application.ports.PhotoStorage;
import com.memora.domain.mural.MuralPhoto;
import com.memora.domain.photo.PhotoAsset;
import com.memora.domain.photo.PhotoStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class ListMuralPhotos {
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(15);

    private final MuralPhotoRepository murals;
    private final PhotoAssetRepository photos;
    private final PhotoStorage storage;
    private final CurrentUserId currentUser;
    private final Clock clock;

    public ListMuralPhotos(MuralPhotoRepository murals, PhotoAssetRepository photos, PhotoStorage storage,
                           CurrentUserId currentUser, Clock clock) {
        this.murals = Objects.requireNonNull(murals);
        this.photos = Objects.requireNonNull(photos);
        this.storage = Objects.requireNonNull(storage);
        this.currentUser = Objects.requireNonNull(currentUser);
        this.clock = Objects.requireNonNull(clock);
    }

    public MuralPhotoViewCollection execute(UUID muralId) {
        UUID ownerId = currentUser.get();
        MuralPhotoSnapshot snapshot = murals.findOwned(ownerId, muralId);
        List<MuralPhoto> links = snapshot.photos();
        if (links.isEmpty()) {
            return new MuralPhotoViewCollection(snapshot.mural().version(), List.of());
        }

        List<UUID> photoIds = links.stream().map(MuralPhoto::photoId).toList();
        Map<UUID, PhotoAsset> assetsById = photos.findReadyByIdsAndOwner(photoIds, ownerId.toString()).stream()
                .collect(Collectors.toMap(PhotoAsset::id, Function.identity(), (left, right) -> left));

        List<MuralPhotoView> views = new ArrayList<>(links.size());
        Instant expiresAt = clock.instant().plus(PREVIEW_TTL);
        for (MuralPhoto link : links) {
            PhotoAsset asset = assetsById.get(link.photoId());
            if (asset == null || asset.status() != PhotoStatus.READY) {
                throw new PhotoNotFoundException();
            }
            String previewKey = asset.bestPreviewKey();
            String thumbKey = asset.bestThumbnailKey();
            String previewUrl = storage.presignGet(previewKey, PREVIEW_TTL, cachePolicyFor(previewKey));
            String thumbnailUrl = previewKey.equals(thumbKey)
                    ? previewUrl
                    : storage.presignGet(thumbKey, PREVIEW_TTL, cachePolicyFor(thumbKey));
            views.add(new MuralPhotoView(
                    asset.id(), link.position(), asset.contentType(), asset.sizeBytes(),
                    previewUrl, thumbnailUrl, expiresAt
            ));
        }
        return new MuralPhotoViewCollection(snapshot.mural().version(), views);
    }

    private static String cachePolicyFor(String key) {
        return key.startsWith("derived/") ? PhotoStorage.PRIVATE_DERIVED_CACHE : PhotoStorage.PRIVATE_NO_STORE;
    }
}
