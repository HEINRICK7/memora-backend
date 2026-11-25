# MEM-008 — Critérios de aceite

Os IDs abaixo vinculam requisito, API e cenário. Salvo quando indicado, owner é resolvido pelo backend, respostas não revelam `ownerId`, e mutações de conteúdo usam o ETag forte atual em `If-Match`.

## MEM-008-BE-AC01 — Publicar explicitamente mural com conteúdo

**Given** um mural próprio `DRAFT`, pelo menos um `MuralPhoto` e todos os ativos associados `READY`\
**When** o proprietário chama `POST /api/v1/murals/{muralId}/publish` com `If-Match` e `Idempotency-Key`\
**Then** recebe `200`, status `PUBLISHED`, `publishedAt`, ETag incrementado uma vez e `Cache-Control: private, no-store`\
**And** `GET /api/v1/murals/{muralId}` retorna `MuralSummary` com `coverPhotoId`/`publishedAt` e ETag atual\
**And** nenhum grant é criado automaticamente e o mural ainda não é legível por token ausente.

## MEM-008-BE-AC02 — Publicar aniversário vazio e recusar foto inconsistente

**Given** mural próprio DRAFT sem fotos\
**When** o proprietário publica com a versão atual\
**Then** recebe `200`, status `PUBLISHED`, sem capa e sem grant automático\
**And** consegue criar um grant `CONTRIBUTE` para gerar o QR.

**Given** mural próprio DRAFT com foto própria não `READY` ou associação internamente inconsistente a outro owner\
**When** tenta publicar com versão atual\
**Then** recebe `409 photo_not_ready` ou erro genérico `409 mural_not_publishable`\
**And** status, versão, `publishedAt`, vínculos e storage permanecem iguais.

## MEM-008-BE-AC03 — Proteger publicação por owner, estado e versão

**Given** mural alheio/inexistente, `PUBLISHED`/`ARCHIVED` ou ETag obsoleto\
**When** solicita publicação\
**Then** recurso alheio e ausente são o mesmo `404`; status inválido ou ETag obsoleto retorna `409`\
**And** não há transição nem acesso público criado.

## MEM-008-BE-AC04 — Repetir publicação com idempotency key

**Given** publicação já confirmada para o mesmo owner/mural/chave\
**When** retry envia a mesma chave, inclusive com a resposta anterior perdida\
**Then** recebe a mesma representação publicada e `Idempotency-Replayed: true` sem nova versão ou timestamp\
**And** reutilizar a chave para outro mural do mesmo owner recebe `409 idempotency_conflict`.

## MEM-008-BE-AC05 — Definir capa apenas entre fotos prontas do mural

**Given** mural próprio `DRAFT` com foto READY associada\
**When** owner chama `PATCH /api/v1/murals/{muralId}` com `coverPhotoId` e ETag atual\
**Then** `coverPhotoId` é persistido, resumo do mural o inclui, versão avança uma vez e ETag é atualizado\
**And** foto alheia/inexistente/não associada recebe `404`; foto associada mas não READY recebe `409 photo_not_ready`.

## MEM-008-BE-AC06 — Limpar ou repetir capa sem duplicar versão

**Given** mural DRAFT com capa configurada\
**When** envia `coverPhotoId: null` para limpar, ou reenvia a capa já selecionada em `PATCH /api/v1/murals/{muralId}`, com ETag atual\
**Then** limpar remove a referência e incrementa versão uma vez\
**And** repetir o valor atual é no-op, preserva `version`/`updatedAt` e exige ETag atual.

## MEM-008-BE-AC07 — Limpar capa ao remover seu vínculo

**Given** mural DRAFT cuja capa é uma foto READY associada\
**When** remove essa foto por `DELETE /api/v1/murals/{muralId}/photos/{photoId}` com `If-Match` atual\
**Then** a mesma transação aponta `coverPhotoId` para a primeira foto READY restante por posição, ou null se não houver fotos, remove o vínculo e compacta posições\
**And** mural incrementa versão/timestamp apenas uma vez, ativo continua READY e objeto MinIO permanece.

## MEM-008-BE-AC08 — Imutabilidade do mural publicado

**Given** mural `PUBLISHED`\
**When** tenta alterar metadados, capa, associação, ordem ou remover foto usando APIs MEM-004/MEM-006/MEM-008\
**Then** recebe `409 mural_not_editable`\
**And** conteúdo publicado, versão e capa permanecem iguais; arquivamento/despublicação seguem fora desta feature.

## MEM-008-BE-AC09 — Criar somente grant VIEW em mural publicado

**Given** mural próprio `PUBLISHED`\
**When** chama `POST /api/v1/murals/{muralId}/grants` com `scope: VIEW`, `expiresAt` e `Idempotency-Key`\
**Then** recebe `201`, `grantId`, status `ACTIVE`, e `shareUrl` opaca mostrada apenas nesta resposta\
**And** novo grant não altera versão/timestamp do mural nem cria permissões de contribuição.

