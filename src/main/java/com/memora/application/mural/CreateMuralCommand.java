package com.memora.application.mural;

import java.util.UUID;

public record CreateMuralCommand(String title, String description, UUID idempotencyKey) { }
