# MEM-004 — Atualizar metadados do rascunho de mural

**Estado:** aprovado para implementação incremental após revisão do juiz.  
**Fontes:** `../../../BACKEND_SPEC.md`, `../../../../memora-frontend/FRONTEND_SPEC.md`, `../../../../memora-frontend/BRAND_SYSTEM.md`, `../../../../SDD_TDD_PROTOCOL.md`.

## Problema e usuário

O proprietário pode corrigir o nome e a descrição de um mural enquanto prepara o rascunho. Alterações simultâneas não podem apagar silenciosamente o que outra aba salvou.

## Pré-condições

- Mural existente em `DRAFT`.
- Principal resolvido no backend; o cliente não envia owner.
- O cliente leu a versão pública do mural e a envia em `If-Match`.

## Requisitos rastreáveis

- **MEM-004-R01:** permitir PATCH de `title`, `description` ou ambos; ao menos um campo deve estar presente e campos desconhecidos são recusados.
- **MEM-004-R02:** campo omitido não muda; `description: null` limpa a descrição; `title: null` é inválido.
- **MEM-004-R03:** aplicar o mesmo trim Unicode e limites do MEM-002: título com 1–100 code points, descrição até 500; descrição vazia após trim vira `null`.
- **MEM-004-R04:** somente mural `DRAFT` pode ser atualizado nesta feature; não modificar status nem capa.
- **MEM-004-R05:** autorização deriva do principal. Mural ausente e mural alheio produzem o mesmo `404`.
- **MEM-004-R06:** `If-Match` obrigatório e igual ao ETag forte recebido em `GET /murals/{muralId}`; seu valor contém a versão pública decimal entre aspas, por exemplo `"3"`. ETag malformado retorna `400`; ausente retorna `428`.
- **MEM-004-R07:** versão divergente, mudança concorrente detectada no commit, ou estado diferente de `DRAFT` resulta em `409` sem conteúdo privado no erro.
- **MEM-004-R08:** alteração efetiva incrementa a versão pública uma vez e atualiza `updatedAt` usando o relógio da aplicação; `createdAt` permanece igual.
- **MEM-004-R09:** patch semanticamente sem mudança retorna a representação persistida sem incrementar versão nem `updatedAt`.
- **MEM-004-R10:** sucesso retorna `200`, `MuralSummary`, `ETag` novo e `Cache-Control: private, no-store`.
- **MEM-004-R11:** erros de validação usam RFC 9457 com `field` e código estável; nunca ecoam o texto enviado.
- **MEM-004-R12:** persistência e concorrência são verificadas em PostgreSQL real e no Compose.
- **MEM-004-R13:** `GET /murals/{muralId}` retorna ETag forte correspondente ao `version` da representação; a lista não emite ETag individual por item.
- **MEM-004-R14:** CORS dos origins já permitidos aceita `PATCH` e header `If-Match`, e expõe `ETag` ao navegador; origins não permitidos continuam sem acesso.

## Estados e erros

- Título/descrição inválidos: `422` com `invalid_mural_title` ou `invalid_mural_description`.
- Corpo inválido, campos desconhecidos ou UUID malformado: `400`.
- ETag malformado: `400`, `code: invalid_if_match`.
- `If-Match` ausente: `428` com `precondition_required`.
- Mural inexistente ou de outro owner: mesmo `404`.
- Versão obsoleta ou mural não editável: `409`, códigos `stale_mural_version` ou `mural_not_editable`.
- Falha de persistência: `503` seguro.

## Privacidade

O endpoint não recebe `ownerId`, não retorna identidade interna e não inclui conteúdo original inválido em erros. Respostas privadas não podem ser cacheadas publicamente.

## Fora de escopo

Alterar capa, anexar/reordenar fotos, salvar composição, publicar/arquivar, autenticação de navegador e interface de edição.

## Critérios observáveis

1. PATCH próprio com texto válido atualiza apenas os campos presentes e retorna nova versão/ETag.
2. Descrição pode ser limpa explicitamente; campo ausente permanece intacto.
3. Repetir texto semanticamente igual não altera versão ou timestamp.
4. Dono concorrente com uma versão obsoleta recebe conflito; dados da atualização anterior permanecem íntegros.
5. Outro owner e UUID inexistente são indistinguíveis.
6. PUBLISHED/ARCHIVED não mudam nesta feature.