## MEM-008-BE-AC10 — Não criar link de mural ainda em rascunho

**Given** mural próprio `DRAFT` ou `ARCHIVED`\
**When** tenta criar grant VIEW\
**Then** recebe `409 mural_not_publishable` sem gravar grant nem revelar URL.

## MEM-008-BE-AC11 — Idempotência segura na criação do grant

**Given** uma criação de grant confirmada e a resposta original perdida\
**When** repete a mesma chave e payload\
**Then** recebe `200`, mesmos metadados, `Idempotency-Replayed: true`, `shareUrl: null` e `tokenReturned: false`\
**And** mesma chave com expiração/payload diferente recebe `409 idempotency_conflict`; nova chave cria grant distinto.

## MEM-008-BE-AC12 — Recusar escopo de contribuição nesta fase

**Given** owner tenta criar grant com `scope: CONTRIBUTE` ou outro valor\
**When** envia a solicitação\
**Then** recebe `422 unsupported_grant_scope`\
**And** link VIEW existente não permite upload, edição nem aprovação.

## MEM-008-BE-AC13 — Listar grants sem token ou URL secreta

**Given** mural próprio com grants ativos, expirados e revogados\
**When** owner chama `GET /api/v1/murals/{muralId}/grants` e percorre cursor\
**Then** recebe metadados ordenados/paginados (`grantId`, `scope`, status, createdAt, expiresAt, revokedAt)\
**And** corpo não contém token, `shareUrl`, `tokenHash`, storage key, preview URL ou identidade.

## MEM-008-BE-AC14 — Isolar listagem e revogação por owner

**Given** grant de outro owner ou id inexistente\
**When** owner tenta listar pelo mural alheio ou revogar grant\
**Then** respostas de recurso alheio e ausente são indistinguíveis (`404`)\
**And** estado do grant original não muda.

## MEM-008-BE-AC15 — Revogar idempotentemente e bloquear nova resolução

**Given** grant VIEW ativo\
**When** owner chama `DELETE /api/v1/murals/{muralId}/grants/{grantId}`\
**Then** recebe `204`; repetição também recebe `204` sem novo efeito\
**And** próxima chamada com o token ao endpoint público recebe `410 shared_link_revoked` sem metadados ou fotos.

## MEM-008-BE-AC16 — Resolver link válido sem login e sem composição

**Given** grant VIEW ativo/não expirado e mural PUBLISHED com fotos READY\
**When** visitante sem sessão chama `GET /api/v1/shared/{shareToken}`\
**Then** recebe somente mural, título/descrição, `coverPhotoId`, ordem de fotos e URLs temporárias MinIO\
**And** `permissions.canView` é true, `canContribute` é false, nenhuma foto de outro mural aparece e nenhum documento/export de Composition é retornado.

## MEM-008-BE-AC17 — Tratar inválido, expirado e mural indisponível

**Given** token malformado/desconhecido, expirado, revogado ou mural ausente/arquivado/fora de PUBLISHED\
**When** visitante tenta resolver link\
**Then** recebe `404 shared_link_unavailable`, `410 shared_link_expired`, `410 shared_link_revoked` ou `404 shared_mural_unavailable` conforme o caso\
**And** Problem Details não revela owner, título, UUID do mural, token hash ou existência de outros murais.

## MEM-008-BE-AC18 — Emitir URLs MinIO somente depois da autorização

**Given** acesso válido ao grant VIEW\
**When** API constrói a coleção pública\
**Then** cada URL é presigned no bucket privado, expira no máximo em 60 segundos e não excede o `expiresAt` do grant\
**And** resposta usa `private, no-store`, `noindex`, `noarchive` e `Referrer-Policy: no-referrer`\
**And** um GET válido posterior reemite URLs assinadas renovadas para fotos ainda autorizadas.

## MEM-008-BE-AC19 — Falha de presign não retorna coleção parcial

**Given** uma falha de presign em qualquer foto depois de token/grant válidos\
**When** consulta `GET /api/v1/shared/{shareToken}`\
**Then** recebe `503 dependency_unavailable` genérico\
**And** nenhuma URL assinada, token ou item parcial aparece no corpo/log da resposta.

## MEM-008-BE-AC20 — Declarar limite real da revogação

**Given** visitante obteve uma URL MinIO assinada enquanto grant estava ativo\
**When** owner revoga grant e visitante tenta novamente o endpoint do token\
**Then** nova resolução é negada imediatamente\
**And** a URL já emitida ainda pode funcionar até seu TTL residual de no máximo 60 segundos; nenhuma garantia de recolher bytes já baixados/salvos é declarada\
**And** o GET direto ao MinIO da URL previamente emitida responde `Cache-Control: private, no-store`, instruindo caches HTTP conformes a não armazenar os bytes.

