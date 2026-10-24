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

Apenas objetos privados são entregues. O fluxo não publica mural nem URL pública permanente. Derivados com remoção de EXIF pertencem a uma feature futura antes de qualquer fotografia ser exibida por link compartilhado.

## MEM-001-ADR-08 — Build local do MinIO OSS

A release oficial mais recente de minio/minio consultada em 24/10/2026 é RELEASE.2025-10-15T17-29-55Z. As instruções da própria release dizem para construir a imagem de container a partir do código-fonte. O Compose constrói essa versão fixada em Docker em vez de presumir uma imagem precompilada no registry. Fonte: https://github.com/minio/minio/releases/tag/RELEASE.2025-10-15T17-29-55Z.

Risco de segurança: essa release upstream tem quase um ano na data desta decisão. MinIO fica restrito ao ambiente local em loopback; não publicar esse servidor como produção sem confirmar suporte e correções de segurança atuais.

## MEM-001-ADR-09 — CORS global no MinIO OSS

A release OSS fixada responde `501 NotImplemented` a `PutBucketCors`; CORS por bucket não está implementado nessa edição. O Compose configura `MINIO_API_CORS_ALLOW_ORIGIN` com as duas origens locais exatas. O middleware OSS define métodos e cabeçalhos globalmente no endpoint S3, portanto essa política cobre os buckets do serviço; o ambiente local mantém apenas o bucket privado do Memora. CORS controla acesso do navegador, não substitui assinatura nem autorização do objeto.
