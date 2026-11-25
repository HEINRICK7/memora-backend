# MEM-011 — Contratos HTTP e Migração

Todas as alterações de contrato HTTP são **estritamente aditivas e retrocompatíveis** com os clientes existentes.

## Migração `V12__photo_thumbnails_and_cleanup_index.sql`

```sql
ALTER TABLE photo_assets
    ADD COLUMN IF NOT EXISTS thumbnail_storage_key VARCHAR(512),
    ADD COLUMN IF NOT EXISTS thumbnail_width INTEGER,
    ADD COLUMN IF NOT EXISTS thumbnail_height INTEGER,
    ADD COLUMN IF NOT EXISTS thumbnail_size_bytes BIGINT;

CREATE INDEX IF NOT EXISTS idx_photo_assets_uploading_expires
    ON photo_assets (status, intent_expires_at)
    WHERE status = 'UPLOADING';
```

## Endpoints atualizados (aditivos)

### `GET /api/v1/photos?limit=50&cursor=...`
Resposta `200`:
```json
{
  "items": [
    {
      "id": "uuid",
      "status": "READY",
      "contentType": "image/jpeg",
      "sizeBytes": 123456,
      "previewUrl": "https://...",
      "thumbnailUrl": "https://...",
      "previewExpiresAt": "2026-11-25T10:15:00Z"
    }
  ],
  "nextCursor": "opaque-or-null"
}
```

### `GET /api/v1/murals/{muralId}/photos`
Cada item inclui `thumbnailUrl` (opcional/aditivo) além de `previewUrl`.

### `GET /api/v1/shared/{shareToken}?limit=60&offset=0`
`mural` inclui `totalPhotos` e `nextOffset` (`integer | null`), e cada item em `mural.photos` inclui `thumbnailUrl`.

### Novos códigos de erro (RFC 9457 Problem Details)
- `429 Too Many Requests` (`code: "rate_limit_exceeded"`, header `Retry-After`)
- `422 Unprocessable Entity` (`code: "photo_quota_exceeded"` / `"mural_photo_limit_exceeded"` / `"grant_contribution_limit_exceeded"`)
