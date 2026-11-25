package com.memora.domain.photo;

import java.util.Objects;

public record ProcessedImage(
        InspectedImage original,
        InspectedImage shareView,
        InspectedImage thumbnail
) {
    public ProcessedImage {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(shareView, "shareView");
        Objects.requireNonNull(thumbnail, "thumbnail");
    }
}
