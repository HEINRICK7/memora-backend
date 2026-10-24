# MEM-002 — Criar e consultar rascunhos de mural

**Estado:** aprovado para implementação incremental.  
**Escopo:** estabelecer o agregado Mural, resolver o principal para um usuário interno e permitir criar, listar e consultar murais do proprietário.  
**Fontes:** `BACKEND_SPEC.md`, `FRONTEND_SPEC.md`, `BRAND_SYSTEM.md`, `SDD_TDD_PROTOCOL.md`.

## Problema e usuário

Depois de guardar fotografias, a pessoa precisa organizá-las em projetos persistentes chamados murais. Neste slice, pode criar um rascunho e voltar à lista depois; composição visual e publicação vêm em features posteriores.

## Pré-condições

- O ambiente roda pelo Docker Compose.
- A API valida JWT pelo issuer configurado fora do perfil `local`.
- No perfil local existe o principal fixo `local-development-owner`; não há autenticação de produção.

## Requisitos rastreáveis

- **MEM-002-R01:** resolver o principal validado para um `UserId` UUID interno. JWT identifica usuário pelo par `issuer` + `sub`; se a identidade ainda não existir, provisionar um usuário UUID de modo concorrente-seguro e idempotente. O perfil local usa issuer `urn:memora:local` e subject fixo. O cliente nunca informa ownerId.
- **MEM-002-R02:** preservar fotografias existentes ao migrar `photo_assets.owner_id` textual para UUID de usuário. O backfill usa o issuer configurado no Flyway e cada owner legado vira exatamente um usuário; mantém estados, storage keys e bytes MinIO.
- **MEM-002-R03:** criar mural com título de 1–100 pontos de código Unicode e descrição opcional de até 500 pontos. Antes de validar, remover whitespace Unicode das extremidades; título vazio é inválido e descrição vazia após normalização vira `null`.
- **MEM-002-R04:** mural recém-criado começa em `DRAFT`, versão pública 1, com `createdAt == updatedAt` UTC e owner resolvido no backend. O contador interno JPA pode começar em 0 e mapeia para versão pública `lockVersion + 1`.
- **MEM-002-R05:** listar somente murais do proprietário, ordem `createdAt DESC, id DESC`, filtro opcional por `DRAFT | PUBLISHED | ARCHIVED`, páginas de 1–50 itens (padrão 20) e cursor keyset estável.
- **MEM-002-R06:** cursor Base64URL sem padding contém `status-or-ALL|createdAt|id`, na precisão retornada pelo PostgreSQL. É limitado a 128 caracteres, parseado estritamente e vinculado ao filtro de status; a consulta sempre filtra owner. Busca `limit + 1` e deriva `nextCursor` do último item retornado.
- **MEM-002-R07:** buscar UUID só retorna o mural próprio. Ausente ou de outro dono responde o mesmo `404`.
- **MEM-002-R08:** POST aceita `Idempotency-Key` UUID obrigatório. Repetição do mesmo key e conteúdo normalizado retorna a mesma resposta/recurso e `Idempotency-Replayed: true`; mesmo key com conteúdo diferente resulta em `409`.
- **MEM-002-R09:** criação, listagem e consulta persistem no PostgreSQL; limites e cursor inválidos resultam em `400`, texto inválido em `422` com campo e código estável.
- **MEM-002-R10:** respostas privadas usam `Cache-Control: private, no-store` e nunca incluem identidade, id do owner, chave S3 ou key de idempotência.
- **MEM-002-R11:** `/meus-murais` oferece criação, filtros de status, paginação manual, vazios, carregamento, erro inicial, erro de próxima página e sessão não autenticada sem tratar `401` como lista vazia.
- **MEM-002-R12:** formulário usa labels, mensagens associadas aos campos, contagem em pontos de código, bloqueia envio duplicado e reusa o mesmo `Idempotency-Key` ao repetir uma solicitação ambígua com o mesmo conteúdo. O mural criado aparece no topo da lista de Rascunhos; após sucesso, filtro muda para Rascunhos e a paginação reinicia. Não há ação de editor/publicação.
- **MEM-002-R13:** controles interativos medem no mínimo 44×44 CSS px, mantêm foco após carregar mais e anunciam estados/resultados por região acessível.

## Estados e regras

O agregado reconhece `DRAFT`, `PUBLISHED` e `ARCHIVED`. Esta feature só cria `DRAFT`; filtro e consulta aceitam os três estados, sem alterar status.

