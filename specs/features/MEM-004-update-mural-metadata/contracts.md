# MEM-004 — Contratos

## MEM-004-API-01 — Patch de metadados

`PATCH /api/v1/murals/{muralId}`

Headers:

```http
If-Match: "2"
Content-Type: application/json
```

O valor corresponde ao campo `version` de `MuralSummary`, codificado como ETag forte. O header é obrigatório.

`GET /api/v1/murals/{muralId}` fornece o ETag forte correspondente à versão da representação. Respostas paginadas não fornecem ETag por item; o cliente deve consultar o detalhe antes de editar.

Body parcial, com pelo menos um campo permitido (adendo MEM-004-R15 permite também `coverPhotoId`):

```json
{
  "title": "Férias em família",
  "description": "Dias de descanso"
}
```

- `title`: string, 1–100 code points depois de trim Unicode; não aceita `null`.
- `description`: string ou `null`, máximo 500 code points depois de trim; vazio vira `null`.
- `coverPhotoId` (adendo MEM-004-R15): UUID ou `null`; omitido preserva, `null` limpa, UUID precisa ser foto READY associada ao mesmo mural.
- Propriedades omitidas permanecem iguais. Propriedades desconhecidas e body vazio são rejeitados.
- Somente rascunhos próprios podem ser alterados.

Resposta `200 OK`: schema `MuralSummary`, `ETag: "3"` e `Cache-Control: private, no-store`. Uma operação semanticamente sem mudança retorna a versão/ETag existente e preserva `updatedAt`.

Respostas: `200`, `400`, `401`, `404`, `409`, `422`, `428`, `503`.

## MEM-004-API-02 — Erros

- ETag malformado: `400`, `code: invalid_if_match`.
- ETag atual diferente: `409`, `code: stale_mural_version`.
- Mural não está em `DRAFT`: `409`, `code: mural_not_editable`.
- Capa inexistente/alheia/não associada: `404 resource_not_found`; foto associada própria mas não READY: `409 photo_not_ready`.
- ETag ausente: `428`, `code: precondition_required`.
- Texto inválido: `422`, `field: title|description` e código correspondente.
- Erros não refletem conteúdo do body, owner, issuer ou subject.

O schema `MuralSummary` de MEM-002 inclui `coverPhotoId: UUID | null` e `publishedAt: RFC 3339 UTC | null`. Foto inexistente/alheia/não associada retorna `404 resource_not_found`; ativo próprio associado não READY retorna `409 photo_not_ready`. A resposta continua usando ETag/version de mural.

## MEM-004-API-03 — ETag do detalhe

`GET /api/v1/murals/{muralId}` continua retornando `MuralSummary` e passa a incluir `ETag: "{version}"`. O valor é forte e pode ser reutilizado sem alteração no `If-Match` do PATCH. A listagem não emite ETag por item.

## MEM-004-API-04 — CORS para edição concorrente

Os origins já configurados podem usar `PATCH`, enviar `If-Match` e ler `ETag` (`Access-Control-Expose-Headers`). Nenhum origin novo é permitido por este contrato.

## MEM-004-API-05 — Selecionar ou limpar capa (adendo MEM-008)

O mesmo `PATCH /api/v1/murals/{muralId}` aceita `coverPhotoId` junto de `title`/`description`. `coverPhotoId` omitido não altera a capa; `null` limpa manualmente e não seleciona substituta; UUID precisa identificar foto READY associada ao mesmo mural. Somente DRAFT próprio, com `If-Match` atual. Mudança efetiva incrementa versão e `updatedAt` uma vez; no-op preserva. A seleção/fallback automática ao anexar/remover vínculo é coberta por MEM-006. Respostas seguem MEM-004-API-01.
