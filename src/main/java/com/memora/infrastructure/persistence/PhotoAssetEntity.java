package com.memora.infrastructure.persistence;

import com.memora.domain.photo.PhotoAsset;
import com.memora.domain.photo.PhotoStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "photo_assets", indexes = {
        @Index(name = "idx_photo_assets_owner_status_created", columnList = "owner_id,status,created_at")
})
public class PhotoAssetEntity {
    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "storage_key", nullable = false, unique = true, length = 512)
    private String storageKey;

    @Column(name = "content_type", nullable = false, length = 100)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PhotoStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "intent_expires_at", nullable = false)
    private Instant intentExpiresAt;

    private Integer width;

    private Integer height;

    @Column(name = "ready_at")
    private Instant readyAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Version
    @Column(nullable = false)
    private long version;

    protected PhotoAssetEntity() {
    }

    private PhotoAssetEntity(PhotoAsset asset) {
        id = asset.id();
        ownerId = UUID.fromString(asset.ownerId());
        storageKey = asset.storageKey();
        contentType = asset.contentType();
        sizeBytes = asset.sizeBytes();
        status = asset.status();
        createdAt = asset.createdAt();
        intentExpiresAt = asset.intentExpiresAt();
        width = asset.width();
        height = asset.height();
        readyAt = asset.readyAt();
        deletedAt = asset.deletedAt();
    }

    public static PhotoAssetEntity fromDomain(PhotoAsset asset) {
        return new PhotoAssetEntity(asset);
    }

    public void updateFromDomain(PhotoAsset asset) {
        ownerId = UUID.fromString(asset.ownerId());
        storageKey = asset.storageKey();
        contentType = asset.contentType();
        sizeBytes = asset.sizeBytes();
        status = asset.status();
        createdAt = asset.createdAt();
        intentExpiresAt = asset.intentExpiresAt();
        width = asset.width();
        height = asset.height();
        readyAt = asset.readyAt();
        deletedAt = asset.deletedAt();
    }

    public PhotoAsset toDomain() {
        return PhotoAsset.restore(
                id, ownerId.toString(), storageKey, contentType, sizeBytes, status, createdAt,
                intentExpiresAt, width, height, readyAt, deletedAt
        );
    }
}
