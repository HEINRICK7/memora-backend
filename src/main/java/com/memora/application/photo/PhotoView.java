package com.memora.application.photo;

import com.memora.domain.photo.PhotoStatus;

import java.time.Instant;
import java.util.UUID;

public record PhotoView(
        UUID id,
        PhotoStatus status,
        String contentType,
        long sizeBytes,
        String previewUrl,
        Instant previewExpiresAt
) {
}
