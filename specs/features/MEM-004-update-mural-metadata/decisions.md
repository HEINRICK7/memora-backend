# MEM-004 — Decisões

## MEM-004-ADR-01 — Revisão via If-Match

Usar `If-Match` com ETag forte que contém a versão pública decimal (`"1"`, `"2"`, …). Assim a leitura do `MuralSummary` fornece diretamente o precondicionamento para o PATCH. Versão divergente resulta em `409` segundo o padrão de conflito já adotado pelo backend. Header ausente usa `428`.

O endpoint GET de detalhe emite esse ETag; a listagem mantém apenas `version` dentro de cada item para não criar ETags compostos de uma página.

O update carrega a entidade JPA gerenciada e deixa `@Version` controlar o write concorrente; não recria `MuralEntity` por `fromDomain`, que é caminho de criação. Conflitos de flush/commit são convertidos em resposta `409` fora de uma transação abortada.

## MEM-004-ADR-02 — Campos parciais e no-op

Omitido significa “preservar”; `description: null` significa limpar. Um patch que resulta nos mesmos valores normalizados é no-op, não grava e não avança `version` nem `updatedAt`.

## MEM-004-ADR-03 — Apenas rascunhos

Publicação e suas regras de consistência ainda não existem. Atualizar somente `DRAFT` impede alterar silenciosamente uma representação já publicada. Capa está fora deste contrato porque não há coluna nem política de ownership implementada para ela.
