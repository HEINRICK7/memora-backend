# MEM-004 — Critérios de aceite

Em todos os cenários de PATCH abaixo, o cliente envia o ETag atual em `If-Match`, a menos que o cenário diga que está ausente ou obsoleto.

## MEM-004-AC01 — Atualizar somente o título

**Given** um rascunho próprio na versão 2  
**When** envia `PATCH /api/v1/murals/{id}` com `If-Match: "2"` e novo `title`  
**Then** recebe `200`, título novo, versão 3, `ETag: "3"` e `Cache-Control: private, no-store`  
**And** descrição, status e `createdAt` permanecem iguais.

## MEM-004-AC02 — Atualizar somente a descrição

**Given** um rascunho próprio  
**When** envia somente `description` válido  
**Then** título e demais campos permanecem iguais e a descrição é persistida.

## MEM-004-AC03 — Limpar descrição explicitamente

**Given** um rascunho com descrição  
**When** envia `{"description": null}` com a versão atual  
**Then** recebe `description: null` e a versão avança uma vez.

## MEM-004-AC04 — Preservar campo omitido

**Given** um rascunho com título e descrição  
**When** envia somente novo título  
**Then** descrição persiste sem alteração.

## MEM-004-AC05 — Não criar revisão para no-op

**Given** um rascunho cujo título e descrição normalizados já correspondem ao pedido  
**When** envia PATCH com ETag atual  
**Then** recebe a representação atual  
**And** versão e `updatedAt` não mudam.

## MEM-004-AC06 — Recusar texto inválido

**Given** título vazio, `null` ou acima de 100 code points, ou descrição acima de 500  
**When** envia PATCH  
**Then** recebe `422` com `field` e código estável  
**And** nenhum campo é alterado e o texto original não aparece no erro.

## MEM-004-AC07 — Recusar corpo inválido

**Given** body vazio, não objeto ou com propriedade desconhecida  
**When** envia PATCH  
**Then** recebe `400` e o mural permanece igual.

## MEM-004-AC08 — Exigir versão de leitura

**Given** um PATCH sem `If-Match`  
**When** envia um body JSON válido com pelo menos `title`, sem o header  
**Then** recebe `428` e nada é atualizado.

## MEM-004-AC09 — Recusar versão obsoleta

**Given** mural na versão 4 e cliente enviando `If-Match: "3"`  
**When** envia alteração válida  
**Then** recebe `409` com `stale_mural_version`  
**And** título, descrição e versão seguem inalterados.

## MEM-004-AC10 — Proteger contra updates concorrentes

**Given** duas alterações iniciadas com o mesmo ETag atual  
**When** ambas tentam gravar mudanças diferentes  
**Then** no máximo uma altera a revisão corrente  
**And** a outra recebe `409`, sem sobrescrever silenciosamente a primeira.

## MEM-004-AC11 — Ocultar mural alheio

**Given** ID de mural pertencente a outro owner ou UUID inexistente  
**When** envia PATCH com body válido e um `If-Match` sintaticamente válido  
**Then** ambos retornam o mesmo `404` sem alteração.

## MEM-004-AC12 — Restringir update a rascunho

**Given** mural `PUBLISHED` ou `ARCHIVED`  
**When** envia título válido com o ETag atual  
**Then** recebe `409` com `mural_not_editable` e o estado permanece intacto.

## MEM-004-AC13 — Persistir atualização no Compose

**Given** atualização aceita no PostgreSQL  
**When** a API responde e o serviço reinicia  
**Then** `GET /murals/{id}` retorna os novos metadados e a versão incrementada.

## MEM-004-AC14 — Emitir ETag na leitura do mural

**Given** um mural próprio na versão pública N  
**When** consulta `GET /api/v1/murals/{id}`  
**Then** recebe `ETag: "N"` com `MuralSummary.version: N`  
**And** pode enviar esse valor em `If-Match` no próximo PATCH.

## MEM-004-AC15 — Recusar ETag malformado

**Given** um rascunho próprio e header `If-Match` fora do formato ETag forte decimal  
**When** envia PATCH com body válido  
**Then** recebe `400` e `code: invalid_if_match`  
**And** o mural permanece inalterado.

## MEM-004-AC16 — Permitir PATCH de origem aprovada

**Given** origin configurado na allowlist e preflight com `PATCH`/`If-Match`  
**When** o navegador consulta ou atualiza um mural  
**Then** CORS permite o método/header e expõe `ETag` ao cliente  
**And** um origin fora da allowlist não recebe permissão CORS.
