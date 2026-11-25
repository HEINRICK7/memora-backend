# MEM-008 — Publicar mural e compartilhar link de visualização

**Estado:** draft para revisão do juiz; ainda não aprovado para implementação.\
**Fontes:** `BACKEND_SPEC.md`, `memora-frontend/FRONTEND_SPEC.md`, `memora-frontend/BRAND_SYSTEM.md`, `AGENTS.md`, `SDD_TDD_PROTOCOL.md`, MEM-002, MEM-004 e MEM-006.

## Problema e usuários

O proprietário precisa concluir explicitamente um mural rascunho, escolher uma capa, criar um link de leitura para familiares/amigos, consultar os links existentes e revogá-los. Uma pessoa convidada sem conta deve conseguir abrir somente um link `VIEW` válido e ver as fotografias daquele mural.

Publicar é uma transição de estado de `Mural`; conceder acesso é uma ação separada por `ShareGrant`. A publicação não torna o mural navegável ou indexável publicamente por si só. O token de um grant `VIEW` é a capacidade de leitura.

## Escopo

- Transição explícita `DRAFT -> PUBLISHED`, com validação de conteúdo e controle otimista de versão.
- Seleção e leitura de `coverPhotoId` no mural; limpeza atômica da capa quando a foto de capa for desassociada enquanto o mural está em `DRAFT`.
- Criar, listar e revogar grants somente de escopo `VIEW`.
- Resolver um link por token opaco e retornar metadados do mural e URLs temporárias MinIO das fotografias prontas.
- Migration PostgreSQL, OpenAPI versionado, controles de privacidade e evidências de RED, integração real e E2E Compose.

## Fora de escopo

- `Composition`, templates, documento de edição, imagem exportada e renderização. Este feature publica o `Mural` como coleção de fotografias; não salva nem exporta composição.
- Grants `CONTRIBUTE`, upload anônimo, fila de moderação, aprovação de contribuições, senha de link, limites/quota de links, despublicação, arquivamento e exclusão de mural.
- Escolha de provedor ou sessão de autenticação de produção. A decisão continua no ADR de identidade pendente.
- Revogação retroativa de bytes baixados ou de URLs MinIO já emitidas.

## Pré-condições

- O proprietário foi resolvido para `UserId` interno no backend; o cliente não informa `ownerId`.
- O mural foi criado por MEM-002; fotos READY e vínculos ordenados seguem MEM-001/MEM-006.
- `If-Match` usa a versão pública do mural (`ETag: "{version}"`) nas alterações de capa e publicação.
- A stack local é Docker Compose. No perfil `local`, o owner é o principal fixo `urn:memora:local` / `local-development-owner`, como em MEM-002; isso não comprova autenticação de produção.

## Requisitos rastreáveis

