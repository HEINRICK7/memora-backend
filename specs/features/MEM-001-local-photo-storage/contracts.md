# MEM-001 — Contratos

## MEM-001-API-01 — Criar intenção de upload

POST /api/v1/uploads

Pedido:

    {
      "fileName": "foto.png",
      "contentType": "image/png",
      "sizeBytes": 123456
    }

Resposta 201 Created:

    {
      "uploadId": "uuid",
      "method": "PUT",
      "uploadUrl": "temporary-signed-url",
      "requiredHeaders": {
        "Content-Type": "image/png"
      },
      "expiresAt": "ISO-8601 instant"
    }

O segredo da URL não pode ser registrado em logs. A API valida formato, tamanho positivo e limite de 20 MiB antes de emitir a URL. O cliente não escolhe bucket nem chave.

## MEM-001-API-02 — Enviar bytes

PUT {uploadUrl} para MinIO, com o corpo binário original e o Content-Type retornado. CORS do endpoint MinIO permite somente as origens web locais configuradas. Na release OSS fixada, o CORS é global ao serviço e os métodos/cabeçalhos vêm do middleware MinIO; CORS não concede leitura anônima e o bucket continua privado.

## MEM-001-API-03 — Confirmar upload

POST /api/v1/uploads/{uploadId}/complete

Resposta 200 OK:

    {
      "id": "uuid",
      "status": "READY",
      "contentType": "image/png",
      "sizeBytes": 123456,
      "previewUrl": "temporary-signed-url",
      "previewExpiresAt": "ISO-8601 instant"
    }

A operação é idempotente quando o ativo já está READY e continua pertencendo ao mesmo proprietário. O backend inspeciona o objeto armazenado antes da transição. Se houver divergência, não expõe chave nem URL de objeto.

O `previewUrl` é uma URL GET assinada com o override S3 `response-cache-control=private, no-store`. O header deve aparecer no GET real servido pelo MinIO; `Cache-Control` da resposta da API não substitui esse header. TTL de leitura preservado: cinco minutos.

Essa URL continua sendo apenas para o proprietário e aponta ao original privado. `POST /complete` gera também a variante interna `SHARE_VIEW`; nenhum key/URL da variante é serializado. A conclusão permanece idempotente e confirma variante READY antes de anunciar sucesso de preparação para compartilhamento.

## MEM-001-API-04 — Listar fotos READY

GET /api/v1/photos

Resposta 200 OK:

    {
      "items": [
        {
          "id": "uuid",
          "status": "READY",
          "contentType": "image/jpeg",
          "sizeBytes": 123456,
          "previewUrl": "temporary-signed-url",
          "previewExpiresAt": "ISO-8601 instant"
        }
      ]
    }

Somente fotos READY do proprietário atual aparecem. URLs são temporários e nunca armazenados no banco. Cada `previewUrl` usa o override S3 `response-cache-control=private, no-store`; o cliente recebe esse header ao buscar o objeto diretamente no MinIO. TTL de leitura preservado: cinco minutos.

`GET /photos` continua emitindo somente URL privada owner para o original; a resposta não inclui `share_view_storage_key`, estado interno de processamento ou URL da variante.

## MEM-001-API-05 — Excluir fotografia

DELETE /api/v1/photos/{photoId} retorna 204 No Content quando a foto pertence ao proprietário e não está associada a mural. Ativo inexistente ou de outro proprietário não revela existência.

- Se houver qualquer vínculo `MuralPhoto`, responder `409 Conflict`, `code: photo_attached`; não marcar `DELETED`, não alterar vínculos e não solicitar remoção ao MinIO. O cliente deve desassociar o ativo de todos os murais antes de excluir.
- Associação concorrente e exclusão bloqueiam/serializam pela mesma linha do ativo. Se a associação vencer, a exclusão observa o vínculo e responde `409 photo_attached`. Se a exclusão vencer, persiste `DELETED` e a associação posterior responde `409 photo_not_ready` sem inserir vínculo.
- Após aceitar exclusão sem vínculos, o banco transiciona o ativo para `DELETED` e solicita remoção do objeto. Se MinIO falhar, o objeto permanece privado, não pode receber nova URL assinada e a operação de limpeza pode ser repetida.

