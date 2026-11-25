# MEM-009 — Contratos

Os IDs abaixo rastreiam `spec.md`. Escopo desconhecido continua `422 unsupported_grant_scope`. Isso substitui, só para `CONTRIBUTE`, a frase do MEM-008 que recusava esse escopo.

## MEM-009-BE-API-01 — Criar grant

`POST /api/v1/murals/{muralId}/grants`, mesmo corpo do MEM-008, com `scope` `VIEW` ou `CONTRIBUTE`.

## MEM-009-BE-API-02 — Abrir álbum privado do convidado

`POST /api/v1/shared/{shareToken}/guest-album`

```json
{ "guestToken": "segredo-aleatorio-do-navegador", "displayName": null }
```

`200`: `{ "displayName": null }`. O backend cria ou reabre o álbum pessoal associado ao grant e mural resolvidos pelo `shareToken`. O servidor guarda somente o SHA-256 do `guestToken`.

`GET /api/v1/shared/{shareToken}/guest-album` recebe `X-Guest-Album-Token` e retorna apenas as fotos privadas desse álbum, com estados `READY` ou `PUBLISHED` e URLs assinadas temporárias para prévia.

## MEM-009-BE-API-03 — Preparar envio anônimo

`POST /api/v1/shared/{shareToken}/guest-album/contributions`, com `X-Guest-Album-Token`:

```json
{ "contentType": "image/jpeg", "sizeBytes": 1200 }
```

`201`:

```json
{
  "contributionId": "uuid",
  "method": "PUT",
  "uploadUrl": "https://storage.invalid/assinada",
  "headers": { "Content-Type": "image/jpeg" },
  "expiresAt": "2026-10-24T12:05:00Z"
}
```

Cabeçalhos: `Cache-Control: private, no-store`, `X-Robots-Tag: noindex, nofollow, noarchive`, `Referrer-Policy: no-referrer`. A URL de upload é temporária. O corpo não traz o id do mural.

## MEM-009-BE-API-04 — Concluir envio privado

`POST /api/v1/shared/{shareToken}/guest-album/contributions/{contributionId}/complete`, com `X-Guest-Album-Token`.

`200`: `{ "contributionId": "uuid", "status": "READY" }`. A imagem validada permanece privada no MinIO e só aparece no álbum pessoal do convidado. Conclusão repetida devolve o estado atual.

## MEM-009-BE-API-05 — Compartilhar seleção do convidado

`POST /api/v1/shared/{shareToken}/guest-album/publish`, com `X-Guest-Album-Token`:

```json
{ "contributionIds": ["uuid-da-contribuicao"] }
```

`200`: `{ "items": [{ "contributionId": "uuid-da-contribuicao", "status": "PUBLISHED" }] }`. Somente fotos `READY` daquele álbum e daquele mural podem ser compartilhadas; no máximo 50 por chamada. A operação vincula as fotos selecionadas ao álbum universal. Uma seleção repetida é idempotente.

## MEM-009-BE-API-06 — Leitura compartilhada

`GET /api/v1/shared/{shareToken}` ganha `permissions.canContribute: true` quando o grant é `CONTRIBUTE`. `photos` inclui somente as fotos publicadas pelo anfitrião e as que os próprios convidados escolheram compartilhar. Fotos `READY` privadas nunca aparecem nesse payload. `moderationRequired` é `false`. Sem `displayName`.

## MEM-009-BE-API-07 — Histórico visível ao dono

`GET /api/v1/murals/{muralId}/contributions` retorna somente contribuições já compartilhadas no álbum universal. Fotos privadas `READY` não são expostas ao dono; não há fila nem aprovação do anfitrião.

```json
{
  "items": [{
    "contributionId": "uuid",
    "status": "PUBLISHED",
    "displayName": "Ana",
    "createdAt": "2026-10-24T12:00:00Z",
    "previewUrl": "https://storage.invalid/preview-assinada",
    "previewExpiresAt": "2026-10-24T12:01:00Z"
  }]
}
```

Não há endpoints de aprovação ou rejeição de contribuições no Modo Aniversário.

## MEM-009-BE-API-08 — Logo do QR e da festa

`GET /api/v1/murals/{muralId}/qr-logo` retorna `logoUrl` e `logoExpiresAt` para o proprietário, ou ambos como `null`. `POST` recebe `{ "logoDataUrl": "data:image/png;base64,..." }`; backend valida PNG até 256×256, salva o objeto privado no MinIO e retorna a URL assinada temporária. `DELETE` remove a referência do mural e o objeto do MinIO. As três rotas exigem autenticação de proprietário.

`GET /api/v1/shared/{shareToken}` inclui `mural.logoUrl` e `mural.logoExpiresAt` se o evento tiver logo. Essa URL expira em 60 segundos e é autorizada pelo próprio grant; o logo não fica público nem é uma credencial reutilizável.

## Armazenamento

Original do convidado em storage privado `originals/private/{photoId}`. Dono técnico do arquivo é o proprietário do mural. Variante continua em `derived/private/share-view/v1/`; prévias são presignadas para o convidado em seu `GET guest-album`, enquanto `GET shared` inclui somente fotos ligadas ao álbum universal.

Logo de QR em `derived/private/qr-logo/v1/{muralId}.png`. O original não é servido diretamente; a leitura de um mural com grant válido recebe URL assinada com validade curta.

Criação de aniversário usa as rotas privadas de mural do MEM-002 e exige sessão administrativa no perfil local. A rota experimental `POST /api/v1/public/murals` foi removida; os endpoints públicos `/api/v1/shared/**` são autorizados pelo grant e, para fotos pessoais, também pelo token do álbum do convidado. Envio validado retorna `READY`; somente a escolha explícita do convidado publica a foto no mural universal.
