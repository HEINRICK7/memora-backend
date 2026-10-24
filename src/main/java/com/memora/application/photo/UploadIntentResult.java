package com.memora.application.photo;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record UploadIntentResult(
        UUID uploadId,
        String method,
        String uploadUrl,
        Map<String, String> requiredHeaders,
        Instant expiresAt
) {
}
