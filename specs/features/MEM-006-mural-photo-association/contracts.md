# MEM-006 — Contratos

**Estado:** aprovado pelo juiz; OpenAPI MEM-006 v1 criado e MEM-001 v1 atualizado junto com o GREEN.\
**Base:** `/api/v1`; rotas privadas usam o `UserId` do principal validado.\
**Rastreabilidade:** MEM-006-R01–R17, MEM-006-AC01–AC19.

## MEM-006-API-01 — Listar fotografias do mural

`GET /api/v1/murals/{muralId}/photos`

- Autorização: somente proprietário atual; mural ausente/alheio retorna `404 resource_not_found`.
- Retorna apenas ativos `READY` associados àquele mural, na posição ascendente.
- Resposta inclui `muralVersion` e `Cache-Control: private, no-store`. O `ETag` forte continua sendo obtido de `GET /murals/{muralId}`; não emitir ETag forte nesta resposta porque `previewUrl` muda quando o presigner emite uma URL nova.
- `previewUrl` é acesso temporário assinado criado depois da autorização, expira em até cinco minutos e não é persistido.

Resposta `200`:

```json
{
  "muralVersion": 2,
  "items": [
    {
      "photoId": "73164257-1e58-4d63-a586-6af868ec350a",
      "position": 0,
      "contentType": "image/jpeg",
      "sizeBytes": 3145728,
      "previewUrl": "SIGNED_TEMPORARY_URL",
      "previewExpiresAt": "2026-10-24T12:05:00Z"
    }
  ]
}
```

IDs, tamanhos e URL acima são ilustrativos. Nunca retornar `ownerId`, nome original, storage key, issuer, subject ou URL permanente. Lista vazia retorna `{"items":[]}`. Respostas: `200`, `400`, `401`, `404`, `503`.

## MEM-006-API-02 — Associar foto pronta

`POST /api/v1/murals/{muralId}/photos`

Headers obrigatórios:

```http
If-Match: "2"
Content-Type: application/json
```

Pedido:

```json
{
  "photoId": "73164257-1e58-4d63-a586-6af868ec350a"
}
```

O ativo precisa estar `READY` e pertencer ao mesmo proprietário do mural. Campo `ownerId` ou propriedades desconhecidas não são aceitos. A associação nova é acrescentada ao fim. O mesmo ativo pode aparecer em outro mural do mesmo proprietário, mas não se duplica no mesmo mural.

- Novo vínculo: `201 Created`, corpo `MuralPhotoLink` (`photoId`, `position`), ETag novo, `Cache-Control: private, no-store`.
- Vínculo já existente e ETag atual: `200 OK`, corpo `MuralPhotoLink` e ETag atual; não incrementa versão/timestamp.
- O corpo de mutação não contém URL assinada e não depende de MinIO. Falha posterior de armazenamento não pode fazer o cliente acreditar que a transação de associação falhou.
- Para retry após resultado de rede ambíguo, o cliente primeiro relê o ETag; um ETag obsoleto sempre recebe conflito, mesmo quando a operação acabou se tornando no-op.

Respostas: `200`, `201`, `400`, `401`, `404`, `409`, `422`, `428`, `503`.

## MEM-006-API-03 — Reordenar fotografias

`PUT /api/v1/murals/{muralId}/photos/order`

Header obrigatório `If-Match: "{muralVersion}"`.

```json
{
  "photoIds": [
    "73164257-1e58-4d63-a586-6af868ec350a",
    "b9cb80af-b8ec-49b4-a52d-448b793d423e"
  ]
}
```

`photoIds` deve conter cada vínculo atual exatamente uma vez. Lista vazia vale apenas para coleção vazia. A atualização de todas as posições ocorre atomicamente.

- Nova ordem: `200 OK`, corpo `MuralPhotoLinkCollection` (IDs e posições), novo ETag e `Cache-Control: private, no-store`.
- Ordem idêntica com ETag atual: `200 OK`, mesmo ETag; `updatedAt` e versão não mudam.
- ID repetido, omitido ou não associado: `422 invalid_photo_order`, sem identificar se um ID pertence a outro owner.
- A mutação não presigna nem lê objetos MinIO; não inclui URL de mídia no corpo.

Respostas: `200`, `400`, `401`, `404`, `409`, `422`, `428`, `503`.

## MEM-006-API-04 — Remover vínculo

`DELETE /api/v1/murals/{muralId}/photos/{photoId}`

Header obrigatório `If-Match: "{muralVersion}"`.

- `204 No Content`, ETag novo e `Cache-Control: private, no-store` quando remove vínculo atual; sem URL de mídia.
- A operação só remove a associação do mural indicado. Normaliza as posições seguintes; não apaga ativo nem arquivo.
- Mural/foto ausente ou alheio retorna `404 resource_not_found`; vínculo inexistente neste mural retorna o mesmo `404`.

Respostas: `204`, `400`, `401`, `404`, `409`, `428`, `503`.

