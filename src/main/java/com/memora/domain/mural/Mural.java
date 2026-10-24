package com.memora.domain.mural;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record Mural(
        UUID id,
        UUID ownerId,
        MuralStatus status,
        int version,
        String title,
        String description,
        Instant createdAt,
        Instant updatedAt
) {
    public Mural {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 1) {
            throw new IllegalArgumentException("A versão pública do mural precisa ser positiva.");
        }
        title = normalizeTitle(title);
        description = normalizeDescription(description);
    }

    public static Mural create(UUID id, UUID ownerId, String title, String description, Instant now) {
        String normalizedTitle = normalizeTitle(title);
        String normalizedDescription = normalizeDescription(description);
        return new Mural(id, ownerId, MuralStatus.DRAFT, 1, normalizedTitle, normalizedDescription, now, now);
    }

    public static Mural restore(UUID id, UUID ownerId, MuralStatus status, int version,
                                String title, String description, Instant createdAt, Instant updatedAt) {
        return new Mural(id, ownerId, status, version, title, description, createdAt, updatedAt);
    }

    public Mural updateMetadata(String title, boolean titlePresent,
                                String description, boolean descriptionPresent, Instant now) {
        Objects.requireNonNull(now, "now");
        if (status != MuralStatus.DRAFT) {
            throw new MuralNotEditableException();
        }
        if (!titlePresent && !descriptionPresent) {
            throw new IllegalArgumentException("O patch precisa alterar ao menos um campo.");
        }

        String updatedTitle = titlePresent ? normalizeTitle(title) : this.title;
        String updatedDescription = descriptionPresent ? normalizeDescription(description) : this.description;
        if (updatedTitle.equals(this.title) && Objects.equals(updatedDescription, this.description)) {
            return this;
        }

        return new Mural(id, ownerId, status, Math.addExact(version, 1), updatedTitle, updatedDescription,
                createdAt, now);
    }

    public static String normalizeTitle(String value) {
        String normalized = trimUnicodeWhiteSpace(value);
        int length = codePointLength(normalized);
        if (length < 1 || length > 100) {
            throw new MuralValidationException("title", "invalid_mural_title",
                    "O nome do mural deve ter entre 1 e 100 caracteres.");
        }
        return normalized;
    }

    public static String normalizeDescription(String value) {
        if (value == null) {
            return null;
        }
        String normalized = trimUnicodeWhiteSpace(value);
        if (normalized.isEmpty()) {
            return null;
        }
        if (codePointLength(normalized) > 500) {
            throw new MuralValidationException("description", "invalid_mural_description",
                    "A descrição deve ter no máximo 500 caracteres.");
        }
        return normalized;
    }

    private static int codePointLength(String value) {
        return value.codePointCount(0, value.length());
    }

    private static String trimUnicodeWhiteSpace(String value) {
        if (value == null) {
            return "";
        }
        int start = 0;
        int end = value.length();
        while (start < end) {
            int codePoint = value.codePointAt(start);
            if (!isUnicodeWhiteSpace(codePoint)) {
                break;
            }
            start += Character.charCount(codePoint);
        }
        while (start < end) {
            int codePoint = value.codePointBefore(end);
            if (!isUnicodeWhiteSpace(codePoint)) {
                break;
            }
            end -= Character.charCount(codePoint);
        }
        return value.substring(start, end);
    }

    private static boolean isUnicodeWhiteSpace(int codePoint) {
        return (codePoint >= 0x0009 && codePoint <= 0x000D)
                || codePoint == 0x0020
                || codePoint == 0x0085
                || codePoint == 0x00A0
                || codePoint == 0x1680
                || (codePoint >= 0x2000 && codePoint <= 0x200A)
                || codePoint == 0x2028
                || codePoint == 0x2029
                || codePoint == 0x202F
                || codePoint == 0x205F
                || codePoint == 0x3000;
    }
}