## Identidade e erros

- Perfil local: principal fixo local-development-owner, habilitado somente por configuração explícita do Compose local.
- Perfil diferente do local exige autenticação configurada; não aceita principal fixo nem header de identidade arbitrário.
- Erros seguem Problem Details com status, code, detail e traceId.
- 400 formato/metadados inválidos; 401 não autenticado fora do perfil local; 404 recurso não acessível; 409 estado conflitante (`photo_attached` quando houver vínculo); 413 arquivo excede limite; 415 formato não aceito; 5xx dependência indisponível.
- CORS da API e do endpoint S3 MinIO permite apenas http://localhost:{MEMORA_WEB_PORT} e http://127.0.0.1:{MEMORA_WEB_PORT} no perfil local (3000 por padrão). O MinIO OSS fixado aplica essa lista globalmente ao endpoint e usa o conjunto fixo de métodos/cabeçalhos do middleware; isso não concede autorização nem leitura anônima.
- Nenhuma resposta inclui credenciais, chave S3, token compartilhável ou URL permanente privada.

## Persistência

- PostgreSQL guarda owner, chave interna, MIME verificado, tamanho, dimensões e estado.
- MinIO guarda o objeto em bucket privado com chave aleatória gerada pelo backend.
- O contrato público nunca contém storageKey.

## Rastreabilidade do requisito de cache de mídia

| Requisito | Acceptance | API | Prova requerida |
|---|---|---|---|
| MEM-001-R13 | MEM-001-AC11 | MEM-001-API-03 e MEM-001-API-04 | Compose: GET da URL assinada contra MinIO retorna `Cache-Control: private, no-store`. |

## Rastreabilidade R–AC–API da variante

| Requisito | Acceptance | API/infra | Prova |
|---|---|---|---|
| MEM-001-R14 | MEM-001-AC12 | MEM-001-API-03, `PhotoStorage` | Compose MinIO: original preservado por hash; bytes SHARE_VIEW confirmam lado maior ≤2560 px sem upscaling, proporção/orientação correta e ausência de GPS/EXIF/XMP; transformação usa decode/subsampling bounded sem alegação de latência. |
| MEM-001-R15 | MEM-001-AC13 | MEM-001-API-03, migration V4, MEM-008-BE-API-03 | PostgreSQL + MinIO reais: READY legado começa PENDING; publish gera variante idempotente antes da transação e falha deixa DRAFT sem grant. |
| MEM-001-R16 | MEM-001-AC14 | MEM-001-API-05, `DeletePhoto`/`PhotoStorage` | Falha injetada: nenhum presign após DELETED; retry limpa ambos os objetos e não toca outros assets. |

### Persistência V4 de variante

V4 adiciona em `photo_assets` campos internos `share_view_status`, `share_view_storage_key`, `share_view_content_type`, `share_view_size_bytes`, `share_view_width`, `share_view_height`, `share_view_processor_version` e `share_view_ready_at`. Só `share_view_status=READY` pode ter key e metadados completos; nenhum campo é retornado ao cliente. Todas as fotos preexistentes em `READY` são iniciadas como `PENDING`, sem a migration alegar que transformou bytes.

Uploads novos só passam a `READY` depois de gerar/validar `SHARE_VIEW` idempotentemente. Para fotos READY anteriores, `PublishMural` detecta `PENDING`, lê o original privado, gera e valida `derived/private/share-view/v1/` e persiste READY de forma repetível antes de iniciar a transação que muda o mural para `PUBLISHED`. Se qualquer variante falhar, publicação termina sem alteração de estado/versão/timestamp e sem grant; o original permanece intacto e a próxima tentativa repete com o mesmo destino. Não há comando/etapa manual no fluxo MVP local. GET compartilhado consulta e assina exclusivamente `share_view_storage_key` com estado READY; nunca usa `storage_key` original como fallback.
