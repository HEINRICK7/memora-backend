package com.memora.application.mural;

import com.memora.domain.mural.MuralPhoto;

import java.util.Objects;

public record MuralPhotoAttachment(MuralPhotoSnapshot snapshot, MuralPhoto link, boolean created) {
    public MuralPhotoAttachment {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(link, "link");
    }
}
