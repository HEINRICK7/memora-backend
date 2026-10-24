package com.memora.domain.photo;

public record InspectedImage(String contentType, long sizeBytes, int width, int height) {
    public InspectedImage {
        if (contentType == null || sizeBytes <= 0 || width <= 0 || height <= 0) {
            throw new PhotoValidationException(
                    PhotoValidationException.Code.INVALID_CONTENT,
                    "O arquivo não contém uma imagem válida."
            );
        }
    }
}
