# MEM-011 — Critérios de Aceite

## Backend

- **MEM-011-AC01:** `processUploadedPhoto` lê o arquivo original do S3 apenas uma vez (1 `headObject` + 1 `getObject`), valida formato real e limite de 250 MP antes da decodificação, aplica orientação EXIF (ex.: orientação 6 rotaciona 90° trocando largura/altura), gera `share-view` (`<= 2560 px`, sem EXIF) e `thumbnail` (`<= 480 px`, sem EXIF) com interpolação suave, e apaga o arquivo temporário em disco.
- **MEM-011-AC02:** `completeContribution` e `publish` executam processamento de mídia fora de transação JDBC/locks `FOR UPDATE`.
- **MEM-011-AC03:** `PreparePhotoUpload` bloqueia novos uploads quando o proprietário atinge `maxPhotosPerOwner` (`422` / `quota_exceeded`).
- **MEM-011-AC04:** `MuralPhotoCollection.append` e `BirthdayModeService` bloqueiam inclusão quando o mural atinge `maxPhotosPerMural` ou o grant atinge `maxContributionsPerGrant`.
- **MEM-011-AC05:** `UploadRateLimiter` permite até o limite configurado por janela e rejeita excedentes retornando `429 Too Many Requests` com `Retry-After`.
- **MEM-011-AC06:** `OrphanUploadCleanupJob` marca uploads `UPLOADING` vencidos como `FAILED` e remove seus objetos no storage.
- **MEM-011-AC07:** `ListPhotos` retorna páginas limitadas com `nextCursor` e `thumbnailUrl`; `ListMuralPhotos` busca metadados em lote (sem N+1) e inclui `thumbnailUrl`.
- **MEM-011-AC08:** `GET /api/v1/shared/{token}` aceita `limit` e `offset`, retorna `thumbnailUrl`, `totalPhotos` e `nextOffset`, com TTL de mídia >= 10 minutos.

## Frontend

- **MEM-011-AC09:** `validatePhotoFile` aceita `image/jpeg`, `image/png`, `image/webp`, `image/heic` e `image/heif`, rejeita `image/gif`/vazios/`> 20 MiB` e limita seleção por lote a `20` arquivos com aviso claro.
- **MEM-011-AC10:** `runUploadQueue` processa até 3 arquivos concorrentemente, executa retry com backoff para falhas transitórias e preserva a ordem/resultado individual de sucessos e falhas.
- **MEM-011-AC11:** `optimizePhotoForUpload` redimensiona/comprime imagens grandes e converte WebP/HEIC para JPEG/PNG quando suportado pelo navegador, com fallback seguro para o `File` original quando APIs de bitmap/canvas não existem.
- **MEM-011-AC12:** `PhotoLibrary` e `SharedMuralView` exibem `thumbnailUrl ?? previewUrl` no grid, `previewUrl` no lightbox, e oferecem carregamento paginado ("Carregar mais fotos") quando houver próxima página.
- **MEM-011-AC13:** `createCanvasCompositionExportRenderer` utiliza Web Worker + `OffscreenCanvas` quando disponíveis no navegador (sem mocks DOM injetados), suportando progresso e cancelamento via `AbortSignal`, e cai para o renderer `HTMLCanvasElement` quando indisponíveis.
