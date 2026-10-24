CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    identity_issuer TEXT NOT NULL,
    identity_subject TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_users_identity UNIQUE (identity_issuer, identity_subject)
);

INSERT INTO users (identity_issuer, identity_subject)
SELECT '${memoraIdentityIssuer}', owner_id
FROM photo_assets
GROUP BY owner_id;

ALTER TABLE photo_assets ADD COLUMN owner_id_user UUID;

UPDATE photo_assets AS photos
SET owner_id_user = users.id
FROM users
WHERE users.identity_issuer = '${memoraIdentityIssuer}'
  AND users.identity_subject = photos.owner_id;

ALTER TABLE photo_assets ALTER COLUMN owner_id_user SET NOT NULL;
DROP INDEX idx_photo_assets_owner_status_created;
ALTER TABLE photo_assets DROP COLUMN owner_id;
ALTER TABLE photo_assets RENAME COLUMN owner_id_user TO owner_id;
ALTER TABLE photo_assets
    ADD CONSTRAINT fk_photo_assets_owner FOREIGN KEY (owner_id) REFERENCES users (id);
CREATE INDEX idx_photo_assets_owner_status_created
    ON photo_assets (owner_id, status, created_at DESC);

CREATE TABLE murals (
    id UUID PRIMARY KEY,
    owner_id UUID NOT NULL REFERENCES users (id),
    idempotency_key UUID NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    title TEXT NOT NULL,
    description TEXT,
    status VARCHAR(20) NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    lock_version BIGINT NOT NULL DEFAULT 0 CHECK (lock_version >= 0),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_murals_title_length CHECK (char_length(title) BETWEEN 1 AND 100),
    CONSTRAINT ck_murals_description_length CHECK (description IS NULL OR char_length(description) <= 500),
    CONSTRAINT uq_murals_owner_idempotency UNIQUE (owner_id, idempotency_key)
);

CREATE INDEX idx_murals_owner_created_id
    ON murals (owner_id, created_at DESC, id DESC);
