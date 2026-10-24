package com.memora.application.mural;

import java.util.List;

public record MuralPhotoLinkCollection(List<MuralPhotoLink> items) {
    public MuralPhotoLinkCollection {
        items = List.copyOf(items);
    }
}
