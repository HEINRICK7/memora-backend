package com.memora.application.mural;

import java.time.Instant;
import java.util.UUID;

public record MuralPhotoView(UUID photoId, int position, String contentType, long sizeBytes,
                             String previewUrl, Instant previewExpiresAt) {
}
