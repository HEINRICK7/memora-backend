# Memora — Backend Specification

**Versão:** 1.0 · 24/10/2026  
**Status:** contrato de construção, não documentação de API implementada.  
**Fontes:** [`BRAND_SYSTEM.md`](../memora-frontend/BRAND_SYSTEM.md), [`FRONTEND_SPEC.md`](../memora-frontend/FRONTEND_SPEC.md), [`AGENTS.md`](AGENTS.md).

## 1. Objetivo e fronteira

Backend responsável por identidades, murais persistentes, fotografias publicadas, permissões, links compartilhados, contribuições, moderação e acesso seguro ao armazenamento. O editor visual e a prévia local pertencem ao frontend; backend armazena documentos de composição versionados e arquivos quando o usuário solicita persistência/publicação.

Não concentrar MediaPipe, motores AR ou renderização por frame no backend. Não criar microserviços prematuramente: iniciar com **monólito modular** e fronteiras claras.

## 2. Stack proposta

| Camada | Tecnologia |
|---|---|
| Plataforma | Java 21 + Spring Boot (versão estável verificada na implementação) |
| API | Spring Web, validação de entrada, documentação OpenAPI |
| Segurança | Spring Security + OIDC/OAuth2 resource server; provedor de identidade em ADR |
| Persistência | PostgreSQL, JPA/Hibernate no adapter de infraestrutura |
| Migrações | Flyway |
| Imagens | Storage S3 compatível, presigned upload/download com limites e autorização |
| Metadados | PostgreSQL, estados auditáveis |
| Processamento assíncrono | Inicialmente jobs simples/outbox; fila apenas com necessidade demonstrada |
| Observabilidade | Health, métricas técnicas, logs estruturados sem dados privados |
| Testes | JUnit 5, AssertJ, Mockito se necessário, Testcontainers para integrações reais |
| Deploy | Docker/Compose, HTTPS atrás de proxy; CI independente de nuvem |

AWS S3/RDS/EC2 são destinos possíveis, não decisões de deploy já realizadas. Não assumir existência de conta, infraestrutura ou credenciais.

## 3. Fronteiras e módulos

```text
src/main/java/.../memora/
  domain/
    mural/
    photo/
    sharing/
    contribution/
    composition/
  application/
    mural/
    photo/
    sharing/
    contribution/
    ports/
  infrastructure/
    persistence/
    storage/
    identity/
    googlephotos/
    jobs/
  presentation/
    rest/
    security/
    error/
  bootstrap/
    configuration/
src/test/java/.../
  domain/
  application/
  integration/
  presentation/
```

**Dependências:** presentation/infrastructure → application → domain. Entidades e casos de uso não importam classes Spring, JPA, AWS SDK ou HTTP.

## 4. Ubiquitous language

- `Mural`: coleção ordenada de imagens publicadas e metadados, governada por proprietário e política de acesso.
- `Composition`: documento visual com template, slots, crop/transformações e camadas de texto; não é o mesmo que mural.
- `PhotoAsset`: foto original e variantes derivadas; sem URLs permanentes públicas.
- `ShareGrant`: autorização revogável associada a token opaco e escopo (`VIEW`/`CONTRIBUTE`).
- `Contribution`: envio proposto por convidado, sujeito à moderação.
- `Template`: definição versionada de layout. Pode ser estático no frontend no MVP, mas versões usadas em composições precisam ser estáveis.
- `UserId`: UUID interno estável resolvido do principal.
- `Owner`: usuário identificado pelo par OIDC `(issuer, sub)`; issuer e subject são armazenados separadamente para evitar colisão entre provedores.

## 5. Domínio e invariantes

### Mural

```text
DRAFT -> PUBLISHED -> ARCHIVED
           |  ^
           v  |
         DRAFT (somente se política explícita permitir despublicar)
```

- Mural tem ownerId obrigatório e títulos válidos (1–100 caracteres).
- `DRAFT` não é acessível por ShareGrant.
- `PUBLISHED` pode ser visualizado somente com autorização válida.
- Arquivamento invalida visualização pública, conforme política registrada.
- Modificações concorrentes usam `version` (optimistic locking).

### ShareGrant

