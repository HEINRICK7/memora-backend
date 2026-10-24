# MEM-003 — Catálogo de templates declarativos

**Estado:** pronto para revisão de escopo.  
**Escopo:** oferecer os oito layouts iniciais aprovados pela especificação frontend como dados versionados e permitir navegar, filtrar e selecionar um layout no catálogo.
**Fontes:** `FRONTEND_SPEC.md` (Fluxo B — Templates), `BRAND_SYSTEM.md`, `SDD_TDD_PROTOCOL.md`.

## Problema e usuário

Depois de guardar fotografias, a pessoa precisa comparar arranjos visuais e escolher um template para uma composição futura. Os layouts precisam ter identidade estável e geometria legível para o renderer que será implementado em outra feature.

## Pré-condições

- A pessoa abre `/templates`.
- Os templates v1 são mantidos no frontend; esta feature não faz chamada de rede nem altera backend.
- A pessoa pode explorar o catálogo mesmo sem fotos guardadas.

## Requisitos rastreáveis

- **MEM-003-R01:** cada template tem `templateId` estável, `version` positiva, nome, quantidade de fotos, `aspectRatio` width/height, slots de geometria normalizada e configuração de exportação versionada.
- **MEM-003-R02:** catálogo inicial contém exatamente os oito layouts definidos em `contracts.md`: Single (1), Duo (2), Trio (3), Classic strip (4), Grid (4), Polaroid (1), Grid (6) e Story trio (3), todos na versão 1.
- **MEM-003-R03:** toda definição do catálogo valida IDs de slots únicos, quantidade compatível, proporção finita entre 0.5 e 2, geometria normalizada dentro da tela, ausência de sobreposição, ordem de camada única, formatos suportados, `jpegQuality` em `[0,1]` e dimensões de exportação inteiras positivas cuja razão largura/altura coincide com `aspectRatio` com tolerância máxima `1e-9`.
- **MEM-003-R04:** filtros de quantidade e proporção exata podem ser combinados. Proporções disponíveis são `1:1`, `4:3`, `2:3` e `4:5`; orientação é derivada de `aspectRatio` (`<1` retrato, `=1` quadrado, `>1` paisagem) e serve como texto informativo, não como substituto do filtro de proporção.
- **MEM-003-R05:** a pessoa seleciona no máximo um template por vez. Seleção é marcada semanticamente (`aria-pressed` ou equivalente), tem indicador textual e anúncio acessível; não depende somente de cor.
- **MEM-003-R06:** preview mostra layout e contagem de slots sem incorporar fotografias inventadas, miniaturas externas ou conteúdo pessoal.
- **MEM-003-R07:** catálogo é responsivo, usa a identidade visual/token do Memora, mantém alvos interativos de ao menos 44×44 CSS px e possui estado vazio para filtros sem resultado.
- **MEM-003-R08:** selecionar um template apenas registra a escolha no estado da tela. Não cria composição, mural, arquivo nem promessa de edição/exportação ainda indisponíveis.

## Invariantes dos dados

- `templateId` não muda ao atualizar o desenho; mudanças incompatíveis aumentam `version`.
- São incompatíveis mudanças em quantidade/ID/geometria/ordem dos slots, proporção, decoração, dimensões ou formatos de exportação. A versão de template é independente de `Composition.schemaVersion` e da versão do documento de composição.
- As coordenadas de slots são normalizadas em `[0,1]` relativas à tela do template; cada slot tem largura/altura positivas e fica dentro dos limites.
- `photoCount` deve ser igual ao número de slots de fotografia.
- `aspectRatio` representa `canvasWidth / canvasHeight`.
- `aspectRatio` deve corresponder a `export.width / export.height` com tolerância máxima `1e-9`.
- Os slots iniciais não se sobrepõem por área positiva; bordas compartilhadas entre slots adjacentes são válidas. `layerOrder` é único e define renderização determinística.
- A prévia usa apenas os retângulos de slot e a decoração declarada no template. Imagens reais ficam fora do catálogo.
- Se um filtro ocultar o template selecionado, a seleção é limpa e a região `role="status"` anuncia essa mudança.
- A versão é validada nos dados e testes, sem ocupar espaço visual nos cartões.

## Estados e erros

- Estado inicial: todos os templates e filtros em “Todos”.
- Estado filtrado: combinação de quantidade e proporção exata aplicada aos dados locais; orientação é apenas informativa.
- Estado selecionado: uma definição com seleção visível e anúncio acessível.
- Estado sem resultado: explicação e ação para limpar os filtros.
- Definição inválida é rejeitada por validação de domínio e falha os testes do catálogo; não é renderizada parcialmente.

## Privacidade e segurança

- O catálogo não lê nem envia nomes, metadados ou bytes de fotos.
- Não usa analytics, fonte remota de imagens ou API de templates.
- Não representa uma seleção como composição salva ou publicada.

## Fora de escopo

Editor Canvas, associar fotos aos slots, placeholders no editor, transformações de crop, undo/redo, persistir `CompositionDocument`, exportar PNG/JPEG e alterar murais no backend.

## Critérios observáveis

1. `/templates` lista os oito layouts da especificação com razão visual e número de fotos; o teste do catálogo confirma `version: 1` sem exigir que a versão ocupe espaço visual no cartão.
2. Um teste de domínio valida todas as definições e rejeita geometrias inválidas, slot duplicado e quantidade divergente.
3. Filtros de quantidade e proporção exata funcionam combinados e podem ser limpos.
4. Seleção de um cartão deixa apenas aquele selecionado e anuncia o nome/quantidade.
5. Layouts e estados vazios são navegáveis por teclado e utilizáveis em tela estreita.
6. Catálogo não chama API, não salva composição e não contém fotografia gerada ou externa.

## Decisões abertas

- A feature seguinte poderá consumir `templateId` e mapear `TemplateDefinition.version` para `templateVersion` ao iniciar um documento de composição local.
- A resolução final de exportação definida aqui serve como base determinística e poderá ser ajustada antes do renderer, atualizando versão e spec.
