# MEM-008 — Contratos

**Estado:** proposta para revisão; não aprovada.\
**Base:** `/api/v1`. Contrato versionado proposto: `src/main/resources/static/openapi/MEM-008-v1.yaml` (`openapi: 3.1.0`, `info.version: 1.0.0`).\
**Rastreabilidade:** MEM-008-BE-R01–R24, MEM-008-BE-AC01–AC29, MEM-008-BE-API-01–07.

## Matriz R–AC–API

| Requisitos | Critérios de aceite | Contrato HTTP |
|---|---|---|
| R01–R04 | AC01–AC04 | API-01, API-03 |
| R05–R06, R18 | AC05–AC07, AC23–AC24 | API-01, API-02 e extensão de MEM-006 API-04 |
| R07 | AC08 | API-02, API-03, MEM-004 e MEM-006 |
| R08–R10 | AC09–AC12 | API-04 |
| R11 | AC13–AC14 | API-05 |
| R12 | AC15, AC20 | API-06, API-07 |
| R13–R16 | AC16–AC19 | API-07 |
| R17 | AC22 | API-04–API-07 e política de logging/proxy |
| R19 | AC01–AC29 | OpenAPI MEM-008-v1 versionado; todos os APIs BE-01–07 |
| R20 | AC21 | ambiente Compose e ADR de identidade |
| R21 | AC20, AC25 | API-07 e contrato global de `PhotoStorage` |
| R22 | AC26, AC29 | API-07, PhotoStorage SHARE_VIEW |
| R23 | AC27–AC28 | API-03, MEM-001-API-03 e transação de publicação |
| R24 | AC29 | Migration V5 após MEM-001 V4 e OpenAPI MEM-008-v1 |

### Rastreabilidade API–AC

| API | Cenários que validam o contrato |
|---|---|
| API-01 `GET /murals/{muralId}` e MuralSummary | AC01, AC05, AC08, AC21, AC27 |
| API-02 `PATCH /murals/{muralId}` com coverPhotoId | AC05, AC06, AC08 |
| API-03 `POST /murals/{muralId}/publish` | AC01–AC04, AC08, AC27–AC28 |
| API-04 `POST /murals/{muralId}/grants` | AC09–AC12 |
| API-05 `GET /murals/{muralId}/grants` | AC13–AC14 |
| API-06 `DELETE /murals/{muralId}/grants/{grantId}` | AC14–AC15, AC20 |
| API-07 `GET /shared/{shareToken}` | AC15–AC20, AC22, AC25–AC26, AC29 |
| MEM-006-API-02 attach e MEM-006-API-04 detach, com extensão MEM-008 | AC07, AC23–AC24 |

`MEM-008-v1.yaml` define estes `operationId` estáveis: `getOwnedMuralWithCover`, `patchMuralWithCover`, `publishMural`, `createViewGrant`, `listMuralGrants`, `revokeMuralGrant`, `getSharedMuralByToken`. Owner operations usam extensão `x-memora-owner-auth-required: true`; shared GET declara `security: []`.

## Regras comuns

- `ownerId` é sempre derivado de `CurrentUserId`; jamais é aceito em path/body/query.
- Rotas de owner exigem principal autenticado. OpenAPI registra `x-memora-owner-auth-required: true`; o esquema de produção não é fixado enquanto ADR de identidade estiver pendente. Perfil Compose local usa owner fixo `urn:memora:local` / `local-development-owner`.
- Todas as respostas de owner e shared usam `Cache-Control: private, no-store`. Owner reads/mutations preservam `X-Trace-Id`/Problem Details. `If-Match` é ETag forte `"{version}"` do mural em mutações de conteúdo.
- Nunca logar/share token, link completo, `X-Amz-Signature`, URL de mídia, conteúdo de foto ou `storage_key`. Request/trace tags usam rota template `/{shareToken}`, não valor real.
- Erros seguem RFC 9457 (`type`, `title`, `status`, `detail`, `code`, `traceId`); o detalhe nunca ecoa token, texto sensível ou exception externa.

## MEM-008-BE-API-01 — Obter resumo próprio incluindo capa/publicação

`GET /api/v1/murals/{muralId}` (extensão aditiva de MEM-002/MEM-004).

