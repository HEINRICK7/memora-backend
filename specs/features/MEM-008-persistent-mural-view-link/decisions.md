# MEM-008 — Decisões propostas

**Estado:** draft; cada decisão abaixo requer revisão do juiz antes de RED/implementação. Nenhuma está aprovada.

## MEM-008-BE-ADR01 — Separar publicar de criar o grant

**Proposta:** `POST /murals/{id}/publish` muda somente o estado do `Mural`; `POST /murals/{id}/grants` cria depois um grant VIEW.

**Motivo:** publicação é uma decisão explícita de conteúdo; compartilhamento é uma capacidade separada e revogável. Falha ao criar link não desfaz mural publicado, e mural publicado sem grant continua privado.

**Consequência:** UI precisa reportar publicação concluída mesmo se criação de grant falhar, permitir retry e não tratar `PUBLISHED` como “acesso público aberto”.

## MEM-008-BE-ADR02 — Publicar somente com conteúdo READY

**Proposta:** exigir ao menos um `MuralPhoto` e validar todos os links como fotos READY do owner. A capa é opcional; se presente, precisa ser READY e associada. Vínculo interno inconsistente de owner retorna `mural_not_publishable` genérico, não `photo_not_ready`. Não salvar `Composition` nem imagem exportada.

**Motivo:** link vazio não satisfaz fluxo MVP de mural compartilhável; estado READY já garante integridade do upload e o grant dá acesso apenas à coleção vinculada.

**Consequência:** publicar não cria Composition nem artefato de composição; antes da transação final prepara variantes SHARE_VIEW pendentes idempotentemente, inclusive READY legado, e só publica após revalidar variantes READY. `coverPhotoId: null` continua válido e frontend pode mostrar a primeira foto como fallback sem persistir seleção.

## MEM-008-BE-ADR03 — Capa como referência à coleção, sem cópia

**Proposta:** estender `PATCH /murals/{id}` do MEM-004 com campo opcional `coverPhotoId` para selecionar/trocar/limpar uma foto vinculada; constraint composta garante o vínculo. A aplicação confere owner e READY dentro da transação. Primeira foto READY associada a coleção vazia vira capa inicial; remover a foto de capa escolhe a primeira restante por posição ou null.

**Motivo:** evita capa apontar para ativo alheio, removido do mural ou inexistente; mantém original único no MinIO.

**Consequência:** migration V5 requer `(mural_id, photo_id)` único no relacionamento existente e faz backfill de capa para a primeira foto READY das coleções existentes. Ao desassociar a foto marcada, aplicação recalcula capa para a primeira restante/null antes de excluir a relação, e o mesmo commit avança a versão uma vez. As alterações já propostas em MEM-004 acceptance/contracts/OpenAPI e MEM-006 acceptance/contracts/OpenAPI/E2E devem ser aprovadas junto com este draft antes do RED/GREEN.

## MEM-008-BE-ADR04 — Tokens aleatórios, hash-only no banco

**Proposta:** gerar 32 bytes com CSPRNG, codificar Base64URL sem padding, armazenar apenas SHA-256 hexadecimal e mostrar `shareUrl` somente na resposta inicial de criação.

**Motivo:** token é credencial bearer de leitura; hash minimiza dano de dump e evita token em leitura/listagem acidental.

**Consequência:** replay idempotente da mesma criação não consegue reconstruir segredo sem persistir valor recuperável. Retorna grantId/metadados e `tokenReturned:false`; UI pode revogar o grant sem token e gerar outro. Avaliar com produto a UX após timeout antes de aprovar.

## MEM-008-BE-ADR05 — TTL MinIO curto e limite explícito de revogação

**Proposta:** signed GET com TTL máximo de 60 segundos e, quando grant expira antes, com TTL igual ao tempo restante. Resolver grant em toda chamada pública. Revogação invalida token para novas chamadas, não URLs emitidas.

**Motivo:** MinIO valida assinatura/expiração sem consultar o banco; revogação imediata de um token não pode recolher URL bearer já emitida.

**Consequência:** API e GET direto do objeto MinIO usam `Cache-Control: private, no-store`; a integração verifica o header do objeto. Caches HTTP conformes não armazenam a resposta, mas a API declara risco residual máximo de 60 segundos para URLs emitidas e não promete remover bytes já baixados/salvos. Integração real verifica URL nova bloqueada após revoke e a antiga ainda dentro do TTL.

## MEM-008-BE-ADR06 — Política VIEW-only no MVP1

**Proposta:** criar somente escopo VIEW nesta feature. Não aceitar `CONTRIBUTE`; vários grants de leitura independentes são permitidos e cada grant pode ser listado/revogado.

**Motivo:** a fase 2 exige ingestão, quota e moderação; visualização não pode implicar permissão de upload.

**Consequência:** `CONTRIBUTE` responde `422 unsupported_grant_scope`; nenhum endpoint de contribuição é descrito nem exposto. Política de quota e limite de links é ADR de produção pendente.

## MEM-008-BE-ADR07 — Conservar idempotência sem reter segredo

**Proposta:** publication key fica associada à transição do mural; create-grant key/request hash fica na row de grant. Chave igual e request igual reproduz resultado sem duplicar; key/payload diferente retorna conflito. Token bruto nunca é guardado para replay.

**Motivo:** publicação e grants são POSTs que podem ser repetidos quando resposta de rede é ambígua; hashes da solicitação protegem contra reuso acidental da chave com outro conteúdo.

**Consequência:** publish replay retorna representação atual; grant replay não reemite `shareUrl`. Scope da chave de publish é `(owner, operação, mural)`; scope de criação de grant é `(mural, key)`.

