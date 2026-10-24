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

Body parcial, com pelo menos um campo permitido:

```json
{
  "title": "Férias em família",
  "description": "Dias de descanso"
}
```

- `title`: string, 1–100 code points depois de trim Unicode; não aceita `null`.
- `description`: string ou `null`, máximo 500 code points depois de trim; vazio vira `null`.
- Propriedades omitidas permanecem iguais. Propriedades desconhecidas e body vazio são rejeitados.
- Somente rascunhos próprios podem ser alterados.

Resposta `200 OK`: schema `MuralSummary`, `ETag: "3"` e `Cache-Control: private, no-store`. Uma operação semanticamente sem mudança retorna a versão/ETag existente e preserva `updatedAt`.

Respostas: `200`, `400`, `401`, `404`, `409`, `422`, `428`, `503`.

## MEM-004-API-02 — Erros

- ETag malformado: `400`, `code: invalid_if_match`.
- ETag atual diferente: `409`, `code: stale_mural_version`.
- Mural não está em `DRAFT`: `409`, `code: mural_not_editable`.
- ETag ausente: `428`, `code: precondition_required`.
- Texto inválido: `422`, `field: title|description` e código correspondente.
- Erros não refletem conteúdo do body, owner, issuer ou subject.

Os schemas comuns de `MuralSummary` e Problem Details permanecem os de MEM-002.

## MEM-004-API-03 — ETag do detalhe

`GET /api/v1/murals/{muralId}` continua retornando `MuralSummary` e passa a incluir `ETag: "{version}"`. O valor é forte e pode ser reutilizado sem alteração no `If-Match` do PATCH. A listagem não emite ETag por item.

## MEM-004-API-04 — CORS para edição concorrente

Os origins já configurados podem usar `PATCH`, enviar `If-Match` e ler `ETag` (`Access-Control-Expose-Headers`). Nenhum origin novo é permitido por este contrato.
