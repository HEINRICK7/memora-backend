# MEM-012 — Critérios de Aceite

- **MEM-012-AC01:** Ao abrir link `CONTRIBUTE`, o convidado vê diretamente a galeria da festa e os botões **`Tirar foto`** e **`Da galeria`**, sem precisar clicar em "Começar".
- **MEM-012-AC02:** Ao abrir link `VIEW`, nenhuma ação de câmera, galeria ou exclusão aparece.
- **MEM-012-AC03:** Ao selecionar 2 fotos da galeria, o *bottom sheet* abre localmente sem iniciar upload; o convidado pode remover 1 foto (`URL.revokeObjectURL` chamado) e só ao clicar em **`Enviar para a festa`** os uploads iniciam.
- **MEM-012-AC04:** No envio, fotos são publicadas diretamente (`status: PUBLISHED`), o mural recarrega com as novas fotos, e fotos que falharem permanecem no *bottom sheet* para retentativa.
- **MEM-012-AC05:** No lightbox, uma foto com `ownedByGuest: true` exibe **`Apagar minha foto`** com confirmação em 2 passos; fotos de outros convidados (`ownedByGuest: false`) não exibem ação de apagar.
- **MEM-012-AC06:** No backend, `DELETE /api/v1/shared/{token}/guest-album/photos/{photoId}` recusa (`404`) tentativa de apagar foto pertencente a outro convidado ou ao proprietário.
