package com.memora.domain.photo;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record PhotoAsset(
        UUID id,
        String ownerId,
        String storageKey,
        String contentType,
        long sizeBytes,
        PhotoStatus status,
        Instant createdAt,
        Instant intentExpiresAt,
        Integer width,
        Integer height,
        Instant readyAt,
        Instant deletedAt
) {
    public PhotoAsset {
        Objects.requireNonNull(id, "id");
        if (ownerId == null || ownerId.isBlank()) {
            throw new IllegalArgumentException("ownerId é obrigatório.");
        }
        if (storageKey == null || !storageKey.startsWith("originals/private/")) {
            throw new IllegalArgumentException("A chave precisa apontar para originals/private/.");
        }
        Objects.requireNonNull(contentType, "contentType");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(intentExpiresAt, "intentExpiresAt");
        if (status == PhotoStatus.READY && (width == null || height == null || readyAt == null)) {
            throw new IllegalArgumentException("Foto READY precisa de dimensões e data de conclusão.");
        }
        if (status == PhotoStatus.DELETED && deletedAt == null) {
            throw new IllegalArgumentException("Foto DELETED precisa de data de exclusão.");
        }
    }

    public static PhotoAsset beginUpload(
            UUID id,
            String ownerId,
            String storageKey,
            UploadMetadata metadata,
            Instant createdAt,
            Instant intentExpiresAt
    ) {
        return new PhotoAsset(
                id, ownerId, storageKey, metadata.contentType(), metadata.sizeBytes(),
                PhotoStatus.UPLOADING, createdAt, intentExpiresAt, null, null, null, null
        );
    }

    public static PhotoAsset restore(
            UUID id,
            String ownerId,
            String storageKey,
            String contentType,
            long sizeBytes,
            PhotoStatus status,
            Instant createdAt,
            Instant intentExpiresAt,
            Integer width,
            Integer height,
            Instant readyAt,
            Instant deletedAt
    ) {
        return new PhotoAsset(
                id, ownerId, storageKey, contentType, sizeBytes, status, createdAt,
                intentExpiresAt, width, height, readyAt, deletedAt
        );
    }

    public PhotoAsset confirm(InspectedImage actual, Instant now) {
        if (status != PhotoStatus.UPLOADING) {
            throw new PhotoStateConflictException("A intenção não está aguardando conclusão.");
        }
        if (!now.isBefore(intentExpiresAt)) {
            throw new UploadExpiredException();
        }
        if (!contentType.equals(actual.contentType())
                || sizeBytes != actual.sizeBytes()
                || actual.width() <= 0
                || actual.height() <= 0) {
            throw new PhotoValidationException(
                    PhotoValidationException.Code.INVALID_CONTENT,
                    "O conteúdo armazenado não corresponde à intenção de upload."
            );
        }
        return new PhotoAsset(
                id, ownerId, storageKey, contentType, sizeBytes, PhotoStatus.READY,
                createdAt, intentExpiresAt, actual.width(), actual.height(), now, null
        );
    }

    public PhotoAsset fail() {
        if (status != PhotoStatus.UPLOADING) {
            throw new PhotoStateConflictException("Somente uma intenção pendente pode falhar.");
        }
        return new PhotoAsset(
                id, ownerId, storageKey, contentType, sizeBytes, PhotoStatus.FAILED,
                createdAt, intentExpiresAt, null, null, null, null
        );
    }

    public PhotoAsset delete(Instant now) {
        if (status != PhotoStatus.READY) {
            throw new PhotoStateConflictException("Somente uma foto READY pode ser excluída.");
        }
        return new PhotoAsset(
                id, ownerId, storageKey, contentType, sizeBytes, PhotoStatus.DELETED,
                createdAt, intentExpiresAt, width, height, readyAt, now
        );
    }
}