- Token criptograficamente aleatório, com entropia adequada; banco armazena apenas hash do segredo, mais metadados mínimos.
- Token é emitido uma vez e mostrado ao criador; rotacionar cria novo segredo.
- Papel `VIEW` não admite upload. Papel `CONTRIBUTE` admite envio, **não aprovação/edição**.
- Grant pode ser revogado a qualquer momento, expirar, ser limitado por senha/código opcional e quota.
- Não aceitar id enumerável como credencial suficiente.
- Respostas para token inválido devem evitar vazamento de existência/identidade de mural.

### Contribution

```text
PENDING -> APPROVED
        -> REJECTED
```

- Upload convidado permanece isolado do feed publicado até aprovação do dono.
- Apenas dono/editor autorizado muda status.
- Aprovação é idempotente; transições inválidas geram conflito de domínio.
- Rejeição define política de retenção/remoção da foto original pendente.

### PhotoAsset

- Pertence a um owner ou contexto temporário de contribuição controlado.
- Status proposto: `UPLOADING`, `READY`, `FAILED`, `DELETED`.
- Publicação só referencia `READY`, com validação de tipo, dimensão, integridade e ownership.
- Não publicar EXIF sensível; remover geolocalização de derivados públicos.
- Nunca confiar em `Content-Type` informado pelo cliente como única validação.

### Composition

- `schemaVersion` obrigatório, `templateId/templateVersion` imutáveis para uma revisão publicada.
- Coordenadas de slots normalizadas e coerentes; referências a assets autorizados.
- Alteração produz nova revisão ou versionamento otimista; não sobrescrever edições simultâneas silenciosamente.

## 6. Entidades e tabelas iniciais

| Tabela | Campos principais | Regras |
|---|---|---|
| `users` | `id UUID`, `identity_issuer`, `identity_subject`, `created_at` | Par issuer/subject único; `id` é o UserId interno |
| `murals` | `id`, `owner_id`, `title`, `description`, `cover_photo_id`, `status`, `version`, timestamps | Índice por owner/status |
| `photo_assets` | `id`, `owner_id`, `storage_key`, `mime_type`, `bytes`, `width`, `height`, `status`, `created_at` | Storage privado; sem URL pública persistente |
| `mural_photos` | `mural_id`, `photo_id`, `sort_order`, `caption`, `created_at` | Unicidade mural/foto conforme regra definida |
| `compositions` | `id`, `mural_id`, `schema_version`, `template_id`, `template_version`, `document_json`, `version` | JSON validado no application boundary |
| `share_grants` | `id`, `mural_id`, `token_hash`, `scope`, `expires_at`, `revoked_at`, `created_at` | Token hash único |
| `contributions` | `id`, `mural_id`, `photo_asset_id`, `grant_id`, `status`, `created_at`, `decided_at`, `decided_by` | Moderada por dono |
| `audit_events` | `id`, `actor_id`, `mural_id`, `type`, `occurred_at`, `metadata_safe` | Sem token/URLs assinadas/EXIF |

Usar UUID/ULID como identificadores opacos. Índices, constraints e foreign keys devem ser definidos por migration e validados por testes em PostgreSQL real.

## 7. Ports de aplicação

```java
public interface MuralRepository {
    Optional<Mural> findById(MuralId id);
    Mural save(Mural mural);
}

public interface ShareGrantRepository {
    Optional<ShareGrant> findActiveByTokenHash(String tokenHash);
    ShareGrant save(ShareGrant grant);
}

public interface PhotoStorage {
    UploadIntent createUploadIntent(StorageObjectKey key, UploadConstraints constraints);
    StoredObjectMetadata inspect(StorageObjectKey key);
    DownloadAccess createReadAccess(StorageObjectKey key, Duration ttl);
    void delete(StorageObjectKey key);
}

public interface Clock { Instant now(); }
public interface TokenGenerator { String generateOpaqueToken(); }
public interface AuthenticatedPrincipal { UserId userId(); }
```

Criar interfaces para dependências voláteis, mas não embrulhar trivialmente todo método da linguagem/framework em uma interface genérica.

## 8. Casos de uso (ordem de implementação)

