# MEM-001 — Critérios de aceite

## MEM-001-AC01 — Seleção não envia imagens

**Given** que a pessoa abre o seletor local e escolhe JPEG e PNG válidos  
**When** a interface apresenta as prévias  
**Then** as imagens permanecem no navegador  
**And** não existe chamada a /api/v1/uploads nem ao MinIO antes do comando Guardar fotografias.

## MEM-001-AC02 — Guardar envia via URL assinada

**Given** uma ou mais imagens válidas selecionadas  
**When** a pessoa confirma Guardar fotografias  
**Then** o frontend cria uma intenção na API para cada imagem  
**And** envia os bytes por PUT ao URL assinado  
**And** confirma a conclusão com a API  
**And** mostra somente ativos em estado READY.

## MEM-001-AC03 — Arquivo recusado mantém seleção recuperável

**Given** um arquivo vazio, maior que 20 MiB, ou que não seja JPEG/PNG válido  
**When** a pessoa tenta guardá-lo  
**Then** a API recusa o arquivo sem torná-lo visível na galeria  
**And** a interface informa o motivo  
**And** as outras imagens selecionadas permanecem disponíveis.

## MEM-001-AC04 — Conteúdo real é verificado

**Given** uma intenção criada para PNG  
**When** bytes incompatíveis com PNG ou com tamanho diferente são enviados ao storage  
**And** a conclusão é solicitada  
**Then** o ativo não chega a READY  
**And** a API retorna erro seguro e remove o objeto inválido quando possível.

## MEM-001-AC05 — Objeto permanece privado

**Given** um ativo READY  
**When** uma requisição anônima tenta obter sua chave diretamente no MinIO  
**Then** o MinIO não entrega o objeto  
**When** o proprietário busca a galeria  
**Then** recebe um URL de visualização temporário para esse ativo.

## MEM-001-AC06 — Falha de infraestrutura não perde a seleção

**Given** imagens pré-visualizadas localmente  
**When** API ou MinIO fica indisponível durante o salvamento  
**Then** a interface mantém as prévias e oferece nova tentativa  
**And** não relata sucesso sem confirmação READY.

## MEM-001-AC07 — Persistência local pelo Compose

**Given** a aplicação foi iniciada pelo Compose e há um ativo READY  
**When** os containers são reiniciados sem remover volumes  
**Then** a API continua listando o ativo  
**And** seu objeto continua acessível por URL assinada válida.

## MEM-001-AC08 — Superfície local limitada

**Given** o Compose iniciado  
**Then** API, web e portas do MinIO estão publicadas apenas em loopback  
**And** PostgreSQL só é acessível na rede interna do Compose.
