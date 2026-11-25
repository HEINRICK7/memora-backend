# MEM-009 — Aceite

| ID | Critério |
|---|---|
| MEM-009-BE-AC01 | Dado mural publicado e token `CONTRIBUTE` ativo, quando o convidado conclui um JPEG/PNG, então a contribuição fica `READY` e privada no MinIO. |
| MEM-009-BE-AC02 | Dado envio concluído, quando o mesmo convidado reabre seu álbum, então consegue rever a foto, enquanto outros convidados e o álbum universal não a recebem. |
| MEM-009-BE-AC03 | Dado upload concluído, quando o convidado não escolheu compartilhar, então o dono não precisa moderar e a foto não está no mural universal. |
| MEM-009-BE-AC04 | Dado qualquer convidado, quando tenta editar ou remover uma foto já publicada, então a operação não é autorizada. |
| MEM-009-BE-AC05 | Dado token revogado ou expirado, quando tenta novo upload, então o envio é negado. |
| MEM-009-BE-AC06 | Dado token `VIEW`, quando tenta contribuir, então recebe `403 contribution_not_allowed`. |
| MEM-009-BE-AC07 | Dado contribuição `READY`, quando o convidado seleciona e publica a foto, então ela fica `PUBLISHED` no álbum universal. |
| MEM-009-BE-AC08 | Conclusões e publicações repetidas não duplicam contribuição nem vínculo da foto no mural. |
| MEM-009-BE-AC09 | Dado álbum pessoal de um convidado, quando ele tenta publicar UUIDs de outro álbum, então a API nega a operação e não revela as fotos privadas. |