- **MEM-008-BE-R01:** somente ação explícita do proprietário publica; `DRAFT -> PUBLISHED` é transição atômica e o mural publicado não fica acessível sem grant ativo.
- **MEM-008-BE-R02:** publicar exige `If-Match` atual, `Idempotency-Key` UUID e mural próprio em `DRAFT`; uma gravação concorrente ou estado incompatível retorna conflito sem efeito parcial.
- **MEM-008-BE-R03:** no Modo Aniversário, publicação não exige foto inicial; isso permite gerar o QR assim que a festa é criada. Se houver fotos associadas, todas precisam estar `READY` e pertencer ao mesmo owner; foto própria não READY resulta em `photo_not_ready`, associação inconsistente a outro owner resulta no erro genérico `mural_not_publishable`; a publicação não depende de `Composition`.
- **MEM-008-BE-R04:** publicação incrementa a versão pública uma vez, registra `publishedAt` em UTC e mantém título, descrição, owner e coleção; retry com a mesma chave e mesmo mural reproduz a publicação sem nova transição.
- **MEM-008-BE-R05:** `coverPhotoId` é opcional. A primeira foto READY associada ao mural inicia como capa; depois o proprietário pode selecionar/trocar/limpar a capa por `PATCH /murals/{id}` somente em `DRAFT`. Quando definida, identifica foto `READY` associada ao mesmo mural e owner. `If-Match` é obrigatório e uma mudança efetiva incrementa versão/timestamp uma vez.
- **MEM-008-BE-R06:** ao desassociar em `DRAFT` a foto selecionada como capa, a mesma transação aponta `coverPhotoId` para a primeira foto READY restante na ordem (ou null se não restar foto), remove o vínculo, normaliza posições e incrementa versão/timestamp uma única vez. O arquivo READY no MinIO continua existindo.
- **MEM-008-BE-R07:** depois de `PUBLISHED`, não se altera metadado, capa ou coleção por estes endpoints; mutações de MEM-004/MEM-006 continuam retornando conflito. Política de edição/despublicação exige feature posterior.
- **MEM-008-BE-R08:** somente mural próprio `PUBLISHED` pode receber grants novos; esta feature aceita apenas escopo `VIEW`. Um grant jamais confere upload, edição, aprovação ou acesso a outro mural.
- **MEM-008-BE-R09:** cada grant usa token opaco criptograficamente aleatório de 256 bits codificado Base64URL sem padding; PostgreSQL guarda somente SHA-256 hexadecimal do token e metadados. Token bruto e `shareUrl` aparecem somente na resposta inicial de criação, nunca em listagem, logs, analytics, erros ou auditoria.
- **MEM-008-BE-R10:** criação de grant exige `Idempotency-Key` UUID por mural. Repetir chave e conteúdo retorna o mesmo grant e metadados, sem reemitir segredo; reutilizar chave com outro conteúdo retorna `409 idempotency_conflict`.
- **MEM-008-BE-R11:** proprietário lista grants do próprio mural, ativos, expirados e revogados, com paginação estável. A lista contém apenas metadados; não contém token, `shareUrl`, hash do token ou URL de mídia.
- **MEM-008-BE-R12:** owner pode revogar somente grant do próprio mural. Revogação é idempotente e bloqueia imediatamente novas resoluções daquele token; uma URL MinIO já emitida continua potencialmente utilizável até sua expiração.
- **MEM-008-BE-R13:** endpoint público exige token válido e grant `VIEW` não revogado/não expirado, mural `PUBLISHED`, e revalida essas condições em cada chamada. A resposta inclui apenas metadados mínimos e fotos associadas/prontas daquele mural.
- **MEM-008-BE-R14:** URLs de leitura são pré-assinadas sob demanda, após autorização, pelo MinIO privado, com TTL máximo de 60 segundos e nunca persistidas. Quando o grant tem expiração, TTL é o menor entre 60 segundos e o tempo restante do grant; novo GET autorizado emite URLs renovadas.
- **MEM-008-BE-R15:** resposta pública é atômica: falha ao pré-assinar qualquer foto devolve `503` seguro sem coleção parcial ou URLs já emitidas no corpo. Respostas de grants e mural compartilhado usam `Cache-Control: private, no-store`; link não indexável e resposta aplica `X-Robots-Tag: noindex, nofollow, noarchive` e `Referrer-Policy: no-referrer`.
- **MEM-008-BE-R16:** token inválido, expirado, revogado e mural indisponível podem produzir status/códigos seguros distintos exigidos pelo frontend, sem revelar proprietário, título, UUID, identidade, conteúdo ou existência de outros murais. Token possui 256 bits de entropia.
- **MEM-008-BE-R17:** logs, métricas, traces e auditoria usam template de rota/ID de correlação sem token bruto, `shareUrl`, URL assinada, conteúdo de foto ou segredo. A camada HTTP e proxy devem redigir o segmento `{shareToken}`.
- **MEM-008-BE-R18:** migration PostgreSQL registra `published_at`, `cover_photo_id`, grant/hash/idempotência/expiração/revogação, constraints e índices. Constraint relacional garante que capa é um `MuralPhoto` do próprio mural; aplicação valida status READY e ownership.
- **MEM-008-BE-R19:** contratos v1 são descritos no artefato versionado `src/main/resources/static/openapi/MEM-008-v1.yaml`; payloads de owner e convidado não serializam issuer, subject, ownerId, token hash ou storage key.
- **MEM-008-BE-R20:** Compose local documenta URL pública de aplicação e MinIO acessível pelo navegador e principal local fixo. Produção exige ADR de identidade/sessão e política de expiração/quota antes de habilitar owners ou links públicos.
- **MEM-008-BE-R21:** toda URL GET assinada de mídia emitida por `PhotoStorage`, inclusive leitura compartilhada MEM-008, retorna `Cache-Control: private, no-store` na resposta direta do MinIO. O adaptador S3 assina o override `response-cache-control=private, no-store`; resposta JSON da API não substitui essa política de objeto. TTL compartilhado permanece no máximo 60 segundos; TTL privado MEM-001/MEM-006 continua cinco minutos.
- **MEM-008-BE-R22:** `GET /shared/{shareToken}` assina exclusivamente a chave privada `SHARE_VIEW` READY e seus metadados derivados; nunca assina `storage_key` original nem faz fallback. Variante ausente, pendente ou falha de leitura/presign resulta em falha segura sem URL, bytes ou coleção parcial.
- **MEM-008-BE-R23:** antes da transação final de publicação, o backend prepara idempotentemente toda variante SHARE_VIEW pendente das fotos READY associadas, incluindo READY legado marcado PENDING pela V4 do MEM-001. A transação final bloqueia mural/coleção, revalida ETag, estado, ownership, conteúdo e variantes READY; só então altera DRAFT→PUBLISHED. Falha de geração não consome idempotency key nem muda status, versão, updatedAt/publishedAt e não cria grant; retry é idempotente.
- **MEM-008-BE-R24:** a migration de publicação/grants/capa é V5, aplicada somente após MEM-001 V4 de variantes SHARE_VIEW. V5 é aditiva, preserva originais e grava estado de publicação/capa/grants sem token bruto.

