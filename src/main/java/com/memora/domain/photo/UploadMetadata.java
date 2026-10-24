package com.memora.domain.photo;

import java.util.Set;

public record UploadMetadata(String contentType, long sizeBytes) {
    private static final Set<String> SUPPORTED_TYPES = Set.of("image/jpeg", "image/png");

    public UploadMetadata {
        if (contentType == null || !SUPPORTED_TYPES.contains(contentType)) {
            throw new PhotoValidationException(
                    PhotoValidationException.Code.UNSUPPORTED_MEDIA_TYPE,
                    "O formato precisa ser JPEG ou PNG."
            );
        }
        if (sizeBytes <= 0) {
            throw new PhotoValidationException(
                    PhotoValidationException.Code.INVALID_SIZE,
                    "O arquivo precisa conter dados."
            );
        }
    }

    public static UploadMetadata request(String contentType, long sizeBytes, long maxBytes) {
        if (maxBytes <= 0 || sizeBytes > maxBytes) {
            throw new PhotoValidationException(
                    PhotoValidationException.Code.SIZE_LIMIT_EXCEEDED,
                    "O arquivo excede o limite permitido."
            );
        }
        return new UploadMetadata(contentType, sizeBytes);
    }
}
