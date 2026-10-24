# MEM-006 — Critérios de aceite

## MEM-006-AC01 — Associar fotografia READY própria

**Given** um mural `DRAFT` e uma fotografia `READY` do mesmo proprietário\
**When** o proprietário envia `POST /api/v1/murals/{muralId}/photos` com `If-Match` atual\
**Then** a API cria um único vínculo na próxima posição zero-based\
**And** responde `201`, `ETag` incrementado e `Cache-Control: private, no-store`\
**And** o corpo contém somente referência/posição, sem URL assinada.

## MEM-006-AC02 — Consultar vínculos ordenados

**Given** um mural próprio com fotografias associadas\
**When** o proprietário chama `GET /api/v1/murals/{muralId}/photos`\
**Then** recebe somente os vínculos do mural na ordem persistida\
**And** cada item contém URL de leitura temporária e expiração\
**And** recebe `muralVersion`, sem ETag forte para o corpo com URLs variáveis\
**And** a resposta não contém `ownerId`, nome original ou storage key.

## MEM-006-AC03 — Não expor URL temporária sem autorização

**Given** um mural inexistente ou pertencente a outro proprietário\
**When** o ator tenta listar fotografias associadas\
**Then** recebe `404` indistinguível nos dois casos\
**And** nenhuma URL de leitura é emitida.

## MEM-006-AC04 — Não anexar fotografia alheia ou inexistente

**Given** um mural próprio `DRAFT` e um ID de ativo inexistente ou de outro owner\
**When** o ator solicita associação\
**Then** recebe o mesmo `404 resource_not_found`\
**And** nenhuma associação ou dado do ativo é revelado.

## MEM-006-AC05 — Exigir estado READY

**Given** um ativo próprio em `UPLOADING`, `FAILED` ou `DELETED`\
**When** o proprietário tenta associá-lo a um mural próprio `DRAFT`\
**Then** recebe `409 photo_not_ready`\
**And** o mural, o ativo e o storage permanecem inalterados.

## MEM-006-AC06 — Repetir associação existente sem duplicar

**Given** um vínculo já existente e o ETag atual\
**When** o proprietário repete a associação do mesmo ativo\
**Then** recebe `200` com o vínculo existente e o ETag atual\
**And** versão, `updatedAt` e quantidade de linhas não mudam\
**And** a resposta não contém URL assinada.

## MEM-006-AC07 — Rejeitar edição de mural não rascunho

**Given** um mural próprio `PUBLISHED` ou `ARCHIVED`\
**When** tenta associar, reordenar ou remover vínculo\
**Then** recebe `409 mural_not_editable`\
**And** coleção, versão e timestamps permanecem iguais.

## MEM-006-AC08 — Reordenar a coleção completa

**Given** um mural próprio `DRAFT` com vínculos A, B e C em posições `0, 1, 2`\
**When** envia a permutação `[C, A, B]` com `If-Match` atual\
**Then** a API persiste posições `C=0, A=1, B=2` atomicamente\
**And** responde `200` com ETag/versão incrementado uma vez\
**And** o corpo contém somente IDs e posições, sem URL assinada.

## MEM-006-AC09 — Recusar reordenação parcial, duplicada ou alheia

**Given** a coleção atual de um mural\
**When** o corpo de reordenação omite/repete um ID ou inclui ID não associado\
**Then** recebe `422 invalid_photo_order`\
**And** nenhuma posição ou versão muda.

## MEM-006-AC10 — Reordenar coleção vazia ou preservar ordem

**Given** um mural sem vínculos, ou a ordem atual já igual ao pedido\
**When** envia a lista vazia ou a ordem idêntica com ETag atual\
**Then** a API responde `200`\
**And** não incrementa versão nem altera `updatedAt`.

## MEM-006-AC11 — Remover somente o vínculo

**Given** um ativo associado ao mural A e também ao mural B do mesmo owner\
**When** o proprietário remove a associação do ativo com A usando o ETag atual\
**Then** responde `204` com ETag incrementado\
**And** o ativo READY, bytes MinIO e vínculo com B permanecem\
**And** posições restantes de A são compactadas de zero até `n-1`.

## MEM-006-AC12 — Bloquear exclusão de ativo associado

**Given** uma fotografia associada a ao menos um mural\
**When** o proprietário chama `DELETE /api/v1/photos/{photoId}`\
**Then** recebe `409 photo_attached`\
**And** nenhum vínculo, metadado ou objeto MinIO é removido.

## MEM-006-AC13 — Exigir If-Match e detectar edição concorrente

**Given** um cliente com ETag desatualizado após outro vínculo ou mutação no mesmo mural\
**When** tenta associar, reordenar ou remover\
**Then** recebe `409 stale_mural_version` sem gravação parcial\
**And** a coleção fica igual ao resultado da operação concorrente válida.

## MEM-006-AC14 — Validar cabeçalho e entrada

**Given** uma mutação sem `If-Match`, com ETag malformado, UUID/corpo inválido ou campos desconhecidos\
**When** a API valida o pedido\
**Then** responde `428 precondition_required` para header ausente, `400 invalid_if_match` para ETag malformado e `400 invalid_request` para UUID/corpo/campos inválidos\
**And** não altera a coleção.

## MEM-006-AC15 — Falha ao presign durante consulta (R04, R12)

**Given** uma falha ao criar URL temporária durante a consulta\
**When** lista os vínculos\
**Then** recebe `503` Problem Details seguro\
**And** não observa vínculo parcial, URL assinada ou credencial em log/resposta de erro.

## MEM-006-AC16 — Falha de banco durante mutação (R08, R09, R16)

**Given** uma indisponibilidade de banco durante associação, reordenação ou remoção\
**When** a operação tenta persistir\
**Then** recebe `503` sem URL assinada no pedido ou resposta\
**And** a operação é atômica: ou todos os vínculos/posições e a versão persistem, ou nada muda.

## MEM-006-AC17 — Corrida entre associação e exclusão do ativo (R13, R15; MEM-001-R11/R12)

**Given** um ativo READY sem vínculos e seu objeto privado no MinIO\
**When** associação ao mural e `DELETE /api/v1/photos/{photoId}` concorrem\
**Then** as operações serializam pela linha do ativo e um único resultado se confirma:\
- se associação vence, ela cria o vínculo; exclusão responde `409 photo_attached`; ativo continua READY e objeto permanece; ou\
- se exclusão vence, ativo fica DELETED; associação responde `409 photo_not_ready`, não cria vínculo e nenhum presign futuro é emitido.\
**And** não há violação de FK, foto DELETED associada ou objeto removido enquanto existir vínculo\
**And** falha na limpeza após exclusão aceita deixa objeto privado e permite retry.

## MEM-006-AC18 — Trocar posições sem colisão intermediária no PostgreSQL (R05, R14, R16)

**Given** vínculos A e B nas posições 0 e 1, com constraint `(mural_id, position) DEFERRABLE INITIALLY DEFERRED`\
**When** uma reordenação válida troca A e B dentro da transação\
**Then** PostgreSQL valida unicidade do conjunto final no commit e mantém A=1, B=0\
**And** a integração real em PostgreSQL confirma que não há colisão transitória, posição duplicada ou resultado parcial.

## MEM-006-AC19 — Permitir reordenação via CORS sem ampliar origins (R17)

**Given** um origin já configurado para a aplicação\
**When** o navegador envia preflight `OPTIONS` para a rota de reordenação com método `PUT` e header `If-Match`\
**Then** a resposta permite `PUT`, `If-Match` e `Content-Type` e expõe `ETag` nas respostas da API\
**And** um origin não configurado não recebe `Access-Control-Allow-Origin`.