## MEM-008-BE-AC21 — Persistência de segredo e ambiente de autenticação

**Given** stack Compose `local` com owner `local-development-owner`\
**When** owner completa o fluxo e visitante abre o link sem login\
**Then** teste E2E real usa backend, PostgreSQL, MinIO e browser dentro do fluxo suportado\
**And** documentação declara que provedor/sessão de produção permanece bloqueado pelo ADR de identidade e não trata perfil local como validação de produção.

## MEM-008-BE-AC22 — Redigir token em logs e traces

**Given** um link VIEW válido usado em `/api/v1/shared/{shareToken}`\
**When** a solicitação passa pelos logs HTTP, auditoria e traces observáveis no Compose\
**Then** caminho registrado contém somente o template/placeholder da rota\
**And** token, URL completa, `X-Amz-Signature` e URL MinIO não aparecem em log, trace ou analytics.

## MEM-008-BE-AC23 — Inicializar capa na primeira associação

**Given** mural DRAFT vazio sem capa\
**When** a primeira foto READY é associada por MEM-006\
**Then** o mesmo commit define essa foto como `coverPhotoId` inicial\
**And** associação e capa avançam a versão/timestamp do mural uma única vez.

## MEM-008-BE-AC24 — Validar migration e integridade da capa no PostgreSQL

**Given** banco PostgreSQL real pós MEM-001/V4 e MEM-006/V3, incluindo murais DRAFT existentes com coleção de fotos READY\
**When** aplica migration V5 de MEM-008 e tenta apontar capa para foto não associada ao mural\
**Then** migration preenche capa existente com primeira foto READY por posição (ou null se vazia) e a FK composta rejeita capa alheia/não vinculada\
**And** unicidade de hash/idempotência e persistência de `published_at`/versão são comprovadas no PostgreSQL real.

## MEM-008-BE-AC25 — Não armazenar em cache bytes de mídia compartilhada (R21)

**Given** um link VIEW válido e uma `previewUrl` compartilhada assinada para MinIO\
**When** um cliente faz GET direto à URL no MinIO real dentro do Compose\
**Then** a resposta do objeto contém `Cache-Control: private, no-store`\
**And** o override `response-cache-control=private, no-store` faz parte da URL assinada gerada pelo adaptador, sem mutação manual da query após assinatura\
**And** `X-Amz-Expires` não excede 60 segundos nem o tempo restante do grant\
**And** o teste não infere o header do `Cache-Control` da resposta JSON da API.

## MEM-008-BE-AC26 — GET compartilhado entrega apenas SHARE_VIEW sanitizado (R22)

**Given** original JPEG grande com GPS EXIF e orientação 6, foto vinculada READY e grant VIEW válido\
**When** o teste Compose busca a previewUrl retornada pelo endpoint compartilhado diretamente no MinIO\
**Then** os bytes correspondem à variante SHARE_VIEW, nunca ao hash do original; orientação visual está correta, proporção é preservada, lado maior ≤2560 px sem upscaling, e GPS/EXIF/XMP não existem\
**And** a variante permanece privada, entrega `Cache-Control: private, no-store` e o original conserva exatamente o hash de entrada.

## MEM-008-BE-AC27 — Preparar READY legado automaticamente ao publicar (R23)

**Given** migration MEM-001 V4 marcou `share_view_status=PENDING` em foto READY ligada a mural DRAFT\
**When** owner chama publish com If-Match e Idempotency-Key válidos\
**Then** o backend gera/verifica SHARE_VIEW idempotentemente antes da transação final e publica somente após revalidar todas as fotos/variantes READY\
**And** migration MEM-008 V5 sucede V4 e a URL pública aponta à variante, nunca ao original.

## MEM-008-BE-AC28 — Falha de derivado não publica nem cria grant (R23)

**Given** foto READY legada pendente e falha injetada no decoder ou no write MinIO SHARE_VIEW\
**When** owner solicita publicação e depois repete a mesma chave após recuperação\
**Then** a primeira resposta é 503 sanitizada e mantém mural DRAFT, versão, updatedAt, publishedAt, coleção e grants intactos\
**And** retry gera o destino derivado idempotentemente e conclui uma única transição; nenhum presign do original ocorre.

## MEM-008-BE-AC29 — Migration V5 e variante sempre fail closed (R22, R24)

**Given** schema V4 de variantes e link VIEW com variante ausente ou estado diferente de READY\
**When** aplica V5 e consulta GET shared\
**Then** V5 é aplicada depois de MEM-001 V4 e preserva o original\
**And** API responde 503 sem URL/bytes parciais, nunca tenta assinar o original e não inclui token, storage key ou detalhes de owner.