1. `CreateMural` — cria rascunho do dono.
2. `GetOwnedMural` / `ListOwnedMurals` — só recursos do proprietário.
3. `UpdateMuralMetadata` — título/descrição de rascunho com optimistic locking; capa fica para contrato e migration próprios.
4. `PreparePhotoUpload` — autoriza escopo, limites, storage key e URL assinada.
5. `ConfirmPhotoUpload` — verifica objeto realmente enviado, tipo, tamanho, status.
6. `AttachPhotoToMural` / `ReorderMuralPhotos` — foto READY do dono.
7. `SaveComposition` — valida documento e versão.
8. `PublishMural` — verifica invariantes e altera status.
9. `CreateShareGrant` / `RevokeShareGrant` — acesso view-only no MVP.
10. `GetSharedMural` — resolve hash de token, estado, expiração e fotos aprovadas.
11. `SubmitContribution` — fase 2; autorização `CONTRIBUTE` e isolamento.
12. `ApproveContribution` / `RejectContribution` — fase 2; ator autorizado.
13. `StartGooglePhotosImport` / `CompleteGooglePhotosImport` — fase 2; OAuth, sessão, media selected.

Cada caso de uso nasce de spec comportamental e de teste RED antes da implementação.

## 9. API REST v1 proposta

Base `/api/v1`. Autenticação OIDC para rotas privadas. Erros em padrão RFC 9457 Problem Details, com `code` estável próprio, correlação e sem vazamento de PII.

### Murais do proprietário

| Método | Endpoint | Propósito |
|---|---|---|
| `POST` | `/murals` | Criar rascunho; `Idempotency-Key` evita duplicação em retry |
| `GET` | `/murals` | Listar com cursor/paginação e filtro de status opcional |
| `GET` | `/murals/{id}` | Obter detalhe do dono |
| `PATCH` | `/murals/{id}` | Atualizar metadados com versão |
| `POST` | `/murals/{id}/publish` | Publicar idempotentemente |
| `POST` | `/murals/{id}/archive` | Arquivar |
| `PUT` | `/murals/{id}/composition` | Persistir documento de composição versionado |
| `PUT` | `/murals/{id}/photos/order` | Alterar ordem de fotos |

O `PATCH /murals/{id}` altera somente título e/ou descrição enquanto o mural estiver em `DRAFT`. Exige `If-Match` com a versão pública atual; alteração concorrente responde `409`. Campos omitidos permanecem iguais e `description: null` limpa a descrição. Capa ainda não faz parte da tabela implementada e requer spec/migration próprios.

### Upload e fotografias

| Método | Endpoint | Propósito |
|---|---|---|
| `POST` | `/uploads` | Intent de upload com restrições |
| `POST` | `/uploads/{uploadId}/complete` | Confirmar objeto e metadados |
| `POST` | `/murals/{id}/photos` | Vincular foto pronta ao mural |
| `DELETE` | `/murals/{id}/photos/{photoId}` | Desvincular (não apagar arquivo indiscriminadamente) |

### Compartilhamento

| Método | Endpoint | Propósito |
|---|---|---|
| `POST` | `/murals/{id}/grants` | Criar link VIEW ou CONTRIBUTE |
| `GET` | `/murals/{id}/grants` | Listar apenas metadados do dono |
| `DELETE` | `/murals/{id}/grants/{grantId}` | Revogar |
| `GET` | `/shared/{shareToken}` | Obter mural acessível pelo token |
| `POST` | `/shared/{shareToken}/contributions` | Solicitar contribuição (fase 2) |
| `GET` | `/murals/{id}/contributions` | Listar pendentes do dono (fase 2) |
| `POST` | `/murals/{id}/contributions/{cid}/approve` | Aprovar (fase 2) |
| `POST` | `/murals/{id}/contributions/{cid}/reject` | Rejeitar (fase 2) |

Para uma rota de token em URL: **nunca usar links de mídia privados permanentes** na resposta. Retornar URLs temporárias somente após autorização. Aplicar `Cache-Control: private, no-store` aos dados de acesso compartilhado e conferir o risco de pré-visualizadores de redes sociais visitarem o link.

## 10. Exemplos de contrato

### `POST /api/v1/murals`

O cliente envia `Idempotency-Key` UUID. O mesmo key com o mesmo conteúdo normalizado retorna o mesmo rascunho; o mesmo key com payload diferente retorna `409`.

```json
{
  "title": "Aniversário da Sofia",
  "description": "Momentos especiais"
}
```

Resposta `201`:

```json
{
  "id": "c0f5f693-7160-4e49-9d76-cfaba4edce1c",
  "status": "DRAFT",
  "version": 1,
  "title": "Aniversário da Sofia",
  "description": "Momentos especiais"
}
```

IDs do exemplo são ilustrativos, não objetos existentes.

