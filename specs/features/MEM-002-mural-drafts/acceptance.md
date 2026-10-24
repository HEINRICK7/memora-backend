# MEM-002 — Critérios de aceite

## MEM-002-AC01 — Criar rascunho do proprietário

**Given** um principal autenticado (ou o principal local do Compose)  
**When** envia título e descrição válidos com `Idempotency-Key` UUID  
**Then** a API responde `201` com UUID, `status: DRAFT`, `version: 1` e timestamps iguais  
**And** owner é determinado no backend e não aparece na resposta.

## MEM-002-AC02 — Repetir criação sem duplicar

**Given** uma solicitação concluída cuja resposta não chegou ao cliente  
**When** o cliente repete a mesma chave e o mesmo texto normalizado  
**Then** a API retorna o mesmo mural e `Idempotency-Replayed: true`  
**And** existe apenas uma linha para aquela chave e owner.

## MEM-002-AC03 — Impedir reutilização conflitante da chave

**Given** uma chave já usada para um mural  
**When** o owner repete a chave com título ou descrição diferente após normalização  
**Then** recebe `409` sem título, id ou conteúdo do mural anterior no erro.

## MEM-002-AC04 — Validar texto sem persistir dados inválidos

**Given** título vazio, título acima de 100 pontos Unicode ou descrição acima de 500 após trim Unicode  
**When** tenta criar o mural  
**Then** responde `422` Problem Details com `field` e `code` estável  
**And** nenhum mural é persistido nem o texto enviado é refletido.

## MEM-002-AC05 — Listar somente murais próprios

**Given** dois principais com murais distintos no PostgreSQL  
**When** cada um lista `/api/v1/murals`  
**Then** recebe apenas os murais do próprio `UserId`  
**And** não infere título ou existência de murais do outro.

## MEM-002-AC06 — Filtrar por status

**Given** murais do owner em mais de um status  
**When** lista com `status=DRAFT`, `PUBLISHED` ou `ARCHIVED`  
**Then** cada item retornado corresponde ao filtro  
**And** status inválido responde `400`.

## MEM-002-AC07 — Paginar em ordem estável

**Given** mais murais que o limite, inclusive timestamps iguais  
**When** percorre páginas pelo `nextCursor` sob o mesmo filtro de status  
**Then** segue `createdAt DESC, id DESC` sem repetição nem perda  
**And** usar o cursor com outro status, ou cursor/limite inválido, responde `400`.

## MEM-002-AC08 — Consultar próprio mural

**Given** que o principal atual possui o mural  
**When** consulta `GET /api/v1/murals/{id}`  
**Then** recebe os dados persistidos e `Cache-Control: private, no-store`.

## MEM-002-AC09 — Não revelar mural de outro proprietário

**Given** UUID existente de outro principal  
**When** consulta esse UUID  
**Then** recebe o mesmo `404` usado para UUID inexistente  
**And** nenhuma informação privada retorna.

## MEM-002-AC10 — Migrar owners e fotos da V1

**Given** schema V1 com linhas `UPLOADING`, `READY`, `FAILED` e `DELETED` do owner local e uma imagem READY armazenada  
**When** Flyway aplica V2 com o issuer local  
**Then** cada identidade vira exatamente um `users.id` UUID e cada foto aponta para esse UUID por FK  
**And** status, `storage_key` e bytes permanecem  
**And** o objeto READY segue acessível por URL assinada após a inicialização V2.

## MEM-002-AC11 — Lista inicial e estado vazio

**Given** que a pessoa abre `/meus-murais` e não tem murais  
**When** a primeira página carrega com sucesso vazia  
**Then** a página anuncia o estado vazio e oferece criação de mural  
**And** status não depende somente de cor.

## MEM-002-AC12 — Carregamento e erro inicial

**Given** que a primeira página está sendo carregada ou falhou  
**When** o estado é renderizado  
**Then** carregamento tem `aria-busy`/anúncio acessível  
**And** falha mostra orientação e ação de retry, sem fingir lista vazia.

## MEM-002-AC13 — Criar pela interface

**Given** a pessoa preenche labels “Nome do mural” e “Descrição (opcional)”  
**When** submete valores válidos  
**Then** o botão bloqueia envios simultâneos e envia uma chave de idempotência  
**And** `201` adiciona o mural no topo da lista e anuncia “Rascunho criado”  
**And** troca o filtro para Rascunhos e reinicia o cursor antes de exibir o novo mural  
**And** erro `422` associa a mensagem ao campo indicado sem apagar os valores.

## MEM-002-AC14 — Paginação da interface

**Given** uma lista com `nextCursor`  
**When** aciona o botão acessível “Carregar mais”  
**Then** a página preserva itens atuais, desabilita solicitações duplicadas e mantém foco  
**And** falha mantém itens e cursor, oferecendo retry da mesma página.

## MEM-002-AC15 — Filtrar status na interface

**Given** a lista de murais  
**When** seleciona Todos, Rascunhos, Publicados ou Arquivados  
**Then** a UI envia o filtro e reinicia a paginação  
**And** cada mural exibe seu status por texto (`Rascunho`, `Publicado` ou `Arquivado`)  
**And** o controle indica programaticamente a opção selecionada  
**And** estado vazio do filtro explica o resultado e permite voltar a Todos.

## MEM-002-AC16 — Sessão indisponível

**Given** que a API responde `401` fora do perfil local  
**When** a pessoa abre `/meus-murais`  
**Then** a interface mostra estado de acesso indisponível, não uma coleção vazia  
**And** não promete fluxo de login ainda fora do escopo.

## MEM-002-AC17 — Repetir criação depois de falha ambígua

**Given** uma falha de rede após o envio do formulário  
**When** a pessoa repete sem alterar título ou descrição  
**Then** a UI reutiliza os mesmos valores e `Idempotency-Key`  
**And** o backend retorna o mesmo mural sem duplicar  
**When** a pessoa altera qualquer valor normalizado  
**Then** a próxima tentativa recebe uma chave nova.

## MEM-002-AC18 — Provisionar a primeira identidade

**Given** um banco sem fotografias e uma identidade autenticada ainda sem registro interno  
**When** a API resolve essa identidade, inclusive sob requisições concorrentes  
**Then** todas as resoluções retornam o mesmo `UserId` UUID persistido  
**And** issuer e subject iguais formam um usuário, enquanto issuers diferentes permanecem identidades distintas  
**And** nenhum owner informado pelo cliente participa da resolução.