## Estados e invariantes

- **Publicação:** `DRAFT -> PUBLISHED`; transição única após preparar variantes SHARE_VIEW pendentes fora da transação final e revalidar tudo sob lock. `ARCHIVED` não é criada por MEM-008 e nunca autoriza GET compartilhado. Nenhuma transição automática ocorre ao criar/revogar grant.
- **Conteúdo publicável:** um Modo Aniversário pode começar sem fotos. Todos os vínculos existentes precisam referenciar `PhotoAsset.READY` do mesmo owner. `coverPhotoId` pode ser null; se preenchido, é membro da coleção.
- **Capa:** `PATCH` com `coverPhotoId: null` limpa escolha manualmente e não seleciona outra foto. Remover a foto que está como capa seleciona a primeira READY restante por posição, ou null se não houver fotos. A primeira associação em coleção vazia inicia a capa.
- **Versão:** capa e publicação compartilham a versão pública do mural e `If-Match` de MEM-004/006. Grant create/list/revoke não incrementa versão de conteúdo do mural.
- **Grant:** somente `VIEW`; vários links por mural são permitidos, cada um revogável. `expiresAt: null` significa sem expiração do grant no perfil local; política de expiração obrigatória/padrão e quota continuam ADR antes de produção.
- **Idempotência do grant:** criação original retorna o link uma única vez. Replay de mesma chave retorna `200`, metadados existentes, `shareUrl: null` e `tokenReturned: false`, pois o banco nunca guarda segredo recuperável. Owner revoga grant sem URL conhecida pela listagem e cria outro com nova chave.
- **Revogação real:** invalidação do token é imediata para novas chamadas à API. URLs pré-assinadas concedidas antes da revogação são credenciais bearer independentes e podem ler até no máximo 60 segundos, ou até seu TTL menor. A resposta direta MinIO leva `Cache-Control: private, no-store`, instruindo caches HTTP conformes a não armazenar esses bytes; isso não recolhe imagens já baixadas/salvas.

## Persistência proposta

`V5__publish_mural_view_grants_cover.sql` parte do schema pós MEM-001/V4 de variantes SHARE_VIEW e MEM-006/V3:

