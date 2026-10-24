package com.memora.application.mural;

import java.util.List;

public record MuralPhotoViewCollection(int muralVersion, List<MuralPhotoView> items) {
    public MuralPhotoViewCollection {
        items = List.copyOf(items);
    }
}
