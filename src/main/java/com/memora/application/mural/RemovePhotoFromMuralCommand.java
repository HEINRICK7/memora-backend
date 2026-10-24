package com.memora.application.mural;

import java.util.UUID;

public record RemovePhotoFromMuralCommand(UUID muralId, UUID photoId, int expectedVersion) {
}
