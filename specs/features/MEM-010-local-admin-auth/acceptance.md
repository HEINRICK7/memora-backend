# MEM-010 — Critérios de aceite

## MEM-010-AC01 — Bloquear operação sem sessão

**Given** um cliente sem sessão válida  
**When** chama qualquer rota privada de mural ou foto  
**Then** recebe `401 unauthenticated` e nenhum dado é lido ou alterado.

## MEM-010-AC02 — Entrar como único administrador local

**Given** credenciais iguais às variáveis locais configuradas  
**When** o administrador solicita login com CSRF válido  
**Then** recebe uma sessão nova e a resposta contém somente o e-mail autenticado.

## MEM-010-AC03 — Recusar credenciais inválidas

**Given** e-mail ou senha incorretos  
**When** o cliente solicita login  
**Then** recebe a mesma resposta genérica `401` e nenhuma sessão autenticada é emitida.

## MEM-010-AC04 — Manter escrita protegida por CSRF

**Given** sessão autenticada  
**When** uma mutação privada omite ou apresenta token CSRF inválido  
**Then** recebe `403` e o recurso permanece inalterado.

## MEM-010-AC05 — Preservar acesso público por link

**Given** um convidado sem sessão administrativa  
**When** consulta ou envia dados pelas rotas públicas `/api/v1/shared/**`  
**Then** a autorização depende somente do grant/token e das regras da feature de compartilhamento.

## MEM-010-AC06 — Logout

**Given** sessão administrativa válida  
**When** o administrador encerra a sessão com CSRF válido  
**Then** o identificador da sessão é invalidado e chamadas privadas passam a retornar `401`.

## MEM-010-AC07 — Configuração ausente

**Given** perfil local sem e-mail ou senha configurados  
**When** o backend inicia  
**Then** falha fechado com erro de configuração que não inclui valores secretos.
