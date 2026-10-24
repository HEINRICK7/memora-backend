package com.memora.application.mural;

import java.util.UUID;

public record AttachPhotoToMuralCommand(UUID muralId, UUID photoId, int expectedVersion) {
}
