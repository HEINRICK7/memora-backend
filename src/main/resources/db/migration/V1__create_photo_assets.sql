CREATE TABLE photo_assets (
    id UUID PRIMARY KEY,
    owner_id VARCHAR(255) NOT NULL,
    storage_key VARCHAR(512) NOT NULL UNIQUE,
    content_type VARCHAR(100) NOT NULL,
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0),
    status VARCHAR(20) NOT NULL CHECK (status IN ('UPLOADING', 'READY', 'FAILED', 'DELETED')),
    created_at TIMESTAMPTZ NOT NULL,
    intent_expires_at TIMESTAMPTZ NOT NULL,
    width INTEGER,
    height INTEGER,
    ready_at TIMESTAMPTZ,
    deleted_at TIMESTAMPTZ,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_photo_asset_dimensions CHECK (
        (status IN ('READY', 'DELETED') AND width > 0 AND height > 0 AND ready_at IS NOT NULL)
        OR (status IN ('UPLOADING', 'FAILED') AND width IS NULL AND height IS NULL AND ready_at IS NULL)
    ),
    CONSTRAINT ck_photo_asset_deleted_at CHECK (
        (status = 'DELETED' AND deleted_at IS NOT NULL)
        OR (status <> 'DELETED' AND deleted_at IS NULL)
    )
);

CREATE INDEX idx_photo_assets_owner_status_created
    ON photo_assets (owner_id, status, created_at DESC);
