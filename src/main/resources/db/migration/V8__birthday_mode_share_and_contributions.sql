-- Reconcile the deployed birthday-mode schema and keep clean installs compatible
-- with the checked-in V1-V3 migrations. This migration is additive and idempotent.

ALTER TABLE photo_assets
    ADD COLUMN IF NOT EXISTS share_view_status VARCHAR(20) DEFAULT 'ABSENT',
    ADD COLUMN IF NOT EXISTS share_view_storage_key VARCHAR(512),
    ADD COLUMN IF NOT EXISTS share_view_content_type VARCHAR(100),
    ADD COLUMN IF NOT EXISTS share_view_size_bytes BIGINT,
    ADD COLUMN IF NOT EXISTS share_view_width INTEGER,
    ADD COLUMN IF NOT EXISTS share_view_height INTEGER,
    ADD COLUMN IF NOT EXISTS share_view_processor_version VARCHAR(100),
    ADD COLUMN IF NOT EXISTS share_view_ready_at TIMESTAMPTZ;

UPDATE photo_assets SET share_view_status = 'ABSENT' WHERE share_view_status IS NULL;
ALTER TABLE photo_assets ALTER COLUMN share_view_status SET DEFAULT 'ABSENT';
ALTER TABLE photo_assets ALTER COLUMN share_view_status SET NOT NULL;

ALTER TABLE murals
    ADD COLUMN IF NOT EXISTS published_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS cover_photo_id UUID,
    ADD COLUMN IF NOT EXISTS publish_idempotency_key UUID,
    ADD COLUMN IF NOT EXISTS publish_request_hash VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_murals_owner_publish_idempotency
    ON murals(owner_id, publish_idempotency_key)
    WHERE publish_idempotency_key IS NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_murals_cover') THEN
        ALTER TABLE murals ADD CONSTRAINT fk_murals_cover
            FOREIGN KEY (id, cover_photo_id) REFERENCES mural_photos(mural_id, photo_id)
            DEFERRABLE INITIALLY DEFERRED;
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS share_grants (
    id UUID PRIMARY KEY,
    mural_id UUID NOT NULL REFERENCES murals(id),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    scope VARCHAR(20) NOT NULL CHECK (scope IN ('VIEW', 'CONTRIBUTE')),
    idempotency_key UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_share_grants_mural_idempotency UNIQUE (mural_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS idx_share_grants_mural_created_id
    ON share_grants(mural_id, created_at DESC, id DESC);

CREATE TABLE IF NOT EXISTS contributions (
    id UUID PRIMARY KEY,
    mural_id UUID NOT NULL REFERENCES murals(id),
    grant_id UUID NOT NULL REFERENCES share_grants(id),
    photo_id UUID NOT NULL UNIQUE REFERENCES photo_assets(id),
    status VARCHAR(20) NOT NULL CHECK (status IN ('UPLOADING', 'PENDING', 'APPROVED', 'REJECTED', 'FAILED')),
    display_name VARCHAR(40),
    created_at TIMESTAMPTZ NOT NULL,
    decided_at TIMESTAMPTZ,
    decided_by UUID,
    CONSTRAINT ck_contributions_decision CHECK (
        (status IN ('UPLOADING', 'PENDING', 'FAILED') AND decided_at IS NULL AND decided_by IS NULL)
        OR (status IN ('APPROVED', 'REJECTED') AND decided_at IS NOT NULL AND decided_by IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS idx_contributions_mural_created
    ON contributions(mural_id, created_at DESC);