- `murals.published_at TIMESTAMPTZ NULL` e `murals.cover_photo_id UUID NULL`.
- `murals.publish_idempotency_key UUID NULL` e `murals.publish_request_hash CHAR(64) NULL`, ambos nulos ou ambos preenchidos; unique `(owner_id, publish_idempotency_key)`.
- FK composta `(murals.id, murals.cover_photo_id) -> mural_photos(mural_id, photo_id) DEFERRABLE INITIALLY DEFERRED`; aplicação recalcula a capa antes de remover vínculo e a FK recusa commit que deixe capa sem associação. Estado READY/owner é validado sob transação/lock.
- Migration preenche a capa de murals existentes com a primeira foto READY da ordem (posição zero); sem foto READY, conserva null. Novos vínculos definem capa automaticamente só quando são o primeiro item da coleção e ainda não há capa.
- CHECK relaciona status e data (`DRAFT` sem `published_at`; `PUBLISHED` e `ARCHIVED` com `published_at`). Registros preexistentes são validados antes de ativar a constraint.
- `share_grants(id UUID PK, mural_id FK, token_hash CHAR(64) UNIQUE, scope CHECK VIEW, idempotency_key UUID, request_hash CHAR(64), expires_at, revoked_at, created_at)`; unique `(mural_id, idempotency_key)` e índices para listagem `(mural_id, created_at DESC, id DESC)`.
- Datas em `TIMESTAMPTZ`; `expires_at`/`revoked_at` são persistidos, sem salvar URL/token bruto. Auditoria armazena apenas grantId, muralId, actorId pseudonimizado, ação e timestamp.

## Privacidade, autenticação e dependências

- Rotas de owner derivam owner do contexto confiável `CurrentUserId`; absent/foreign mural/grant usa `404` indistinguível. OpenAPI usa extensão `x-memora-owner-auth-required: true` enquanto o mecanismo de autenticação de produção está aberto.
- Perfil Compose local usa o owner fixo já descrito em MEM-002. A configuração local deve fornecer `MEMORA_PUBLIC_BASE_URL=http://localhost:${MEMORA_WEB_PORT:-3300}` e manter `MINIO_ENDPOINT_PUBLIC=http://localhost:9000` para URLs abertas no navegador host. Testes de API executados dentro da rede podem sobrescrever endpoint público somente no serviço em teste.
- Fora do perfil local, endpoints de owner respondem `401` quando não houver principal válido; provedor, sessão, CSRF e configuração de proxy dependem do ADR de identidade. Não declarar produção autenticada enquanto o ADR não estiver aprovado.
- Rota de leitura compartilhada não exige login, mas o token é a única autorização. Não emitir Open Graph com fotografia ou título, não permitir indexação, e não enviar token a analytics.
- MinIO continua bucket privado; a API presigna cada imagem autorizada. Uma falha de storage não abre bucket nem muda status publicado.

## Erros principais

- `400 invalid_request` / `invalid_if_match` / `invalid_idempotency_key`: entrada inválida.
- `401`: rota de owner sem principal fora do profile local.
- `404 resource_not_found`: recurso privado ausente/alheio; mesmo corpo para os dois.
- Token inválido/malformado: `404 shared_link_unavailable`; expirado: `410 shared_link_expired`; revogado: `410 shared_link_revoked`; mural ausente/arquivado/não `PUBLISHED`: `404 shared_mural_unavailable`. Detalhes são genéricos e não incluem metadata do mural/owner.
- `409 stale_mural_version`, `mural_not_editable`, `mural_not_publishable`, `photo_not_ready`, `idempotency_conflict`.
- `422 unsupported_grant_scope` / `invalid_grant_expiration`.
- `428 precondition_required`; `503 dependency_unavailable` para falha de DB/MinIO com detalhe sanitizado.

## Estratégia RED e validação real

1. **Domain RED:** publicar aniversário sem foto, com vínculo próprio não READY, associação inconsistente de owner, ou estado diferente de DRAFT; vínculo interno com owner divergente retorna erro genérico `mural_not_publishable`, não uma alegação de que a foto pertencente a outro owner está `photo_not_ready`; selecionar capa não associada/não READY; remoção da capa deve preservar invariantes; VIEW jamais permite contribuição.
2. **Application RED:** owner cross-mural, `If-Match` obsoleto, replay/conflict de idempotência, geração de token e persistência somente do hash, listagem sem segredo, revogação e busca após expiração.
3. **Presentation RED:** testar schemas/status OpenAPI, ausência de `shareUrl` na listagem, nenhum token no Problem Details, headers de cache/robots/referrer e coleção sem parcial no erro de presign.
4. **PostgreSQL real em Compose:** migrations V1–V5 em sequência, incluindo V4 de variantes e V5 de publicação, FKs/unique/checks, trava concorrente de publish, validação da FK composta da capa, clear-cover+detach atômicos e `lock_version` único.
5. **MinIO real em Compose:** fixtures de imagem grande/EXIF confirmam bytes compartilhados sanitizados SHARE_VIEW, maior lado ≤2560 px, orientação/proporção correta, original hash inalterado e nenhum fallback; READY legado é convertido no publish; falha injetada de processamento deixa DRAFT e nenhum grant.
6. **MinIO real em Compose:** link válido retorna imagem pela URL temporária, bucket continua privado, `X-Amz-Expires <= 60`, URL não sobrevive ao prazo do grant, GET direto ao objeto responde `Cache-Control: private, no-store`, revogação bloqueia uma nova chamada de API e URL antiga ainda obedece o TTL residual de até 60 segundos.
7. **E2E navegador real:** owner local cria DRAFT com upload/attach READY, escolhe capa, publica explicitamente e cria link VIEW; janela anônima abre `/m/{shareToken}` e lê somente as fotos daquele mural; owner lista grants sem token e revoga; nova chamada falha enquanto URL de imagem já emitida permanece utilizável só durante seu TTL; conferir `noindex`, `no-store`, `Referrer-Policy` e ausência de qualquer persistência/exportação de `Composition`.

