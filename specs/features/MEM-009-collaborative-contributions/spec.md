# MEM-009 — Contribuição colaborativa no mural publicado

**Incremento:** I-02 de `PRODUCT_SPEC_MURAIS_COLABORATIVOS_ANIMADOS.md`.  
**Modo Aniversário:** o proprietário cria um grant `CONTRIBUTE` e o frontend gera o QR a partir do link de uso único. Ao escanear, o convidado vê a abertura da festa e toca em Começar. Ele pode informar um nome opcional, envia fotos para seu álbum pessoal privado no MinIO e escolhe quais compartilhar no álbum universal.

**Fora deste slice:** rate limit (I-03), Meu Niver 3D e os demais motores (I-04+), exportação de vídeo, Google Fotos.

## Comportamento

O dono de um mural `PUBLISHED` cria um grant `CONTRIBUTE`. O token autoriza ver o mural e iniciar um álbum pessoal anônimo, identificado por uma chave aleatória criada no navegador. Cada upload validado fica em storage privado no MinIO com estado `READY` e não entra no mural universal até o próprio convidado selecionar e compartilhar. Não há fila nem aprovação do anfitrião. `VIEW` não envia. Grant revogado ou expirado não permite abrir, enviar ou publicar. O nome do colaborador é opcional, no máximo 40 caracteres. O visitante não vira usuário do mural. Limite de tamanho e tipos continuam os de MEM-001 (JPEG/PNG e `maxUploadBytes`). Não há quota nova nem rate limit neste slice.

## Requisitos

| ID | Regra |
|---|---|
| MEM-009-BE-R01 | Grant `CONTRIBUTE` é criado no mural publicado, com o mesmo contrato de idempotência e segredo único do VIEW. |
| MEM-009-BE-R02 | Token `VIEW` recebe `403 contribution_not_allowed` e não grava envio. |
| MEM-009-BE-R03 | Token revogado, expirado, malformado ou de mural não publicado nega o envio sem revelar o mural. |
| MEM-009-BE-R04 | Upload válido termina `READY` no álbum privado do convidado, sem ligação ao mural universal. |
| MEM-009-BE-R05 | O convidado consegue listar e pré-visualizar somente fotos do próprio álbum, usando seu token privado. |
| MEM-009-BE-R06 | Só IDs `READY` do álbum associado ao token podem ser publicados; o visitante não recebe permissão de editar ou remover depois de publicar. |
| MEM-009-BE-R07 | Grant revogado ou expirado não autoriza novos envios nem conclusão de upload em andamento. |
| MEM-009-BE-R08 | Conclusão repetida não duplica a contribuição e retorna seu estado atual. A publicação repetida não duplica vínculo no mural. |
| MEM-009-BE-R09 | `displayName` não aparece em `GET /shared/{token}`. `canContribute` é verdadeiro só no grant `CONTRIBUTE`. |
| MEM-009-BE-R10 | Cada convidado tem álbum privado separado; fotos só passam ao álbum universal por escolha explícita do dono daquele álbum. |
