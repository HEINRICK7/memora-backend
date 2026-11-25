CREATE TABLE guest_albums (
    id UUID PRIMARY KEY,
    mural_id UUID NOT NULL REFERENCES murals(id) ON DELETE CASCADE,
    grant_id UUID NOT NULL REFERENCES share_grants(id) ON DELETE CASCADE,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    display_name VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_guest_album_grant UNIQUE (id, grant_id, mural_id)
);

CREATE INDEX idx_guest_albums_mural_created ON guest_albums(mural_id, created_at);

ALTER TABLE contributions
    ADD COLUMN guest_album_id UUID REFERENCES guest_albums(id) ON DELETE SET NULL;

CREATE INDEX idx_contributions_guest_album ON contributions(guest_album_id, created_at, id);

ALTER TABLE contributions DROP CONSTRAINT IF EXISTS ck_contributions_status;
ALTER TABLE contributions ADD CONSTRAINT ck_contributions_status
    CHECK (status IN ('UPLOADING', 'READY', 'PENDING', 'PUBLISHED', 'APPROVED', 'REJECTED', 'FAILED'));

ALTER TABLE contributions DROP CONSTRAINT IF EXISTS ck_contributions_decision;
ALTER TABLE contributions ADD CONSTRAINT ck_contributions_decision CHECK (
    (status IN ('UPLOADING', 'READY', 'PENDING', 'FAILED') AND decided_at IS NULL AND decided_by IS NULL)
    OR (status IN ('APPROVED', 'REJECTED') AND decided_at IS NOT NULL AND decided_by IS NOT NULL)
    OR (status = 'PUBLISHED' AND decided_at IS NOT NULL AND decided_by IS NULL)
);

ALTER TABLE photo_assets
    ADD COLUMN IF NOT EXISTS thumbnail_storage_key VARCHAR(512),
    ADD COLUMN IF NOT EXISTS thumbnail_width INTEGER,
    ADD COLUMN IF NOT EXISTS thumbnail_height INTEGER,
    ADD COLUMN IF NOT EXISTS thumbnail_size_bytes BIGINT;

CREATE INDEX IF NOT EXISTS idx_photo_assets_uploading_expires
    ON photo_assets (status, intent_expires_at)
    WHERE status = 'UPLOADING';