O `MuralSummary` passa a incluir `coverPhotoId: UUID | null` e `publishedAt: RFC 3339 UTC | null`. Em `DRAFT`, `publishedAt` é null. Após publicar, `publishedAt` é persistido. `coverPhotoId`, se não nulo, aponta a uma foto READY associada ao próprio mural. O owner GET continua privado, retorna `ETag: "{version}"` e não presigna imagem; para URL de imagem usa `GET /murals/{muralId}/photos` de MEM-006.

## MEM-008-BE-API-02 — Atualizar metadados e capa

Extensão aditiva de `PATCH /api/v1/murals/{muralId}` (MEM-004).

Headers obrigatórios `If-Match: "{version}"` e `Content-Type: application/json`.

Pedido para selecionar ou trocar:

```json
{ "coverPhotoId": "73164257-1e58-4d63-a586-6af868ec350a" }
```

Pedido para limpar:

```json
{ "coverPhotoId": null }
```

O PATCH mantém as regras MEM-004 para `title`/`description` e acrescenta `coverPhotoId` como campo opcional; ao menos um dos três campos precisa estar presente. Campo omitido não muda. `coverPhotoId: null` limpa a capa sem selecionar substituta. Só `DRAFT` do owner pode mudar. Uma capa não nula precisa ser READY e já estar associada ao mesmo mural. UUID inexistente/alheio/não associado responde `404 resource_not_found`; ativo associado mas não READY responde `409 photo_not_ready`. Campo desconhecido ou body sem campos responde `400 invalid_request`.

- Mudança efetiva de qualquer combinação de metadata/capa: `200` com `MuralSummary`, novo ETag e `Cache-Control: private, no-store`; a versão incrementa uma única vez.
- Mesmo valor atual: `200` no-op com ETag atual; `version`/`updatedAt` não mudam.
- Limpar manualmente retorna `coverPhotoId: null`; associação automática/fallback de detach seguem as regras do domínio abaixo.

Respostas: `200`, `400`, `401`, `404`, `409`, `422`, `428`, `503`.

Como extensão MEM-008 ao MEM-006: primeira associação em coleção vazia escolhe essa foto como capa no mesmo commit; remover vínculo de capa escolhe a primeira foto READY restante por posição, ou null quando vazia. Auto-seleção/fallback ocorre somente por associação inicial/detach; limpar manualmente via PATCH continua null.

## MEM-008-BE-API-03 — Publicar explicitamente

`POST /api/v1/murals/{muralId}/publish`

Headers obrigatórios:

```http
If-Match: "3"
Idempotency-Key: 7c1f49f8-289a-44c9-b1c4-88b5a0f03d79
```

Sem corpo (JSON vazio não é necessário). Requer owner, mural `DRAFT` e ETag atual. Aniversário pode ser publicado sem foto para liberar o QR imediatamente; se houver fotos associadas, todas precisam estar `READY`/do owner. A capa não é obrigatória; se definida, deve continuar válida, READY e associada. Foto própria não READY recebe `409 photo_not_ready`; associação internamente inconsistente de owner recebe `409 mural_not_publishable` genérico e não revela a foto/owner. O uso de uma idempotency key prévia do mesmo owner com outro mural retorna `409 idempotency_conflict`.

Resposta `200 OK`, `Cache-Control: private, no-store`, `ETag` incrementado uma vez:

```json
{
  "id": "c0f5f693-7160-4e49-9d76-cfaba4edce1c",
  "status": "PUBLISHED",
  "version": 4,
  "title": "Aniversário da Sofia",
  "description": "Momentos especiais",
  "coverPhotoId": null,
  "publishedAt": "2026-10-24T12:00:00Z",
  "createdAt": "2026-10-24T11:00:00Z",
  "updatedAt": "2026-10-24T12:00:00Z"
}
```

Mesmo owner/mural/chave repetida retorna a representação publicada, sem nova versão e `Idempotency-Replayed: true`; o retry é reconhecido antes da validação do `If-Match` antigo. Erros de validação não consomem chave. Mesma chave em outro mural do owner: `409 idempotency_conflict`; chave malformada: `400 invalid_idempotency_key`; header ausente: `400 idempotency_key_required`.

Respostas: `200`, `400`, `401`, `404`, `409`, `428`, `503`.

## MEM-008-BE-API-04 — Criar grant VIEW

`POST /api/v1/murals/{muralId}/grants`

Headers `Idempotency-Key: UUID` e `Content-Type: application/json`.

```json
{
  "scope": "VIEW",
  "expiresAt": null
}
```