## Critérios observáveis

1. Nenhuma ação de salvar rascunho cria grant ou transforma status em `PUBLISHED`.
2. Uma publicação de aniversário sem fotos é válida e retomável por retry idempotente; fotos existentes ainda precisam estar prontas e pertencer ao owner.
3. O GET público sem token ou com token inválido não retorna metadados/fotos; com token válido retorna apenas aquele mural e URLs de leitura temporárias.
4. Revogar bloqueia novas requisições por token; a janela residual de URL MinIO é explicitamente limitada a no máximo 60 segundos e não é descrita como revogação instantânea.
5. Owner pode consultar/revogar grants sem reobter o segredo; hash nunca é serializado.
6. Capa persiste somente enquanto associada e READY; primeira foto vira capa inicial, e ao remover a capa em DRAFT é selecionada a primeira restante ou null.
7. Composição visual continua local, exportável em separado pelo frontend e não aparece nas tabelas/API deste feature.

## Dependências e alterações necessárias antes do RED/GREEN

- Antes de iniciar RED ou GREEN, aprovar os adendos documentais MEM-001-R13/AC11 e MEM-006-R18/AC20 e comprovar por Compose que GET direto do MinIO retorna `private, no-store`; a evidência MEM-006 atual ainda não comprova o header. Adaptar `PhotoStorage`/`S3PhotoStorage` uma vez, preservando TTL privado de cinco minutos.
- Antes do RED/GREEN, revisar/aprovar as atualizações documentais propostas: MEM-006 acceptance/contracts/OpenAPI/E2E documentam capa automática inicial e fallback; MEM-004 acceptance/contracts/OpenAPI incluem `coverPhotoId` no PATCH; MEM-002/MEM-004 MuralSummary incluem `coverPhotoId`/`publishedAt`. Todos os AC/API mantêm traceability cruzada.
- FRONTEND_SPEC pede estados distintos para link inválido, expirado, revogado e mural removido. O contrato usa códigos/status diferenciados sem retornar metadata do mural, owner ou UUID; revisar segurança e cópia final contra esses limites antes do GREEN.
- Criar e aprovar OpenAPI versionado MEM-008 antes dos testes de contrato.
- Definir configuração Compose local `MEMORA_PUBLIC_BASE_URL`; produção não tem autenticação aprovada.
- Resolver ADR de autenticação de owner e política de expiração/quota antes de lançamento público.

## Gate de derivado seguro e publish de legado

- Upload novo só fica READY após gerar SHARE_VIEW bounded, orientado, sem GPS/EXIF/XMP; maior lado ≤2560 px sem upscaling e original permanece idêntico.
- V4 de MEM-001 marca READY antigos `PENDING`. Ao publicar, garantir variantes de toda a coleção antes da transação final. Falha deixa mural DRAFT, não persiste chave de idempotência/publicação nem cria grant; retry deve produzir o mesmo resultado sem duplicar objetos.
- GET shared deve presign somente chave SHARE_VIEW READY. Se a variante faltar ou presign falhar, responder 503 sanitizado; nunca tentar o original.
- A migration MEM-008 é V5, aplicada após V4; migration/OpenAPI e AC de migration precisam de revisão antes do RED/GREEN.