### `POST /api/v1/murals/{id}/grants`

```json
{
  "scope": "VIEW",
  "expiresAt": null
}
```

Resposta `201`:

```json
{
  "grantId": "62dce048-b844-4e51-a2a7-418bd18248a6",
  "shareUrl": "https://EXEMPLO.invalid/m/TOKEN_OPACO",
  "scope": "VIEW",
  "expiresAt": null
}
```

A URL acima é fictícia. O domínio real é configuração de ambiente; o backend nunca registra token bruto em log e não retorna o segredo novamente ao listar grants.

### `GET /api/v1/shared/{token}`

```json
{
  "mural": {
    "title": "Aniversário da Sofia",
    "description": "Momentos especiais",
    "photos": [{ "id": "foto1", "caption": "A festa", "previewUrl": "SIGNED_TEMPORARY_URL" }]
  },
  "permissions": { "canView": true, "canContribute": false }
}
```

URLs e IDs deste bloco são exemplos de contrato, não dados operacionais.

### Erros

```json
{
  "type": "about:blank",
  "title": "Access denied",
  "status": 403,
  "code": "MURAL_ACCESS_DENIED",
  "detail": "Você não tem acesso a este mural.",
  "traceId": "request-correlation-id"
}
```

API nunca revela se outro usuário é proprietário em erro de acesso. Para recursos privados, `404` pode ser preferível a `403` quando a distinção comprometer privacidade.

## 11. Upload e armazenamento

1. Frontend informa metadados declarados (nome, mime, tamanho) em `POST /uploads`.
2. Backend autentica/autoriza, cria chave de storage não adivinhável, limites e intenção temporária.
3. Frontend envia arquivo diretamente para storage por URL assinada de curta duração.
4. Frontend chama `complete`.
5. Backend confere existência, bytes, assinatura, magic bytes, dimensões e estado; gera derivados seguros (na infraestrutura ou job).
6. `READY` habilita vínculo ao mural.

Em contribuição anônima, a intenção de upload deve ser vinculada a `ShareGrant` com escopo `CONTRIBUTE` válido, com limite por link/dispositivo, antiabuso e restrição de mídia; o link sozinho não confere acesso amplo ao bucket.

Storage: separar `originals/private/`, `derived/private/`, `contributions/pending/`; chave é interna, não nome fornecido pelo usuário. Remover localização EXIF dos derivados, gerar tamanhos otimizados e definir ciclo de vida de objetos órfãos.

## 12. Google Fotos Picker (fase 2)

- Fluxo baseado na Picker API oficial, não Library API antiga para navegar toda a biblioteca.
- Backend gerencia início/retorno OAuth, sessão de seleção, polling limitado, download apenas dos itens escolhidos e posterior revogação/limpeza quando aplicável.
- Tokens OAuth protegidos em trânsito e repouso, com expiração/escopos mínimos; não expor tokens ao navegador em URL.
- Tratamento de falhas: usuário cancelou, escopo negado, seleção expirada, rate limit e arquivo indisponível.
- Registrar consentimento e finalidade; não conservar credenciais além do necessário.

## 13. Segurança, privacidade e abuso

- TLS obrigatório, CORS restrito e cookies seguros quando utilizados; CSRF conforme estratégia de sessão.
- Autorizar **cada leitura e mutação** pelo contexto do ator, nunca por confiança em `ownerId` recebido do cliente.
- Segredos em secrets manager/env configurada, nunca no repositório, log ou JWT de cliente.
- Proteção contra enumeração, brute force de tokens, scraping, hotlink, excesso de uploads e links públicos indexados.
- `robots` e `noindex` nos murais; lembrar que isso não substitui controle de acesso.
- Fotos de crianças: consentimento e responsabilidade de publicação são centrais; minimizar coleta, retenção e analytics.
- Definir política de exclusão, backup, restauração e prazo de retenção antes da abertura pública do serviço.
- Backups criptografados; remover arquivos de storage ao excluir definitivamente segundo política documentada.
- Auditar mudanças de grants/contribuições sem armazenar fotografias em logs.
- Limites exemplificativos devem ser calibrados por testes e orçamento; não afirmar números não medidos.

## 14. Transações e consistência

