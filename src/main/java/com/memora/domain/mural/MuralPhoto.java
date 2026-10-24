package com.memora.domain.mural;

import java.util.Objects;
import java.util.UUID;

public record MuralPhoto(UUID muralId, UUID photoId, int position) {
    public MuralPhoto {
        Objects.requireNonNull(muralId, "muralId");
        Objects.requireNonNull(photoId, "photoId");
        if (position < 0) {
            throw new IllegalArgumentException("A posição da fotografia precisa ser não negativa.");
        }
    }
}
