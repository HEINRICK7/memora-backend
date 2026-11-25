WITH pending AS (
    SELECT c.mural_id,
           c.photo_id,
           COALESCE((SELECT MAX(existing.position) + 1
                     FROM mural_photos existing WHERE existing.mural_id = c.mural_id), 0)
               + ROW_NUMBER() OVER (PARTITION BY c.mural_id ORDER BY c.created_at, c.id) - 1 AS position
    FROM contributions c
    JOIN photo_assets pa ON pa.id = c.photo_id AND pa.status = 'READY'
    WHERE c.status = 'PENDING'
      AND NOT EXISTS (
          SELECT 1 FROM mural_photos existing
          WHERE existing.mural_id = c.mural_id AND existing.photo_id = c.photo_id
      )
)
INSERT INTO mural_photos (id, mural_id, photo_id, position)
SELECT gen_random_uuid(), mural_id, photo_id, position FROM pending;

ALTER TABLE contributions DROP CONSTRAINT IF EXISTS contributions_status_check;
ALTER TABLE contributions DROP CONSTRAINT IF EXISTS ck_contributions_status;
ALTER TABLE contributions ADD CONSTRAINT ck_contributions_status
    CHECK (status IN ('UPLOADING', 'PENDING', 'PUBLISHED', 'APPROVED', 'REJECTED', 'FAILED'));

ALTER TABLE contributions DROP CONSTRAINT IF EXISTS ck_contributions_decision;
ALTER TABLE contributions ADD CONSTRAINT ck_contributions_decision CHECK (
    (status IN ('UPLOADING', 'PENDING', 'FAILED') AND decided_at IS NULL AND decided_by IS NULL)
    OR (status IN ('APPROVED', 'REJECTED') AND decided_at IS NOT NULL AND decided_by IS NOT NULL)
    OR (status = 'PUBLISHED' AND decided_at IS NOT NULL AND decided_by IS NULL)
);

WITH first_guest_photo AS (
    SELECT DISTINCT ON (c.mural_id) c.mural_id, c.photo_id
    FROM contributions c
    JOIN photo_assets pa ON pa.id = c.photo_id AND pa.status = 'READY'
    WHERE c.status = 'PENDING'
    ORDER BY c.mural_id, c.created_at, c.id
)
UPDATE murals m
SET cover_photo_id = COALESCE(m.cover_photo_id, first_guest_photo.photo_id),
    lock_version = lock_version + 1,
    updated_at = NOW()
FROM first_guest_photo
WHERE m.id = first_guest_photo.mural_id;

UPDATE contributions
SET status = 'PUBLISHED', decided_at = COALESCE(decided_at, NOW()), decided_by = NULL
WHERE status = 'PENDING';