- Publicação altera estado em transação com verificação de invariantes.
- Aprovação de contribuição e inserção de `mural_photos` na mesma transação DB.
- Storage e DB não participam de transação distribuída: jobs de compensação removem objetos órfãos; operações idempotentes.
- Concurrency via versão de mural/composition e `409 CONFLICT` com versão atual.
- Emissões de eventos persistentes por outbox se tarefas assíncronas se tornarem críticas; não adicionar Kafka no MVP.

## 15. TDD e suíte de validação

### Domain unit tests (sem Spring, JPA ou AWS)

- Mural não publica sem owner ou conteúdo válido quando a regra exigir.
- Link view-only não permite contribuição.
- Link expirado/revogado não autoriza visualização.
- Contribuição PENDING não é listada como publicada.
- Apenas dono autorizado aprova ou rejeita.
- Transição `APPROVED -> PENDING` é inválida.
- Composição rejeita documento inválido e asset não autorizado.

### Application tests (ports fake/in-memory sob contrato)

- CreateMural, PublishMural, ShareMural, RevokeGrant, GetSharedMural.
- Upload intent e complete validam autorização, objeto e MIME real.
- Aprovação idempotente sem duplicar foto no mural.
- Falha de storage deixa estado recuperável sem mídia publicada parcialmente.

### Infrastructure integration tests

- Testcontainers PostgreSQL: constraints, locks, migrações, queries e transaction boundary.
- Storage S3 compatível local: upload assinado, TTL, acesso negado e remoção de órfãos.
- OAuth Google Picker: contratos por ambiente de integração, nunca um mock tratado como homologação real.

### Presentation/API tests

- Status `201/200/204/400/401/403/404/409/413/422/428/429` e schemas.
- Acesso cross-owner negado em todos os endpoints privados.
- Token bruto jamais aparece em listagem, logs ou erro.
- Headers de cache e segurança nos endpoints compartilhados.

### E2E

- Publicar e visualizar em anônimo por link válido.
- Revogar link e comprovar bloqueio imediato.
- Upload de convidado permanece invisível até aprovação.
- Falhas de armazenamento não tornam mídia parcialmente pública.

## 16. Operação e observabilidade

- `/actuator/health/liveness` e `/actuator/health/readiness` configurados de forma segura, sem divulgar detalhes internos publicamente.
- Métricas: taxa de upload, latência endpoints, erros por categoria, fotos derivadas, tamanho storage, revogações; métricas de produto agregadas sem dados identificáveis.
- Logs com `traceId` e `userId` pseudonimizado quando necessário, sem descrição de foto, token de link, URLs assinadas ou autenticação.
- Alertas de falha de upload, jobs pendentes, erros 5xx, esgotamento de storage e abusos.
- Pipeline CI: checkstyle/format, unit, integration, OpenAPI contract, container scan, build Docker.
- Deploy/rollback deve preservar backward-compatibility de schema e migrations.

## 17. Critérios de aceite e ordem de milestones

**M1 — Domínio protegido:** testes RED/GREEN para mural, share grant e permissões, repositories in-memory.

**M2 — Persistência e storage:** PostgreSQL, migrations, upload seguro, fotos prontas.

**M3 — API privada de murais:** CRUD mínimo, composição e publicação.

**M4 — Compartilhamento:** token view-only, revoke, resolução de mural convidado, logs seguros e E2E.

**M5 — Colaboração:** novos grants, upload convidado, moderação e limites.

**M6 — Google Fotos:** integração oficial, consentimento, tratamento de expirados/erros, verificação fim a fim.

### Definition of Done por caso de uso

- Comportamento escrito em spec e casos de teste, com identificadores rastreáveis.
- Teste falhou antes da implementação (RED), passou com implementação mínima (GREEN), refatoração segura concluída.
- Regra de negócio isolada do Spring/JPA/SDK.
- API/OpenAPI, erro, autorização, auditoria e documentação atualizados.
- Integração real correspondente verificada onde relevante.
- Observabilidade e controles de privacidade presentes, sem métricas inventadas.

## 18. ADRs pendentes

1. Provedor de identidade do proprietário e modelo de sessão.
2. Política de links: expiração padrão, senhas, limites e revogação.
3. Quotas de upload/storage e custo por mural.
4. Regras de retenção para mídia pendente, excluída e backup.
5. Política de compartilhamento e proteção infantil/consentimento.
6. Estratégia de thumbnails, format conversion e jobs em ambiente de implantação.
7. Estratégia para versionar a API de composições/templates.

