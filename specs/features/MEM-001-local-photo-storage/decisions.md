# MEM-001 — Decisões

## MEM-001-ADR-01 — MinIO local

MinIO é o storage de objetos do ambiente local e implementa o contrato S3 usado pelo adapter. Bucket privado, volume Docker persistente e URL assinada curta para acesso do navegador. O MinIO local não representa serviço de produção.

## MEM-001-ADR-02 — URL assinada para upload direto

O backend gera URL de upload assinada para evitar passar o corpo de fotografias pela API. O SDK usa endpoint interno http://minio:9000 para operações do backend e endpoint público http://localhost:9000 somente ao assinar URLs destinadas ao navegador local. Credenciais e região de assinatura são iguais nos dois usos.

## MEM-001-ADR-03 — Identidade local

Autenticação real permanece em aberto conforme FRONTEND_SPEC.md e BACKEND_SPEC.md. Para testar persistência sem fingir OAuth, o perfil local usa um único proprietário de desenvolvimento. Esse perfil só é ativado pelo Compose com portas em loopback; ambientes não locais devem exigir autenticação real antes de expor rotas privadas.

## MEM-001-ADR-04 — Formatos e limite inicial

Este slice persiste JPEG e PNG, até 20 MiB por arquivo. HEIC e WebP podem ser usados localmente apenas se o navegador conseguir pré-visualizar, mas não serão persistidos até existir conversão validada. O limite fica configurável e deve ser reavaliado em aparelhos reais antes de lançamento.

## MEM-001-ADR-05 — Compose de workspace

Como frontend e backend pertencem a repositórios separados e não há repositório de plataforma informado, o arquivo Compose de integração fica na raiz local de trabalho e constrói os dois diretórios vizinhos. Não se cria nem publica um terceiro repositório nesta feature.

## MEM-001-ADR-06 — Versões de container

Imagens de base devem usar tags fixas, nunca latest. A versão do MinIO deve ser compatível com a documentação de container oficial e fixada no Compose. PostgreSQL e imagens base dos serviços também ficam fixados e são registrados no arquivo de integração.

## MEM-001-ADR-07 — Segurança de mídia

Apenas objetos privados são entregues; a publicação e link VIEW são definidos em MEM-008. Este ADR foi supersedido por MEM-001-ADR-12: qualquer mídia compartilhada usa derivado sanitizado SHARE_VIEW e nunca o original.

## MEM-001-ADR-08 — Build local do MinIO OSS

A release oficial mais recente de minio/minio consultada em 24/10/2026 é RELEASE.2025-10-15T17-29-55Z. As instruções da própria release dizem para construir a imagem de container a partir do código-fonte. O Compose constrói essa versão fixada em Docker em vez de presumir uma imagem precompilada no registry. Fonte: https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z.

Risco de segurança: essa release upstream tem quase um ano na data desta decisão. MinIO fica restrito ao ambiente local em loopback; não publicar esse servidor como produção sem confirmar suporte e correções de segurança atuais.

## MEM-001-ADR-09 — CORS global no MinIO OSS

A release OSS fixada responde `501 NotImplemented` a `PutBucketCors`; CORS por bucket não está implementado nessa edição. O Compose configura `MINIO_API_CORS_ALLOW_ORIGIN` com as duas origens locais exatas. O middleware OSS define métodos e cabeçalhos globalmente no endpoint S3, portanto essa política cobre os buckets do serviço; o ambiente local mantém apenas o bucket privado do Memora. CORS controla acesso do navegador, não substitui assinatura nem autorização do objeto.

## MEM-001-ADR-10 — Exclusão protegida por vínculos de mural

**Decisão:** `DELETE /photos/{photoId}` não desassocia implicitamente o ativo. Enquanto houver vínculo `MuralPhoto`, responde `409 photo_attached`; a limpeza exige remoção explícita dos vínculos por MEM-006.\
**Concorrência:** associação e exclusão serializam na mesma linha de `PhotoAsset` dentro do PostgreSQL. A exclusão verifica ausência de vínculos e transiciona READY→DELETED antes de solicitar remoção ao MinIO.\
**Recuperação:** como DB e MinIO não compartilham transação, falha na remoção mantém o objeto privado e a limpeza é repetível; o ativo DELETED não pode ser associado ou assinado para leitura. Essa decisão impede vínculo órfão e evita apagar conteúdo de outro mural.

## MEM-001-ADR-11 — Impedir cache da resposta de leitura MinIO

**Decisão proposta:** toda URL pré-assinada GET emitida pelo `PhotoStorage` força `Cache-Control: private, no-store` na resposta direta do objeto MinIO por meio do override S3 `response-cache-control`; preservar o TTL de leitura existente de cinco minutos.

**Motivo:** o header `Cache-Control` da API JSON não governa a resposta subsequente do GET direto ao MinIO. URLs assinadas são credenciais bearer e seus bytes não devem ser reutilizáveis por caches após a janela de acesso.

**Consequência:** o adaptador `S3PhotoStorage` deve configurar o override no `GetObjectRequest` antes de assinar; não editar a query depois de assinar. MEM-001-AC11 exige prova Compose lendo a URL emitida e observando o header real do MinIO. A prova ainda está pendente; as URLs PUT de upload não mudam.

## MEM-001-ADR-12 — Preservar original e derivar mídia sanitizada para compartilhamento

**Decisão proposta:** manter o objeto enviado byte a byte em `originals/private/` e gerar uma variante interna `SHARE_VIEW` em `derived/private/share-view/v1/`. A transformação aplica orientação EXIF aos pixels e remove GPS, EXIF e XMP, sem modificar o original. SHARE_VIEW limita o maior lado a 2560 px sem upscaling, preserva aspecto/perfil de cor, e usa decode/subsampling bounded para evitar alocação full-resolution; qualidade visual é verificada com fixtures reais. 2560 px é limite de preview compartilhável, não do original.

**Motivo:** links VIEW não podem expor a cópia original que pode conter localização ou metadados. O original é conteúdo de propriedade do usuário e não deve ser substituído por reencode.

**Consequência:** `PhotoAsset` registra estado/key/metadata/version da variante; `CompletePhotoUpload` gera idempotentemente antes de confirmar READY e `DeletePhoto` limpa ambos os objetos. Migration V4 marca READY legado `PENDING`; `PublishMural` garante as variantes pendentes idempotentemente antes da transação de publicação. Falha deixa DRAFT, sem grant e sem URL, preservando original e permitindo retry; GET compartilhado nunca faz fallback ao original. O original continua privado e consultável pelo owner. Política de retenção/backups de produção permanece ADR pendente.
