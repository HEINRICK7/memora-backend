package com.memora.application.mural;

import java.util.UUID;

public record UpdateMuralMetadataCommand(UUID muralId, int expectedVersion, String title, boolean titlePresent,
                                         String description, boolean descriptionPresent) {
}
