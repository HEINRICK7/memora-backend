CREATE TABLE mural_photos (
    id UUID PRIMARY KEY,
    mural_id UUID NOT NULL REFERENCES murals (id) ON DELETE CASCADE,
    photo_id UUID NOT NULL REFERENCES photo_assets (id) ON DELETE RESTRICT,
    position INTEGER NOT NULL CHECK (position >= 0),
    CONSTRAINT uq_mural_photos_mural_photo UNIQUE (mural_id, photo_id),
    CONSTRAINT uq_mural_photos_position UNIQUE (mural_id, position) DEFERRABLE INITIALLY DEFERRED
);

