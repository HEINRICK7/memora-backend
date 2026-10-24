package com.memora.application.mural;

import com.memora.domain.mural.Mural;

import java.util.List;

public record MuralPage(List<Mural> items, String nextCursor) {
    public MuralPage {
        items = List.copyOf(items);
    }
}