## MEM-008-BE-ADR08 — Não fixar mecanismo de identidade de produção

**Proposta:** reaproveitar em Compose local o principal fixo existente. Contratos de owner exigem um contexto autenticado do owner, mas OpenAPI registra extensão `x-memora-owner-auth-required` sem declarar Bearer/OIDC/cookie.

**Motivo:** `BACKEND_SPEC.md`, `FRONTEND_SPEC.md` e MEM-002 mantêm provider/session em ADR pendente; escolher um agora criaria contrato de auth não aprovado.

**Consequência:** fora de `local`, rotas exigem identidade validada e respondem 401 sem ela, mas fluxo de produção não é implementável/liberável antes do ADR. E2E atual valida apenas o perfil Compose local e o token VIEW anônimo.

## MEM-008-BE-ADR09 — Distinguir estado de link sem metadata

**Proposta:** responder com códigos seguros distintos (`shared_link_unavailable`, `shared_link_expired`, `shared_link_revoked`, `shared_mural_unavailable`) para alinhar `LINK_INVALID`, `LINK_EXPIRED`, `LINK_REVOKED` e `MURAL_REMOVED` do frontend. Nenhuma resposta contém metadata do mural, owner, título, UUID, identidade ou conteúdo.

**Motivo:** frontend especifica estados de erro distintos; token aleatório de 256 bits reduz viabilidade de enumeração e a resposta não confirma dados de identidade/conteúdo.

**Consequência:** status/code informam somente o motivo de indisponibilidade do link apresentado. Mensagem é genérica por classe e não inclui dados do mural. Invalid/unknown ficam indistinguíveis; expiration/revocation/unavailable podem ser reconhecidos pela pessoa que possui o token.

## MEM-008-BE-ADR10 — Migration aditiva V5 e OpenAPI separado

**Proposta:** criar migration `V5__publish_mural_view_grants_cover.sql` após MEM-001 V4 de variantes e MEM-006/V3 e contrato `MEM-008-v1.yaml`, preservando APIs existentes com campos aditivos `coverPhotoId` e `publishedAt`.

**Motivo:** schema e OpenAPI incrementais mantêm MEM-001/002/004/006 rastreáveis e podem ser validados por teste PostgreSQL/OpenAPI real.

**Consequência:** este draft amplia o contrato atual de MEM-004 (que só permite `title`/`description` no PATCH), adicionando `coverPhotoId` depois de aprovada esta feature; atualizar MEM-004 acceptance/contracts/OpenAPI e `MuralSummary`. Também atualizar MEM-006 para capa inicial/fallback em attach/detach. Essas atualizações documentais são pré-requisito para iniciar RED ou GREEN; IDs de API/AC devem permanecer rastreáveis. Isso atende ao gap documentado em BACKEND_SPEC, que dizia que a capa precisava de spec/migration próprias. Migration precisa fazer backfill, validar constraints em PostgreSQL 17 real e declarar caminho de rollback/release.

## MEM-008-BE-ADR11 — Cache-Control no objeto MinIO assinado

**Proposta:** ampliar o contrato de `PhotoStorage.presignGet(storageKey, ttl)` para incluir, em toda URL GET assinada, o override S3 `response-cache-control=private, no-store`. `S3PhotoStorage` deve configurar `responseCacheControl("private, no-store")` antes de gerar assinatura. A regra vale também para URLs existentes MEM-001/MEM-006; manter TTL privado de cinco minutos e TTL compartilhado de no máximo 60 segundos.

**Motivo:** `Cache-Control` enviado pela API descreve apenas o JSON da API, não a resposta independente do GET de bytes direto ao MinIO.

**Consequência:** antes de iniciar RED/GREEN de MEM-008, MEM-001-AC11 e MEM-006-AC20 precisam passar em Compose com GET real à URL assinada e assert do header MinIO. Evidência atual MEM-006 ainda não cobre esse header. `private, no-store` instrui caches HTTP conformes a não reter os bytes, mas não recolhe mídia já baixada/salva nem encurta o TTL da URL.

## Questões ainda abertas para revisão do juiz

1. Revisar replay do `POST /grants` sem devolver o segredo e a UX de recuperar link perdido após timeout.
2. Definir se `expiresAt: null` e grants sem expiração valem apenas no Compose local; prazo padrão/quota de produção permanece no ADR de compartilhamento.
3. Resolver ADR de autenticação de owner/sessão antes de validar perfil de produção.

## MEM-008-BE-ADR12 — Garantir derivados legados no publish e falhar fechado

**Proposta:** uploads novos geram SHARE_VIEW antes de READY. Publish prepara idempotentemente qualquer SHARE_VIEW pendente antes da transação final de estado; V4 do MEM-001 marca READY legado como PENDING. A transação final bloqueia e revalida mural, versão, coleção, ownership e estado READY das variantes antes de DRAFT→PUBLISHED. GET compartilhado assina somente chave SHARE_VIEW.

**Falha e retry:** erro de decode, write, metadata ou storage deixa o mural DRAFT, mantém status/timestamps/versão e não cria grant nem consome a chave de publicação; tentativa repetida usa o mesmo destino idempotente. GET compartilhado responde erro sanitizado sem URL e nunca tenta original. Isso define somente fluxo MVP local; políticas de quota/retenção/produção continuam pendentes.

**Motivo:** metadata de imagem não confiável exige processamento bounded; original pode conter localização/EXIF e não pode ser exposto quando derivado falha. Preparação fora da transação evita segurar locks durante decode/upload, e revalidação final evita publicar snapshot obsoleto.
