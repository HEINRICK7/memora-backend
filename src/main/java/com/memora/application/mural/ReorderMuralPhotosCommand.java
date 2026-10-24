package com.memora.application.mural;

import java.util.List;
import java.util.UUID;

public record ReorderMuralPhotosCommand(UUID muralId, List<UUID> photoIds, int expectedVersion) {
    public ReorderMuralPhotosCommand {
        photoIds = photoIds == null ? null : List.copyOf(photoIds);
    }
}
