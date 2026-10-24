package com.memora.application.mural;

import java.time.Instant;
import java.util.UUID;

public record MuralCursor(String statusFilter, Instant createdAt, UUID id) { }