`expiresAt` é obrigatório no schema (null representa sem expiração). Timestamp deve estar no futuro. Grants com expiração configurada não emitem URL de imagem além do prazo do grant. Escopo fora de `VIEW` e `CONTRIBUTE` retorna `422 unsupported_grant_scope`. O escopo `CONTRIBUTE` e os álbuns privados dos convidados estão em MEM-009. Mural deve ser do owner e estar `PUBLISHED`; o lock/verificação ocorre na mesma transação que a criação.

Primeira criação: `201 Created`, `Cache-Control: private, no-store`, retorna link/segredo uma única vez:

```json
{
  "grantId": "62dce048-b844-4e51-a2a7-418bd18248a6",
  "scope": "VIEW",
  "status": "ACTIVE",
  "createdAt": "2026-10-24T12:00:00Z",
  "expiresAt": null,
  "revokedAt": null,
  "shareUrl": "http://localhost:3300/m/BASE64URL_TOKEN_OPACO",
  "tokenReturned": true
}
```

`shareUrl` usa `MEMORA_PUBLIC_BASE_URL` confiável (Compose local: `http://localhost:${MEMORA_WEB_PORT:-3300}`); nunca construir a partir de `Host` arbitrário. O token tem 43 caracteres Base64URL. Não retornar campo `token` separado.

Mesmo `(muralId, Idempotency-Key)` e request hash: `200`, `Idempotency-Replayed: true`, mesmos metadados, `shareUrl: null`, `tokenReturned: false`; token não é recuperável porque apenas hash é armazenado. Mesmo key com `scope`/expiração diferente: `409 idempotency_conflict`. Owner pode revogar o grant listado sem conhecer token e emitir outro com chave nova.

Respostas: `200`, `201`, `400`, `401`, `404`, `409`, `422`, `503`.

## MEM-008-BE-API-05 — Listar grants do mural

`GET /api/v1/murals/{muralId}/grants?limit=20&cursor={opaque}`

- Só owner pode listar; mural alheio/ausente responde mesmo `404`.
- `limit` opcional, default 20, faixa 1–50. Cursor Base64URL opaco, máximo 128 caracteres, vinculado ao mural; ordem `createdAt DESC, id DESC`; consulta `limit + 1`.
- Inclui todos os estados; `status` deriva de `revokedAt` (`REVOKED`), `expiresAt <= now` (`EXPIRED`), ou `ACTIVE`.

Resposta `200`:

```json
{
  "items": [
    {
      "grantId": "62dce048-b844-4e51-a2a7-418bd18248a6",
      "scope": "VIEW",
      "status": "ACTIVE",
      "createdAt": "2026-10-24T12:00:00Z",
      "expiresAt": null,
      "revokedAt": null
    }
  ],
  "nextCursor": null
}
```

Schema não possui `shareUrl`, token bruto/hash, owner, subject, storage key ou URL assinada. Respostas: `200`, `400`, `401`, `404`, `503`.

## MEM-008-BE-API-06 — Revogar grant

`DELETE /api/v1/murals/{muralId}/grants/{grantId}`

Owner do mural apenas. Primeira revogação preenche `revoked_at`; grant inexistente/alheio responde `404`; grant já revogado retorna `204` sem alterar `revoked_at`. Não exige ETag de conteúdo e não altera `Mural.version`.

Resposta `204 No Content`, `Cache-Control: private, no-store`. A próxima resolução do token é negada. URLs MinIO já emitidas não consultam a linha de grant novamente e podem funcionar até expirarem (máximo 60 segundos, ou tempo restante do grant, o menor).

Respostas: `204`, `401`, `404`, `503`.

## MEM-008-BE-API-07 — Resolver mural por link público VIEW

`GET /api/v1/shared/{shareToken}`. Rota sem autenticação; token é path segment capability, aleatório, mínimo 256 bits, Base64URL sem padding (`^[A-Za-z0-9_-]{43}$`). Resposta `Cache-Control: private, no-store`, `X-Robots-Tag: noindex, nofollow, noarchive`, `Referrer-Policy: no-referrer`.

Com token válido, grant VIEW ativo/não expirado e mural PUBLISHED, resposta `200`:

