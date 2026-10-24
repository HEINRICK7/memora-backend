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
import java.util.Objects;
import java.util.UUID;

public final class ListMuralPhotos {
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(5);

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
        List<MuralPhotoView> views = new ArrayList<>(snapshot.photos().size());
        for (MuralPhoto link : snapshot.photos()) {
            PhotoAsset asset = photos.findByIdAndOwner(link.photoId(), ownerId.toString())
                    .orElseThrow(PhotoNotFoundException::new);
            if (asset.status() != PhotoStatus.READY) {
                throw new PhotoNotFoundException();
            }
            Instant expiresAt = clock.instant().plus(PREVIEW_TTL);
            String previewUrl = storage.presignGet(asset.storageKey(), PREVIEW_TTL);
            views.add(new MuralPhotoView(asset.id(), link.position(), asset.contentType(), asset.sizeBytes(),
                    previewUrl, expiresAt));
        }
        return new MuralPhotoViewCollection(snapshot.mural().version(), views);
    }
}
