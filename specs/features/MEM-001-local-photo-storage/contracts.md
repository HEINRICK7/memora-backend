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

Somente fotos READY do proprietário atual aparecem. URLs são temporários e nunca armazenados no banco.

## MEM-001-API-05 — Excluir fotografia

DELETE /api/v1/photos/{photoId} retorna 204 No Content. A operação autoriza pelo proprietário; ativo inexistente ou de outro proprietário não revela existência. Marca exclusão no banco e solicita remoção do objeto.

## Identidade e erros

- Perfil local: principal fixo local-development-owner, habilitado somente por configuração explícita do Compose local.
- Perfil diferente do local exige autenticação configurada; não aceita principal fixo nem header de identidade arbitrário.
- Erros seguem Problem Details com status, code, detail e traceId.
- 400 formato/metadados inválidos; 401 não autenticado fora do perfil local; 404 recurso não acessível; 409 estado conflitante; 413 arquivo excede limite; 415 formato não aceito; 5xx dependência indisponível.
- CORS da API e do endpoint S3 MinIO permite apenas http://localhost:{MEMORA_WEB_PORT} e http://127.0.0.1:{MEMORA_WEB_PORT} no perfil local (3000 por padrão). O MinIO OSS fixado aplica essa lista globalmente ao endpoint e usa o conjunto fixo de métodos/cabeçalhos do middleware; isso não concede autorização nem leitura anônima.
- Nenhuma resposta inclui credenciais, chave S3, token compartilhável ou URL permanente privada.

## Persistência

- PostgreSQL guarda owner, chave interna, MIME verificado, tamanho, dimensões e estado.
- MinIO guarda o objeto em bucket privado com chave aleatória gerada pelo backend.
- O contrato público nunca contém storageKey.
