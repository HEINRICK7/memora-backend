# MEM-001 — Importar e guardar fotografias no ambiente local

**Estado:** base aprovada para implementação local; adendo de variantes sanitizadas MEM-001-R14–R16 proposto e pendente de implementação/evidência.
**Escopo:** primeiro slice vertical do MVP; não declara o produto completo.

## Problema e usuário

Uma pessoa quer escolher fotografias no próprio dispositivo e guardar uma cópia privada para continuar criando um mural. A escolha local não pode enviar arquivos sem uma ação explícita.

## Pré-condições

- A pessoa iniciou o ambiente local pelo Docker Compose.
- Frontend, API, PostgreSQL e MinIO estão saudáveis.
- A API está no perfil local de desenvolvimento.

## Requisitos rastreáveis

- **MEM-001-R01:** selecionar arquivos mostra prévias locais e não envia fotografias à rede.
- **MEM-001-R02:** somente a ação explícita “Guardar fotografias” inicia upload.
- **MEM-001-R03:** o fluxo local aceita JPEG e PNG de até 20 MiB por arquivo; outros formatos ou tamanhos são recusados com orientação clara.
- **MEM-001-R04:** a API cria uma intenção temporária, assina um upload privado no MinIO e só marca o ativo como READY depois de verificar o objeto armazenado.
- **MEM-001-R05:** originais permanecem em bucket privado. Leitura só é possível por URL assinada de curta duração, após autorização.
- **MEM-001-R06:** a API valida tamanho real e assinatura do conteúdo; não confia apenas no MIME declarado pelo navegador.
- **MEM-001-R07:** a listagem e exclusão de fotografias respeitam o proprietário resolvido no backend.
- **MEM-001-R08:** o conjunto local — frontend, backend, PostgreSQL e MinIO — inicia pelo Compose. Estado de banco e objetos sobrevive a reinicializações por volumes nomeados.
- **MEM-001-R09:** portas publicadas no host ficam limitadas a 127.0.0.1; PostgreSQL não é publicado no host.
- **MEM-001-R10:** a identidade de proprietário deste slice é exclusiva do perfil local e não pode ser usada como identidade de produção.
- **MEM-001-R11:** excluir ativo READY associado a qualquer mural retorna `409 photo_attached`, sem marcar `DELETED`, apagar bytes ou remover vínculos; primeiro é necessário desassociá-lo de todos os murais.
- **MEM-001-R12:** exclusão de ativo e associação a mural serializam pela linha do `PhotoAsset`; nunca pode existir `MuralPhoto` para ativo `DELETED`, nem ativo associado cujo objeto MinIO tenha sido removido.
- **MEM-001-R13:** toda URL pré-assinada GET de objeto privado inclui override de resposta `Cache-Control: private, no-store` no próprio GET do MinIO. TTL de leitura vigente continua em cinco minutos. O header deve ser verificado lendo URL assinada contra MinIO real, não inferido do header JSON da API.
- **MEM-001-R14:** manter cada upload original byte a byte em `originals/private/`. Gerar variante `SHARE_VIEW` em `derived/private/` com orientação EXIF aplicada aos pixels, sem GPS/EXIF/XMP e com maior lado limitado a 2560 px, sem upscaling e com proporção preservada. Decoder usa leitura/subsampling bounded para não alocar a imagem full-resolution; preservar perfil de cor e qualidade visual validada. O limite pertence ao preview compartilhável, não ao original; a variante nunca o substitui.
- **MEM-001-R15:** a geração da variante é idempotente por `photoId` e versão do processador. Upload novo só conclui como `READY` depois da variante `SHARE_VIEW` estar pronta. A migration V4 marca fotos READY preexistentes como `PENDING`; ao publicar, o backend garante as variantes pendentes antes da transação de publicação. Falha preserva o mural em `DRAFT`, não cria grant, não emite URL compartilhável nem permite fallback ao original; retry não perde nem regrava o original.
- **MEM-001-R16:** exclusão limpa original e variante privada; falha parcial mantém qualquer objeto restante privado, sem emitir novas URLs, e permite retry de limpeza. Variantes READY têm metadados próprios e referência interna, sem storage key no contrato HTTP.

## Estados

Ativo: UPLOADING → READY | FAILED → DELETED. READY para uploads novos pressupõe original validado e SHARE_VIEW READY; em ativos preexistentes na instalação antes de V4, o estado legado READY pode coexistir temporariamente com `share_view_status=PENDING` até publish gerar a variante.

Uma intenção expirada não pode ser completada. Um objeto ainda não validado nunca aparece na galeria. A URL de upload expira em cinco minutos; a intenção expira em quinze minutos. URLs de leitura expiram em cinco minutos e o GET direto ao objeto responde `Cache-Control: private, no-store`.

