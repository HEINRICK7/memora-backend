# MEM-002 — Contratos

## MEM-002-API-01 — Criar rascunho

`POST /api/v1/murals`

Headers obrigatórios:

```http
Idempotency-Key: 644d8bbf-1498-4e29-8e75-9142b2a4c9c1
Content-Type: application/json
```

Pedido:

```json
{
  "title": "Aniversário da Sofia",
  "description": "Momentos especiais"
}
```

`description` pode ser omitida ou `null`. Antes de persistir, a API remove Unicode White_Space das extremidades; descrição vazia após normalização vira `null`. Título deve ter 1–100 pontos de código, descrição no máximo 500. O contrato não aceita `ownerId`.

Resposta `201 Created`, `Cache-Control: private, no-store`:

```json
{
  "id": "c0f5f693-7160-4e49-9d76-cfaba4edce1c",
  "status": "DRAFT",
  "version": 1,
  "title": "Aniversário da Sofia",
  "description": "Momentos especiais",
  "createdAt": "2026-10-24T12:00:00Z",
  "updatedAt": "2026-10-24T12:00:00Z"
}
```

Mesma chave e mesmo request normalizado: retorna o mesmo `201`, mesmo corpo e header `Idempotency-Replayed: true`. Mesma chave com corpo normalizado diferente: `409`.

Respostas: `201`, `400`, `401`, `409`, `422`, `503`.

## MEM-002-API-02 — Listar murais próprios

`GET /api/v1/murals?limit=20&status=DRAFT&cursor={opaque}`

- `limit`: opcional, padrão 20, intervalo 1–50.
- `status`: opcional; `DRAFT`, `PUBLISHED` ou `ARCHIVED`.
- `cursor`: opcional, Base64URL sem padding, máximo 128 caracteres e vinculado ao mesmo filtro de status.
- Ordem: `createdAt DESC, id DESC`.
- A consulta sempre aplica `ownerId` derivado do principal validado; não existe parâmetro de owner.

Resposta `200 OK`, `Cache-Control: private, no-store`:

```json
{
  "items": [
    {
      "id": "c0f5f693-7160-4e49-9d76-cfaba4edce1c",
      "status": "DRAFT",
      "version": 1,
      "title": "Aniversário da Sofia",
      "description": "Momentos especiais",
      "createdAt": "2026-10-24T12:00:00Z",
      "updatedAt": "2026-10-24T12:00:00Z"
    }
  ],
  "nextCursor": null
}
```

Respostas: `200`, `400`, `401`, `503`.

## MEM-002-API-03 — Obter mural próprio

`GET /api/v1/murals/{muralId}`

Resposta `200 OK`, `Cache-Control: private, no-store`, com o mesmo schema do item de `MEM-002-API-01`. UUID ausente ou de outro owner responde `404` com corpo indistinguível. MEM-004 acrescenta `ETag: "{version}"` para edição concorrente.

Respostas: `200`, `400`, `401`, `404`, `503`.

## Schema compartilhado `MuralSummary`

- `id`: UUID.
- `status`: `DRAFT | PUBLISHED | ARCHIVED`.
- `version`: inteiro positivo; persistence `@Version` começa em 0 e é exposto como `lockVersion + 1`.
- `title`: string normalizada, 1–100 pontos de código Unicode.
- `description`: string ou `null`, normalizada, máximo 500 pontos de código.
- `createdAt`, `updatedAt`: timestamps RFC 3339 UTC; iguais em criação.
- Owner interno, issuer, subject, storage key, hash e idempotency key não são serializados.

## Problem Details

Para validação textual `422`:

```json
{
  "type": "about:blank",
  "title": "Unprocessable Entity",
  "status": 422,
  "detail": "O nome do mural deve ter entre 1 e 100 caracteres.",
  "code": "invalid_mural_title",
  "field": "title",
  "traceId": "f98f4b86-302a-4ca3-a9dd-19f8dcae3550"
}
```

`field` aceita `title` ou `description`. O detalhe descreve a regra sem repetir o valor recebido.

## Identidade e banco

- `users(id UUID PRIMARY KEY, identity_issuer TEXT NOT NULL, identity_subject TEXT NOT NULL, created_at TIMESTAMPTZ NOT NULL, UNIQUE(identity_issuer, identity_subject))`.
- `murals.owner_id UUID NOT NULL REFERENCES users(id)`.
- `murals` persiste chave de idempotência e hash normalizado com unicidade por `(owner_id, idempotency_key)`.
- Flyway placeholder `memoraIdentityIssuer` vem de `OIDC_ISSUER_URI` ou `urn:memora:local`; V2 usa esse issuer ao mapear rows V1 existentes.
- V2 migra owner de fotos, não objetos S3 nem `storage_key`; URLs assinadas não são persistidas.
- Nenhuma resposta inclui `identity_issuer`, `identity_subject`, `owner_id`, `storage_key` ou hash.