```json
{
  "mural": {
    "title": "Aniversário da Sofia",
    "description": "Momentos especiais",
    "coverPhotoId": "73164257-1e58-4d63-a586-6af868ec350a",
    "photos": [
      {
        "photoId": "73164257-1e58-4d63-a586-6af868ec350a",
        "position": 0,
        "contentType": "image/jpeg",
        "sizeBytes": 742188,
        "width": 2560,
        "height": 1707,
        "previewUrl": "SIGNED_TEMPORARY_URL",
        "previewExpiresAt": "2026-10-24T12:01:00Z"
      }
    ]
  },
  "permissions": { "canView": true, "canContribute": false }
}
```

IDs e URLs são ilustrativos. `contentType`, `sizeBytes`, `width` e `height` descrevem a variante SHARE_VIEW e não o original. Não retornar ownerId, UUID do mural, identity, storage key, token, grantId, `Composition`, documento exportável ou fotos de outro mural. Se `coverPhotoId` null, consumidor pode usar primeira foto como fallback visual sem gravar capa.

- Cada `previewUrl` é pré-assinada exclusivamente para `share_view_storage_key` READY após validar token/grant/mural/fotos, para objeto privado MinIO; nunca assina `storage_key` original e nunca faz fallback; `previewExpiresAt` corresponde ao prazo efetivo.
- Emitir coleção completa ou `503`; nunca corpo parcial. Cada chamada consulta novamente hash, estado, `expires_at`, `revoked_at`, `status` do mural e associação.
- Token inválido/malformado: `404 shared_link_unavailable`; token expirado: `410 shared_link_expired`; token revogado: `410 shared_link_revoked`; mural ausente/arquivado/não `PUBLISHED`: `404 shared_mural_unavailable`. Cada Problem Details tem detalhe genérico sem metadata do mural/owner; a distinção atende os estados da UI sem retornar título, owner ou UUID.
- Falha de presign/DB: `503 dependency_unavailable`, detalhe genérico.

Respostas OpenAPI propostas: `200`, `404`, `410`, `503`.

Rate limiting contra brute force fica fora desta feature e do Compose local, e é gate obrigatório antes de habilitar compartilhamento público em produção.

## Contratos de persistência e transação

### Adaptação transversal de `PhotoStorage`

O contrato atual `PhotoStorage.presignGet(storageKey, ttl)` deve passar a garantir resposta `Cache-Control: private, no-store` no GET direto do objeto MinIO para todas as URLs GET assinadas. `S3PhotoStorage` configura `responseCacheControl("private, no-store")` no `GetObjectRequest` encapsulado por `GetObjectPresignRequest`; o SDK deve assinar o parâmetro `response-cache-control`. Não alterar a URL depois de assinada. Isso inclui as URLs privadas já emitidas por MEM-001/MEM-006 e o endpoint compartilhado MEM-008. Preservar TTLs existentes: MEM-001/MEM-006 até cinco minutos; MEM-008 até 60 segundos, reduzido se o grant expirar antes. URLs PUT de upload ficam inalteradas. Compose prova isso pelo header de uma resposta MinIO real; presença do parâmetro na URL ou do header JSON da API, isoladamente, não basta.

Migration proposta `V5__publish_mural_view_grants_cover.sql`, aplicada depois da V4 MEM-001 de variantes SHARE_VIEW e V3 MEM-006:

1. Adicionar `published_at`, `cover_photo_id`, `publish_idempotency_key`, `publish_request_hash` nullable a `murals`; constraints para pares de idempotência e status/data; unique owner/publish key.
2. Adicionar FK composta `(murals.id, murals.cover_photo_id)` a `(mural_photos.mural_id, mural_photos.photo_id) DEFERRABLE INITIALLY DEFERRED`. O caso de uso de detach atualiza a capa para a primeira foto READY restante (ou null) antes de excluir vínculo; a FK rejeita commit que deixe capa sem vínculo.
3. Criar `share_grants` com token hash único, scope VIEW, idempotency key/hash, `expires_at`, `revoked_at`, `created_at`, FK para mural e índice de listagem.
4. Backfill de capa em murals existentes com a primeira foto READY pela posição; sem foto READY, manter null. Validar demais registros antes das constraints.

Publish e PATCH bloqueiam/atualizam a linha de mural sob ETag/optimistic lock. Publish prepara antes da transação final variantes SHARE_VIEW pendentes; depois bloqueia e revalida a coleção inteira READY/do mesmo owner, inclusive a capa se houver. A primeira associação em coleção vazia define a capa; detach da capa escolhe a primeira foto READY restante pela posição ou null e atualiza vínculo/capa/ordem na mesma transação. Create grant serializa com status do mural; revoke atualiza só grant. Read shared resolve hash e grants/mural/fotos autorizados antes de qualquer chamada ao presigner. Grants não incrementam versão de conteúdo.

