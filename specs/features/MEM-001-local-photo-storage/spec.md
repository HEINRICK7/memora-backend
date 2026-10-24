# MEM-001 — Importar e guardar fotografias no ambiente local

**Estado:** aprovado para implementação local.  
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

## Estados

Ativo: UPLOADING → READY | FAILED → DELETED.

Uma intenção expirada não pode ser completada. Um objeto ainda não validado nunca aparece na galeria. A URL de upload expira em cinco minutos; a intenção expira em quinze minutos. URLs de leitura expiram em cinco minutos.

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
5. Um GET anônimo ao objeto no MinIO falha; o URL assinado temporário funciona até expirar.
6. Reiniciar Compose mantém os ativos e metadados.
7. Todos os serviços necessários podem ser iniciados sem instalar Java ou Node na máquina host.
8. Excluir foto vinculada resulta em conflito sem mudar estado, metadados, vínculos ou bytes no MinIO; após desassociar de todos os murais, a exclusão pode prosseguir.
9. Corrida entre associar foto e excluí-la termina em uma única operação válida: vínculo + ativo/objeto READY, ou ativo DELETED sem vínculo. Nunca há FK quebrada, link para foto DELETED ou objeto ativo removido; falha de limpeza deixa objeto privado e retryable.

## Decisões em aberto

- Provedor de identidade de produção continua pendente; este slice não decide Cognito, Auth.js ou outro provedor.
- Retenção, exclusão definitiva e geração de derivados ficam para a feature de publicação.
- Quota e retenção de objeto cujo estado seja DELETED seguem ADRs operacionais; a exclusão concorrente com vínculo segue MEM-001-R11/R12.
- O limite de 20 MiB é uma regra inicial configurável, a ser revisto com evidência de compatibilidade móvel.
