package com.memora.domain.mural;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Ordered, immutable set of photo links belonging to one mural. */
public final class MuralPhotoCollection {
    private final UUID muralId;
    private final List<MuralPhoto> photos;

    private MuralPhotoCollection(UUID muralId, List<MuralPhoto> photos) {
        this.muralId = Objects.requireNonNull(muralId, "muralId");
        this.photos = validateAndOrder(muralId, photos);
    }

    public static MuralPhotoCollection empty(UUID muralId) {
        return new MuralPhotoCollection(muralId, List.of());
    }

    public static MuralPhotoCollection restore(UUID muralId, List<MuralPhoto> photos) {
        return new MuralPhotoCollection(muralId, photos);
    }

    public UUID muralId() {
        return muralId;
    }

    public List<MuralPhoto> photos() {
        return photos;
    }

    public MuralPhotoCollection append(UUID photoId) {
        Objects.requireNonNull(photoId, "photoId");
        if (photos.stream().anyMatch(photo -> photo.photoId().equals(photoId))) {
            return this;
        }
        List<MuralPhoto> appended = new ArrayList<>(photos);
        appended.add(new MuralPhoto(muralId, photoId, photos.size()));
        return new MuralPhotoCollection(muralId, appended);
    }

    public MuralPhotoCollection reorder(List<UUID> photoIds) {
        Objects.requireNonNull(photoIds, "photoIds");
        if (photoIds.size() != photos.size()) {
            throw new InvalidMuralPhotoOrderException();
        }
        Set<UUID> requested = new HashSet<>(photoIds);
        Set<UUID> existing = new HashSet<>(photos.stream().map(MuralPhoto::photoId).toList());
        if (requested.size() != photoIds.size() || !requested.equals(existing)) {
            throw new InvalidMuralPhotoOrderException();
        }
        if (photos.stream().map(MuralPhoto::photoId).toList().equals(photoIds)) {
            return this;
        }
        List<MuralPhoto> reordered = new ArrayList<>(photoIds.size());
        for (int position = 0; position < photoIds.size(); position++) {
            reordered.add(new MuralPhoto(muralId, photoIds.get(position), position));
        }
        return new MuralPhotoCollection(muralId, reordered);
    }

    public MuralPhotoCollection remove(UUID photoId) {
        Objects.requireNonNull(photoId, "photoId");
        if (photos.stream().noneMatch(photo -> photo.photoId().equals(photoId))) {
            throw new MuralPhotoNotFoundException();
        }
        List<MuralPhoto> remaining = new ArrayList<>();
        for (MuralPhoto photo : photos) {
            if (!photo.photoId().equals(photoId)) {
                remaining.add(new MuralPhoto(muralId, photo.photoId(), remaining.size()));
            }
        }
        return new MuralPhotoCollection(muralId, remaining);
    }

    private static List<MuralPhoto> validateAndOrder(UUID muralId, List<MuralPhoto> input) {
        Objects.requireNonNull(input, "photos");
        List<MuralPhoto> ordered = input.stream()
                .sorted(java.util.Comparator.comparingInt(MuralPhoto::position))
                .toList();
        Set<UUID> photoIds = new HashSet<>();
        for (int position = 0; position < ordered.size(); position++) {
            MuralPhoto photo = ordered.get(position);
            if (!photo.muralId().equals(muralId) || photo.position() != position || !photoIds.add(photo.photoId())) {
                throw new IllegalArgumentException("A coleção precisa ter mural, IDs e posições únicos e contíguos.");
            }
        }
        return List.copyOf(ordered);
    }
}