## Segurança OpenAPI e configurações

- Definir `x-memora-owner-auth-required: true` para endpoints do owner; nenhum esquema OIDC/cookie específico até ADR.
- Definir `security: []` para `GET /shared/{shareToken}`; seu token é capability exclusiva da rota. Erros seguros distinguem inválido (`404`), expirado (`410`), revogado (`410`) e mural indisponível (`404`), sem incluir metadata do mural/owner ou outro identificador.
- `MEMORA_PUBLIC_BASE_URL` é configuração obrigatória e confiável para construir shareUrl. Compose local aponta para o browser em `http://localhost:${MEMORA_WEB_PORT:-3300}`; `MINIO_ENDPOINT_PUBLIC=http://localhost:9000` gera URLs que o browser host acessa.
- Produção exige HTTPS, mecanismo de identidade, rate limiting anti-bruteforce e policy de expiração/quota aprovados.

### Contratos de SHARE_VIEW e publicação de READY legado

- Upload novo não atinge `PhotoAsset.READY` até original validado e derivado SHARE_VIEW finalizado. O derivado aplica orientação, remove GPS/EXIF/XMP, limita maior lado a 2560 px sem upscaling, mantém proporção e usa decode/subsampling bounded; dimensões do original não são alteradas.
- Migration MEM-001 V4 adiciona status/key/metadata/version do derivado e marca READY preexistentes como PENDING. Não processa bytes dentro da migration.
- Durante `POST /publish`, antes da transação final, backend gera idempotentemente derivados pendentes da coleção. A transação final revalida status DRAFT, If-Match, coleção, ownership, cada PhotoAsset READY e derivado READY. Qualquer falha antes ou durante revalidação retorna erro seguro e mantém estado do mural sem alteração e sem grant.
- `GET /shared/{shareToken}` carrega e presigna apenas `SHARE_VIEW`; URL, bytes e metadata refletem o preview sanitizado. Se só existir original, ou chave derivada estiver ausente, responde 503 sem fallback.
- A migration MEM-008 é V5, aplicada após V4; não se declara quota, retenção ou backfill operacional de produção concluídos.


## Crosswalk completo com IDs namespaced do frontend

