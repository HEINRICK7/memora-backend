# MEM-003 — Critérios de aceite

## MEM-003-AC01 — Catálogo estável

**Given** que a pessoa abre `/templates`  
**When** o catálogo local carrega  
**Then** vê os oito templates definidos em `MEM-003-DOM-02`, todos na versão 1 nos dados  
**And** cada cartão informa nome, orientação e quantidade de fotos  
**And** a prévia corresponde à geometria declarativa dos slots.

## MEM-003-AC02 — Validar definições e geometria

**Given** uma definição de template  
**When** o domínio valida o template  
**Then** aceita quantidade, aspect ratio, exportação e camadas coerentes  
**And** rejeita slot duplicado, contagem divergente, coordenadas fora de `[0,1]`, área não positiva, sobreposição por área, layer order repetido, formato não suportado, qualidade JPEG fora de `[0,1]`, dimensão de exportação não inteira/positiva ou razão incompatível entre aspect ratio e exportação  
**And** aceita slots adjacentes que só compartilham uma borda.

## MEM-003-AC03 — Filtrar por quantidade

**Given** os oito templates  
**When** seleciona uma quantidade de fotografias  
**Then** só aparecem templates com esse número de slots  
**And** “Todos” remove o filtro.

## MEM-003-AC04 — Filtrar por proporção

**Given** os oito templates  
**When** seleciona `1:1`, `4:3`, `2:3` ou `4:5`  
**Then** só aparecem templates daquela proporção exata  
**And** a contagem de fotos pode ser combinada com a proporção.

## MEM-003-AC05 — Resultado de filtro vazio

**Given** filtros combinados sem template correspondente  
**When** o resultado aparece  
**Then** há mensagem de estado vazio e ação acessível para limpar os filtros.

## MEM-003-AC06 — Seleção acessível

**Given** cartões de template  
**When** seleciona um template por mouse ou teclado  
**Then** aquele botão indica `aria-pressed=true` e mostra confirmação textual  
**And** os outros indicam `aria-pressed=false`  
**And** uma região `role="status"` com anúncio educado anuncia nome e quantidade  
**And** selecionar não cria mural, composição ou arquivo.

## MEM-003-AC07 — Responsividade e privacidade

**Given** viewport mobile ou desktop  
**When** navega pelos templates, filtros e seleção  
**Then** conteúdo se reorganiza sem rolagem horizontal  
**And** controles medem ao menos 44×44 CSS px e foco é visível  
**And** nenhum arquivo, metadado de foto, conteúdo de terceiro ou URL externa é solicitado.

## MEM-003-AC08 — Limpar seleção oculta

**Given** que um template está selecionado  
**When** outro filtro oculta o template selecionado  
**Then** seleção é limpa e a região de status anuncia a mudança.
