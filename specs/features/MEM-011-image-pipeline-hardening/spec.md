# MEM-011 — Endurecimento do Pipeline de Imagens (Backend + Frontend)

**Status:** Aprovada para implementação  
**Escopo:** `memora-backend` e `memora-frontend`  
**Motivação:** Eliminar os 10 gargalos de performance, memória, concorrência, segurança/abuso, qualidade visual e compatibilidade identificados no processamento de fotografias.

## Requisitos rastreáveis

- **MEM-011-R01 (Transações curtas sem I/O externo):** `completeContribution` e `publish` não devem manter transações de banco nem locks `FOR UPDATE` durante downloads/uploads S3 ou decodificação `ImageIO`. O acesso ao banco ocorre apenas em transações curtas antes e depois do processamento de mídia.
- **MEM-011-R02 (Processamento em passagem única):** A validação do arquivo original e a geração de derivados (`share-view` e `thumbnail`) devem baixar o objeto original do S3 apenas uma vez e decodificá-lo em uma única etapa (`processUploadedPhoto`).
- **MEM-011-R03 (Uso limitado de heap + controle de concorrência):** O streaming S3 → `ImageIO` deve usar buffer em arquivo temporário (removido em `finally`) em vez de `MemoryCacheImageInputStream` em RAM; a proteção contra *decompression bomb* (`<= 250 MP`) deve rodar antes de qualquer leitura de pixels; e um semáforo configurável (`memora.storage.max-concurrent-processing`, padrão `4`) deve limitar decodificações simultâneas.
- **MEM-011-R04 (Cotas, limite de lote, rate limit e limpeza de órfãos):**
  - Cota por biblioteca do proprietário (`memora.limits.max-photos-per-owner`, padrão `500`).
  - Cota por mural (`memora.limits.max-photos-per-mural`, padrão `300`), garantida no domínio `MuralPhotoCollection` e no fluxo de contribuições.
  - Cota por link de contribuição (`memora.limits.max-contributions-per-grant`, padrão `200`).
  - Limite de lote por seleção no frontend (`MAX_BATCH_UPLOAD_COUNT = 20`).
  - Rate limit (`429 Too Many Requests` + `Retry-After`) para `/api/v1/uploads` e `/api/v1/shared/{token}/guest-album/contributions`.
  - Job agendado (`OrphanUploadCleanupJob`) que expira uploads `UPLOADING` vencidos e remove objetos órfãos do S3.
- **MEM-011-R05 (Thumbnails e eliminação de N+1):** Gerar miniatura dedicada (`480 px` maior lado, JPEG `0.80`, sem EXIF) em `derived/private/thumb/v1/{photoId}.jpg`, exposta como `thumbnailUrl` aditiva em `GET /api/v1/photos`, `GET /api/v1/murals/{id}/photos` e `GET /api/v1/shared/{token}`. `ListMuralPhotos` carrega fotos em lote sem N+1. `previewUrl` prefere `share-view` quando disponível.
- **MEM-011-R06 (Paginação e TTL/cache de mídia):**
  - `GET /api/v1/photos?limit=&cursor=` paginado (padrão `50`, máx. `100`) retornando `{ items, nextCursor }`.
  - `GET /api/v1/shared/{token}?limit=&offset=` paginado (padrão `60`, máx. `120`) retornando `totalPhotos` e `nextOffset` aditivos.
  - TTL de URLs assinadas ampliado para `15 min` (privado) e `10 min` (compartilhado), com `Cache-Control: private, max-age=600` nos objetos derivados imutáveis no S3 (mantendo `private, no-store` nas respostas JSON da API).
- **MEM-011-R07 (Upload concorrente com retry e otimização client-side):** Frontend sobe múltiplas fotos com pool de concorrência limitada (`concurrency = 3`), retry com backoff para falhas transitórias e otimização/redimensionamento prévio no navegador (máx. `2560 px`, JPEG `0.88`, orientação EXIF preservada) com fallback transparente para o arquivo original quando Canvas/ImageBitmap não estiverem disponíveis.
- **MEM-011-R08 (Qualidade de redimensionamento e orientação EXIF no backend):** Ler tag `EXIF Orientation` via `metadata-extractor`, aplicar transformação afim correta para todas as 8 orientações, usar redimensionamento suave (`Graphics2D` bicúbico/bilinear multi-etapa) em vez de apenas subsampling inteiro, e retornar dimensões lógicas pós-rotação.
- **MEM-011-R09 (Suporte a WebP e HEIC):** Frontend aceita `image/jpeg`, `image/png`, `image/webp` e `image/heic`/`image/heif` (quando decodificáveis pelo navegador) convertendo WebP/HEIC para JPEG/PNG antes do upload; backend aceita JPEG/PNG (e WebP quando houver reader disponível, mantendo validação rigorosa por magic bytes).
- **MEM-011-R10 (Exportação off-main-thread):** Renderer de exportação de composições usa Web Worker + `OffscreenCanvas` quando suportados no navegador (com progresso e cancelamento real), mantendo fallback seguro para `HTMLCanvasElement` na main thread.

## Privacidade e segurança

- EXIF/GPS/IPTC/XMP continuam removidos de todos os derivados (`share-view` e `thumbnail`).
- Tokens e URLs assinadas nunca entram em logs ou mensagens de erro.
- Derivados permanecem em bucket privado (`derived/private/...`) com acesso somente por URL pré-assinada e `Cache-Control: private, max-age=600` (nunca `public`).