| IDs FE | IDs backend / outras features | Correspondência e decisão para requisito sem endpoint |
|---|---|---|
| MEM-008-FE-R01–MEM-008-FE-R02 / MEM-008-FE-AC01–MEM-008-FE-AC02, MEM-008-FE-AC13 | MEM-002-API-01/02/03; MEM-008-BE-API-01; MEM-008-BE-R20 / MEM-008-BE-ADR08 | Criar/listar/obter continuam MEM-002; BE-API-01 estende MuralSummary. AC13 usa principal local MEM-002; autenticação de produção não cria endpoint neste slice e permanece sob ADR-08. |
| MEM-008-FE-R03 / MEM-008-FE-AC03, MEM-008-FE-AC20 | MEM-008-BE-API-01/02, MEM-008-BE-AC05–MEM-008-BE-AC06; MEM-008-BE-API-03, MEM-008-BE-AC01–MEM-008-BE-AC04 | PATCH metadata/capa e resumo; configuração/publicação passam por BE-API-03. FE-UI-05 é confirmação visual e não adiciona endpoint. |
| MEM-008-FE-R04–MEM-008-FE-R05 / MEM-008-FE-AC04–MEM-008-FE-AC05 | MEM-006-API-02/03/04; MEM-006-AC21/AC22; MEM-008-BE-R05–MEM-008-BE-R06, MEM-008-BE-AC07, MEM-008-BE-AC23–MEM-008-BE-AC24 | Associação/ordem/detach são MEM-006; backend define capa inicial, fallback e integridade de migration. Preservar seleção/retry é estado FE, sem endpoint adicional. |
| MEM-008-FE-R06 / MEM-008-FE-AC06, MEM-008-FE-AC20 | MEM-008-BE-R23, MEM-008-BE-API-03, MEM-008-BE-AC01–MEM-008-BE-AC04, MEM-008-BE-AC27–MEM-008-BE-AC28 | Publish explícito e idempotente; backend prepara SHARE_VIEW legada antes da transação final e falha mantém DRAFT sem grant. FE-UI-05/AC20 confirma o mesmo publish. |
| MEM-008-FE-R07 / MEM-008-FE-AC07 | MEM-008-BE-R08–MEM-008-BE-R10, MEM-008-BE-API-04, MEM-008-BE-AC09–MEM-008-BE-AC12, MEM-008-BE-ADR04/MEM-008-BE-ADR07 | Criar/replay grant VIEW; shareUrl aparece uma vez, token é hash-only. CONTRIBUTE não tem rota neste slice. |
| MEM-008-FE-R08, MEM-008-FE-R15–MEM-008-FE-R16 / MEM-008-FE-AC08, MEM-008-FE-AC15–MEM-008-FE-AC17 | MEM-008-BE-R11–MEM-008-BE-R12, MEM-008-BE-API-04–MEM-008-BE-API-06, MEM-008-BE-AC13–MEM-008-BE-AC15, MEM-008-BE-AC20 | Listar/revogar/criar novo grant usa APIs BE-04–06. Copiar/compartilhar é UI-only (FE-UI-01/AC16), sem endpoint BE; usa shareUrl one-time de BE-API-04 e ADR-04. |
| MEM-008-FE-R09–MEM-008-FE-R11, MEM-008-FE-R13, MEM-008-FE-R17 / MEM-008-FE-AC09–MEM-008-FE-AC10, MEM-008-FE-AC12, MEM-008-FE-AC18 | MEM-008-BE-R13–MEM-008-BE-R17, MEM-008-BE-R21–MEM-008-BE-R22, MEM-008-BE-API-07, MEM-008-BE-AC16–MEM-008-BE-AC20, MEM-008-BE-AC22, MEM-008-BE-AC25–MEM-008-BE-AC29 | Shared GET autoriza conteúdo/URLs, códigos seguros, logging, no-store e SHARE_VIEW. Composition está fora por BE-R03/ADR-02; galeria/lightbox/layout são FE-only sem endpoint e consomem payload BE-API-07. |
| MEM-008-FE-R12 / MEM-008-FE-AC11 | MEM-008-BE-R08, MEM-008-BE-R13, MEM-008-BE-API-04/07, MEM-008-BE-AC09, MEM-008-BE-AC16 | VIEW permite leitura apenas; não existe endpoint BE de mutação por convidado. UI não oferece escrita nem envia request de contribuição. |
| MEM-008-FE-R14 / MEM-008-FE-AC14, MEM-008-FE-AC16–MEM-008-FE-AC18, MEM-008-FE-AC20 | MEM-008-BE-API-01–MEM-008-BE-API-07; MEM-008-BE-AC01–MEM-008-BE-AC29; MEM-001-AC11–AC14; MEM-006-AC20–AC22 | E2E Compose FE compõe resumo, PATCH/capa, attach/detach, publish, grants, shared GET, headers MinIO, EXIF e revogação. Não é endpoint próprio; cada etapa valida API/AC backend correspondente. |
| MEM-008-FE-R18 / MEM-008-FE-AC19 | Sem requisito/endpoint backend correspondente | Gate FE-only para claims de compatibilidade física Safari iOS/Chrome Android. Backend fornece APIs/URLs, mas não atesta aparelhos, teclado, toque ou Web Share nativo. |
| MEM-008-FE-R19 / MEM-008-FE-AC21 | Sem requisito/endpoint backend correspondente | BRAND_SYSTEM, contraste, foco, reduced motion e interação visual são FE-only; nenhum endpoint ou requisito backend é necessário. |
| MEM-008-FE-R20 / MEM-008-FE-AC14, MEM-008-FE-AC22 | MEM-008-BE-R21–MEM-008-BE-R23, MEM-008-BE-API-03/07, MEM-008-BE-AC25–MEM-008-BE-AC29, MEM-001-AC12/AC13 | Shared preview usa exclusivamente SHARE_VIEW sanitizada; original nunca é URL/objeto/fallback. E2E confirma bytes/EXIF/orientação, dimensionamento, cache e falha fechada. |

Mappings de comportamento/endpoint foram conferidos contra os contratos MEM-002/MEM-004/MEM-006 e o OpenAPI proposto MEM-008-BE em 2026-10-24. Os requisitos de layout, cópia/clipboard, aparelhos físicos e identidade visual são de apresentação e não geram API backend própria.