## Erros

- Arquivo inválido, vazio, maior que 20 MiB ou fora dos formatos: não criar ativo persistente; explicar como corrigir.
- Intenção expirada, conclusão repetida ou estado incompatível: erro seguro de conflito.
- Objeto ausente, tamanho divergente ou assinatura incompatível: marcar a intenção como FAILED e remover o objeto inválido quando possível.
- MinIO/API indisponível: preservar seleção e prévia local; permitir nova tentativa sem selecionar novamente.
- Ativo ausente ou de outro proprietário: responder sem revelar sua existência.
- Ativo próprio ainda associado a um mural: responder `409 photo_attached` antes de alterar estado ou solicitar exclusão ao MinIO.
- Associação concorrente com exclusão: as operações serializam; se anexar vencer, exclusão é recusada e o ativo/objeto permanecem READY; se excluir vencer, associação é recusada e não cria vínculo para ativo DELETED. Falha ao remover objeto deixa-o privado e elegível a retry, sem link ativo.

## Privacidade e segurança

- Nenhum upload acontece na seleção, prévia, navegação ou recarga.
- Nomes recebidos do dispositivo não são usados como chave de storage.
- Chave S3 gerada pelo servidor, não enumerável, em originals/private/.
- Bucket sem política anônima de leitura.
- Token, URL assinada, segredo de storage e conteúdo da fotografia não entram em logs ou analytics.
- Este slice não publica mural nem cria link de compartilhamento.
- As regras de associação ordenada pertencem a MEM-006. A exclusão de um ativo respeita esses vínculos e não os remove implicitamente.
- O modo local usa uma identidade fixa de desenvolvimento. Compose publica serviços apenas em loopback e não representa um modo seguro para deploy público.

## Dependências

- Next.js, React e TypeScript conforme FRONTEND_SPEC.md.
- Java 21, Spring Boot, PostgreSQL, Flyway e API REST conforme especificação de backend.
- MinIO com API S3, AWS SDK Java 2.x para intents assinadas e Docker Compose.
- Testes reais de API/storage no ambiente Docker local.

## Fora de escopo

Autenticação OIDC, contas múltiplas, murais, templates, composição, publicação, links compartilhados, contribuições, moderação, Google Fotos, câmera, edição e exportação.

## Critérios observáveis

1. A seleção de arquivo não gera requisição de upload nem grava arquivo remoto.
2. A ação Guardar fotografias cria intents e envia os bytes ao URL assinado do MinIO.
3. A galeria só mostra ativos READY retornados pela API.
4. A API recusa dados cuja extensão, MIME declarado, bytes, tamanho ou proprietário violem o contrato.
5. Um GET anônimo ao objeto no MinIO falha; o URL assinado temporário funciona até expirar e seu GET real retorna `Cache-Control: private, no-store`.
6. Reiniciar Compose mantém os ativos e metadados.
7. Todos os serviços necessários podem ser iniciados sem instalar Java ou Node na máquina host.
8. Excluir foto vinculada resulta em conflito sem mudar estado, metadados, vínculos ou bytes no MinIO; após desassociar de todos os murais, a exclusão pode prosseguir.
9. Corrida entre associar foto e excluí-la termina em uma única operação válida: vínculo + ativo/objeto READY, ou ativo DELETED sem vínculo. Nunca há FK quebrada, link para foto DELETED ou objeto ativo removido; falha de limpeza deixa objeto privado e retryable.
10. Concluir upload conserva hash/bytes do original, cria variante privada sem GPS/EXIF/XMP e com orientação visual correta antes de disponibilizar o ativo para compartilhamento.
11. Upload novo gera variante idempotente e só confirma `READY` quando original e `SHARE_VIEW` estão íntegros.
12. Migration V4 marca READY legado sem variante como pendente; a publicação gera automaticamente essas variantes antes da transação de mudança de estado. Se geração falhar, o mural continua DRAFT e sem grant; retry é seguro.
13. A leitura compartilhada assina somente a chave `SHARE_VIEW`; variante ausente/pendente ou erro de storage falha fechado, sem fallback ao original.
14. Falha de geração ou exclusão parcial nunca cria acesso público ao original e mantém retry seguro.

## Decisões em aberto

- Provedor de identidade de produção continua pendente; este slice não decide Cognito, Auth.js ou outro provedor.
- Política de retenção e exclusão definitiva seguem ADR operacional pendente; geração SHARE_VIEW é coberta por MEM-001-R14–R16 e exigida por MEM-008.
- Quota e retenção de objeto cujo estado seja DELETED seguem ADRs operacionais; a exclusão concorrente com vínculo segue MEM-001-R11/R12.
- O limite de 20 MiB é uma regra inicial configurável, a ser revisto com evidência de compatibilidade móvel.
