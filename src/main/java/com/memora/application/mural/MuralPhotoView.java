package com.memora.application.mural;

import java.time.Instant;
import java.util.UUID;

public record MuralPhotoView(
        UUID photoId,
        int position,
        String contentType,
        long sizeBytes,
        String previewUrl,
        String thumbnailUrl,
        Instant previewExpiresAt
) {
    public MuralPhotoView(UUID photoId, int position, String contentType, long sizeBytes,
                          String previewUrl, Instant previewExpiresAt) {
        this(photoId, position, contentType, sizeBytes, previewUrl, previewUrl, previewExpiresAt);
    }
}
