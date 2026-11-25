# MEM-012 — Contratos HTTP

## `GET /api/v1/shared/{shareToken}?limit=60&offset=0`
- Header opcional: `X-Guest-Album-Token: <token>`
- Cada foto em `mural.photos` inclui campo booleano aditivo `ownedByGuest` (`false` por padrão).

## `POST /api/v1/shared/{shareToken}/guest-album/contributions`
- Header: `X-Guest-Album-Token: <token>` (cria o `guest_album` automaticamente se ainda não existir)
- Body: `{ "contentType": "image/jpeg", "sizeBytes": 12345, "displayName": "Ana" }` (`displayName` opcional)

## `POST /api/v1/shared/{shareToken}/guest-album/contributions/{contributionId}/complete?autoPublish=true`
- Header: `X-Guest-Album-Token: <token>`
- Resposta `200`: `{ "contributionId": "uuid", "photoId": "uuid", "status": "PUBLISHED" }` (ou `READY` se `autoPublish=false`).

## `DELETE /api/v1/shared/{shareToken}/guest-album/photos/{photoId}`
- Header obrigatório: `X-Guest-Album-Token: <token>`
- Remove a foto de `mural_photos` somente se foi enviada por aquele `guest_album` naquele mural.
- Resposta `204 No Content` (ou `404 Not Found` se a foto não pertence ao convidado).
