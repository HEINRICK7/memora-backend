package com.memora.application.mural;

import com.memora.domain.mural.Mural;
import com.memora.domain.mural.MuralPhoto;

import java.util.List;
import java.util.Objects;

/** Consistent mural version and ordered links captured by one repository operation. */
public record MuralPhotoSnapshot(Mural mural, List<MuralPhoto> photos) {
    public MuralPhotoSnapshot {
        Objects.requireNonNull(mural, "mural");
        photos = List.copyOf(Objects.requireNonNull(photos, "photos"));
    }
}
