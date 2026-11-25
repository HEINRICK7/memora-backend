# MEM-010 — Acesso administrativo local

**Estado:** implementado para o ambiente local de teste. Não é autenticação de produção.

## Objetivo

Somente o administrador local configurado pode criar aniversários e operar os recursos privados do proprietário. O navegador autentica por sessão HTTP; o backend valida cada rota privada. A interface sozinha não define autorização.

## Requisitos

- **MEM-010-R01:** o perfil `local` inicia somente com `MEMORA_ADMIN_EMAIL` e `MEMORA_ADMIN_PASSWORD` configurados; não há usuário/senha padrão.
- **MEM-010-R02:** login compara o e-mail configurado sem diferenciar maiúsculas/minúsculas e valida senha com hash BCrypt em memória.
- **MEM-010-R03:** login cria sessão renovada; sessão é HttpOnly, SameSite Strict e expira após oito horas.
- **MEM-010-R04:** todas as rotas privadas do backend exigem sessão autenticada; compartilhamento público fica limitado às rotas `/api/v1/shared/**`.
- **MEM-010-R05:** login inválido retorna erro genérico sem informar se e-mail ou senha falhou; corpo de senha não é registrado.
- **MEM-010-R06:** mutações com sessão exigem token CSRF double-submit; rotas públicas de convidados não dependem da sessão do administrador.
- **MEM-010-R07:** identidade persistida deriva do e-mail autenticado, nunca de `ownerId` do cliente.
- **MEM-010-R08:** frontend usa origem relativa `/api/v1`; Next encaminha ao backend pela rede interna do Compose. O backend não precisa estar exposto à rede local.
- **MEM-010-R09:** credenciais locais ficam somente em `.env` ignorado pelo Git, com permissões restritas; nenhum segredo é embutido na imagem frontend.
- **MEM-010-R10:** a criação de aniversários usa as rotas privadas de mural. Não existe endpoint público para criar evento ou emitir QR.

## Fora de escopo

OIDC/Google, recuperação de senha, cadastro de outros administradores, MFA e autenticação de produção. O compartilhamento/contribuição continua dependendo dos endpoints backend MEM-008/MEM-009.