## MEM-006-API-05 — Bloquear exclusão de foto ainda associada

Este feature acrescenta a pré-condição do endpoint existente `DELETE /api/v1/photos/{photoId}` de MEM-001: se existir qualquer `MuralPhoto` para o ativo, responder `409` Problem Details com `code: photo_attached`. Não remover vínculos implicitamente. Sem vínculos, manter o contrato de autorização e remoção já definido por MEM-001.

## Schemas

### `MuralPhotoLink` (corpo de mutação)

- `photoId`: UUID do ativo READY; não é segredo nem prova de autorização.
- `position`: inteiro zero-based, contíguo por mural.

Esse schema não contém URL nem campos de storage e é retornado por POST/PUT. DELETE retorna sem corpo.

### `MuralPhotoView` (somente GET autorizado)

- `photoId`: UUID do ativo READY; não é segredo nem prova de autorização.
- `position`: inteiro zero-based, contíguo por mural.
- `contentType`: tipo real verificado em MEM-001.
- `sizeBytes`: tamanho persistido/verificado.
- `previewUrl`: URL assinada de curta duração, presente somente em `GET /murals/{id}/photos`, após autorização.
- `previewExpiresAt`: timestamp RFC 3339 UTC correspondente ao TTL emitido.

### `MuralPhotoViewCollection`

```json
{ "muralVersion": 2, "items": [{ "photoId": "uuid", "position": 0, "contentType": "image/jpeg", "sizeBytes": 123456, "previewUrl": "SIGNED_TEMPORARY_URL", "previewExpiresAt": "2026-10-24T12:05:00Z" }] }
```

O schema acima é ilustrativo; cada item respeita `MuralPhotoView`. Somente esta consulta pode emitir URL assinada. Coleção é retornada sem paginação nesta feature; limite/quota segue ADR operacional em aberto.

### `MuralPhotoLinkCollection`

```json
{ "items": [{ "photoId": "uuid", "position": 0 }] }
```

Usado somente como resultado de `PUT /photos/order`; nunca inclui URL assinada.

## Erros Problem Details

| HTTP | `code` | Uso |
|---|---|---|
| `400` | `invalid_if_match` / `invalid_request` | ETag ou entrada malformada |
| `401` | padrão de autenticação | Principal ausente/inválido fora do perfil local |
| `404` | `resource_not_found` | Mural/foto/vínculo ausente ou fora do owner |
| `409` | `stale_mural_version` | ETag desatualizado ou conflito otimista concorrente |
| `409` | `mural_not_editable` | Mural não está `DRAFT` |
| `409` | `photo_not_ready` | Ativo próprio ainda não está `READY` |
| `409` | `photo_attached` | Tentativa MEM-001 de apagar ativo ainda vinculado |
| `428` | `precondition_required` | Header `If-Match` ausente |
| `422` | `invalid_photo_order` | A entrada não é permutação completa dos vínculos |
| `503` | `dependency_unavailable` | Persistência indisponível em mutação ou MinIO/presigner indisponível em GET |

Corpo de erro segue RFC 9457 usado pelo backend, com `traceId`, sem valores enviados, identidade, storage key ou signed URL.

## Concorrência, persistência e CORS

- `If-Match` usa o ETag forte de `GET /murals/{id}`. `GET /murals/{id}/photos` expõe `muralVersion`; o cliente pode convertê-lo para o mesmo valor de `If-Match` (`"{muralVersion}"`). Essa coleção não emite ETag forte porque contém URL assinada variável.
- `POST`, `PUT` e `DELETE` abrem uma unidade transacional, validam ownership/status/versão e aplicam uma única mutação lógica. A versão pública aumenta uma vez por mudança efetiva; colisão concorrente não deixa posições parcialmente atualizadas. Mutação não chama MinIO nem cria URL assinada.
- `mural_photos` possui PK ou unique `(mural_id, photo_id)`, unique `(mural_id, position) DEFERRABLE INITIALLY DEFERRED`, FKs para `murals` e `photo_assets`. O índice B-tree criado pela unique de posição também atende à leitura ordenada por `(mural_id, position)`, sem índice secundário duplicado. `PUT /photos/order` atualiza cada posição numa transação e a constraint deferred valida o conjunto final no commit, permitindo uma troca como `0↔1` sem colisão transitória.
- `DELETE /photos/{photoId}` bloqueia/serializa a linha do ativo, confere referências antes de marcar `DELETED` e remover o objeto; rejeita ativo associado para impedir FK órfã e alteração silenciosa de mural. Se anexar vencer, o ativo/objeto ficam READY; se exclusão vencer, a associação posterior recusa o estado DELETED. Falha ao remover o objeto deixa-o privado para limpeza repetível.
- CORS mantém os origins já configurados, permite `GET`, `POST`, `PUT`, `DELETE`, aceita `If-Match`, expõe `ETag` e não amplia origins.
