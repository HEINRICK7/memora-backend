# MEM-006 — Decisões aprovadas

## MEM-006-ADR-01 — Usar a versão do mural para concorrência da coleção

**Decisão aprovada:** a coleção `MuralPhoto` participa da mesma versão pública do mural usada por MEM-004. Todas as mutações enviam `If-Match`; uma alteração efetiva aumenta a versão uma vez.\
**Motivo:** metadados e ordem/fotos são uma visão única do rascunho. Separar versões permitiria sobrescrita silenciosa entre edição de título e de fotos.\
**Consequência:** persistência deve garantir que associação também altere a linha/versionamento do mural, mesmo quando apenas a tabela de junção muda. Testar corridas em PostgreSQL real.

## MEM-006-ADR-02 — Alterar associação somente em `DRAFT`

**Decisão aprovada:** permitir ler vínculos de mural próprio em qualquer estado, mas associar, reordenar e remover apenas em `DRAFT`.\
**Motivo:** o contrato atual não define edição/despublicação; mudanças em mural publicado poderiam alterar conteúdo já compartilhado sem uma política de publicação/versão aprovada.\
**Consequência:** `PUBLISHED` e `ARCHIVED` retornam `409 mural_not_editable`; editar publicado exige feature futura com política explícita.

## MEM-006-ADR-03 — Desassociar não é excluir

**Decisão aprovada:** `DELETE /murals/{muralId}/photos/{photoId}` remove somente o vínculo, compacta posições e preserva o ativo/MinIO. `DELETE /photos/{photoId}` falha com `409 photo_attached` enquanto houver qualquer vínculo.\
**Motivo:** o mesmo original pode ser reutilizado e apagar silenciosamente altera outros murais.\
**Consequência:** a pré-condição do endpoint de exclusão foi registrada nos artefatos MEM-001 aprovados conjuntamente.

## MEM-006-ADR-04 — Reordenação como permutação completa

**Decisão aprovada:** `PUT /photos/order` recebe todos os IDs vinculados, uma vez cada, na ordem final.\
**Motivo:** evita estados parciais, gaps e operações relativas ambíguas sob concorrência.\
**Consequência:** lista parcial, duplicada ou com ID alheio é rejeitada integralmente; a transação atualiza posições contíguas.

## MEM-006-ADR-05 — Reutilizar ativo somente dentro do mesmo proprietário

**Decisão aprovada:** um `PhotoAsset.READY` próprio pode estar vinculado a vários murais próprios, com unicidade por mural; não é transferido nem copiado.\
**Motivo:** separa a vida do original das escolhas de organização e evita duplicação no MinIO.

## MEM-006-ADR-06 — Validar unicidade de posição ao final da transação

**Decisão aprovada:** declarar `UNIQUE(mural_id, position) DEFERRABLE INITIALLY DEFERRED` no PostgreSQL e atualizar todas as posições da permutação em uma transação.\
**Motivo:** swaps válidos podem ter colisão temporária durante atualizações por linha, embora o conjunto final seja único. Validar somente no commit permite reordenação atômica e ainda impede duplicatas persistidas.\
**Verificação:** a integração real MEM-006 deve trocar posições existentes e confirmar leitura final ordenada e unicidade; não substituir PostgreSQL por H2 ou mock.

## Restrições de implementação aprovadas

- MEM-001 e MEM-006 foram aprovados conjuntamente; preservar as regras de exclusão/concorrência em implementação.
- Quota/limite por mural permanece pendente para exposição pública; não inventar valor nesta feature.
- Adicionar o OpenAPI versionado MEM-006 antes dos testes de contrato HTTP e manter handlers alinhados a ele.
