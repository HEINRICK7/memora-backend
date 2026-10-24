# MEM-003 — Decisões

## MEM-003-ADR-01 — Catálogo local versionado

O catálogo inicial vive em dados estáticos no frontend, conforme o escopo do MVP definido no `BACKEND_SPEC.md`. Nenhum endpoint de templates é criado. Cada composição futura referencia `templateId` e `templateVersion` (o campo `TemplateDefinition.version`); `schemaVersion` e a revisão do documento de composição são contadores independentes. Mudanças incompatíveis ficam disponíveis sob nova versão, mantendo a definição antiga necessária a composições salvas.

## MEM-003-ADR-02 — Geometria relativa e exportação determinística

Slots usam coordenadas normalizadas e uma ordem de camada explícita; a proporção e as dimensões de exportação ficam na definição. Isso deixa a mesma definição reutilizável no preview responsivo, no editor e no renderer sem depender do tamanho do viewport.

## MEM-003-ADR-03 — Seleção sem criar composição

Esta feature termina na seleção dentro do catálogo. O estado não é persistido e não inicia editor porque o documento de composição, a vinculação de fotos e o exportador ainda pertencem a features posteriores.

## MEM-003-ADR-04 — Prévia declarativa sem fotografia

Miniaturas representam molduras/slots com formas simples derivadas da geometria; não usam fotos fictícias ou externas. `polaroid-paper` v1 é uma superfície de papel que cobre o canvas todo sob o slot; a margem inferior livre vem da geometria declarada do slot. O preview não serve como exportação final.

## MEM-003-ADR-05 — Filtro por proporção

O catálogo filtra pelas quatro proporções exatas existentes (`1:1`, `4:3`, `2:3`, `4:5`), combinadas com quantidade. A orientação derivada (`portrait`, `square`, `landscape`) aparece como informação do cartão, pois filtrar só por orientação esconderia diferenças relevantes entre layouts.
