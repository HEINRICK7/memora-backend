# MEM-012 — Fluxo Direto do Convidado (2 Toques, Zero Login)

**Status:** Aprovada para implementação  
**Escopo:** `memora-backend` e `memora-frontend` (`/m/[shareToken]`)  
**Motivação:** Eliminar fricções do Modo Aniversário (tela splash "Começar", dois álbuns concorrentes e dupla curadoria) para que qualquer convidado publique fotos na festa em 2 toques com revisão rápida local antes do upload e possibilidade de apagar apenas as próprias fotos.

## Requisitos rastreáveis

- **MEM-012-R01 (Abertura direta no mural):** Abrir `/m/[shareToken]` exibe imediatamente o cabeçalho da festa e a galeria compartilhada ao vivo, sem tela intermediária bloqueante.
- **MEM-012-R02 (Barra fixa no polegar para links `CONTRIBUTE`):** Quando `permissions.canContribute` é `true`, exibe barra inferior fixa com **`Tirar foto`** e **`Da galeria`**. Links `VIEW` nunca exibem ações de envio.
- **MEM-012-R03 (Revisão local em bottom sheet):** Selecionar ou capturar fotos abre um *bottom sheet* com miniaturas locais (`URL.createObjectURL`, revogadas ao remover/fechar/enviar), opção `×` para remover antes do upload, atalho `+ adicionar` (até 20 fotos por lote), campo opcional/discreto de nome (lembrado no `localStorage`) e CTA **`Enviar para a festa`**.
- **MEM-012-R04 (Sessão anônima silenciosa e publicação direta):** No primeiro envio, um token anônimo (`X-Guest-Album-Token`) é gerado e salvo silenciosamente no `localStorage`. O backend cria a sessão automaticamente em `POST /shared/{token}/guest-album/contributions` e, em `POST .../complete` (padrão `autoPublish=true`), publica a foto diretamente em `mural_photos` (status `PUBLISHED`).
- **MEM-012-R05 (Exclusão apenas das próprias fotos):** `GET /shared/{token}` aceita `X-Guest-Album-Token` opcional e marca `ownedByGuest: true` apenas nas fotos daquele convidado. `DELETE /shared/{token}/guest-album/photos/{photoId}` remove a foto do mural somente se pertencer ao álbum daquele token, reindexa posições e limpa objetos/capa quando necessário.
- **MEM-012-R06 (Continuidade e resiliência):** Fotos que falharem no envio permanecem no *bottom sheet* para retentativa com 1 toque; após envio bem-sucedido, o mural atualiza ao vivo e oferece feedback com atalho rápido **`Tirar outra`**.