Normalização remove o conjunto Unicode White_Space das extremidades, sem alterar espaços internos. O limite considera code points após normalização. Descrição ausente, `null`, vazia ou apenas whitespace vira `null`. `createdAt` e `updatedAt` são persistidos como `TIMESTAMPTZ`; o cursor usa o valor lido do banco, não o relógio anterior à persistência.

`Idempotency-Key` identifica criação por owner. O request hash é SHA-256 de JSON canônico contendo título e descrição normalizados. A chave e o hash ficam associados ao mural durante sua retenção; não são retornados. O cliente gera uma chave nova quando o conteúdo muda.

No cursor, `ALL` representa ausência de filtro. Um cursor emitido para determinado filtro não pode ser reutilizado com outro filtro. O predicado de próxima página é estritamente anterior à tupla `(createdAt,id)` na ordenação definida.

## Erros

- Título/descrição inválidos: `422` Problem Details com `code`, `field` (`title` ou `description`) e `traceId`; não ecoa o texto enviado.
- `limit`, status, cursor, UUID de rota ou `Idempotency-Key` malformados: `400` Problem Details.
- Chave de idempotência existente usada com payload diferente: `409` Problem Details.
- Mural ausente ou de outro owner: `404` indistinguível.
- Falha de banco: `503` seguro, sem SQL/subject no detalhe.
- Sem JWT fora de `local`: `401`; a UI apresenta estado de acesso indisponível sem prometer login ainda não implementado.

## Privacidade e segurança

- `users.id` é UUID aleatório; `identity_issuer` + `identity_subject` formam par único e ficam apenas no banco.
- JWT validado fornece `iss` e `sub` como campos separados; local usa `urn:memora:local` + `local-development-owner`. Nenhum endpoint aceita owner do corpo, query ou header.
- A primeira requisição de uma identidade validada cria seu registro interno caso ainda não exista. A constraint única `(identity_issuer, identity_subject)` torna a resolução idempotente sob requisições concorrentes; `Authentication.name` isolado não é chave de identidade.
- V2 mapeia cada owner textual da V1 ao issuer configurado por `spring.flyway.placeholders.memoraIdentityIssuer` e ao subject legado. Isso preserva instalação local e instalações de desenvolvimento que usavam o issuer configurado. Troca de issuer depois da migração exige fluxo explícito de vinculação; não mescla contas automaticamente.
- Respostas nunca incluem identidade, segredo, token ou storage key.
- Mural rascunho não cria link público nem concede leitura a convidados.

## Fora de escopo

Anexar/reordenar fotos, composição/templates, edição de metadados, arquivamento, publicação, criação/revogação de links, contribuições e autenticação de interface.

## Compatibilidade da migração

Este workspace não tem instância de produção nem deploy rolling. V2 faz corte atômico local, preservando linhas e ownership. Antes de coexistirem versões de aplicação em deployment, a conversão de `photo_assets.owner_id` deve ser dividida em expand/backfill/switch/contract.

## Critérios observáveis

1. POST válido cria um `DRAFT` com UUID, versão pública 1, timestamps iguais e UserId interno resolvido no backend.
2. Repetir POST com mesma chave/conteúdo não duplica; reutilizar a chave com payload diferente resulta em conflito.
3. Texto inválido é recusado sem persistência, com erro associado ao campo e sem refletir o texto recebido.
4. Filtros e páginas retornam somente os murais do principal, em ordem estável e sem repetição/perda.
5. Consultar mural próprio retorna a representação persistida; UUID inexistente e de outro owner têm o mesmo `404`.
6. V2 preserva todas as linhas e estados de fotos existentes, `storage_key` e vínculo do owner; o objeto original segue acessível por URL assinada.
7. Reiniciar a stack conserva usuário, mural, propriedade das fotos e objetos MinIO.
8. A tela não trata 401 como vazio nem declara publicação, edição ou login ainda não implementados.
9. Um banco sem fotografias também consegue resolver o principal local ou uma primeira identidade JWT para um `UserId` persistente e estável.

## Decisões abertas

- Autenticação do proprietário no navegador continua em aberto; este slice pode ser operado pelo perfil local.
- Edição de metadados com controle de versão será especificada separadamente.
- Limite de descrição de 500 code points é inicial e deve ser revisto antes de lançamento público.
