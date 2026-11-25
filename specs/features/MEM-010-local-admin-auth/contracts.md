# MEM-010 — Contratos

Todas as rotas abaixo existem somente no perfil local. O backend permanece em porta loopback; o frontend acessa pela rota relativa `/api/v1` e proxy interno do Next.

## `GET /api/v1/auth/csrf`

Retorna `200 {"token":"..."}` e define cookie `XSRF-TOKEN` necessário antes do login e das mutações.

## `POST /api/v1/auth/login`

Headers: `Content-Type: application/json`, `X-XSRF-TOKEN: <valor do cookie>`.

```json
{"email":"admin@example.com","password":"..."}
```

Sucesso: `200 {"email":"admin@example.com"}` e sessão HttpOnly renovada. Falha: `401` Problem Details genérico (`invalid_credentials`). Não incluir credenciais em logs, erros ou respostas.

## `GET /api/v1/auth/session`

Exige sessão. Retorna `200 {"email":"admin@example.com"}` ou `401 unauthenticated`.

## `POST /api/v1/auth/logout`

Exige sessão e `X-XSRF-TOKEN`; invalida a sessão e retorna `204`.

## Política de acesso

- `/actuator/health/**`, `GET /api/v1/auth/csrf`, `POST /api/v1/auth/login` e `/api/v1/shared/**` são públicos.
- `/api/v1/auth/session`, `/api/v1/auth/logout` e todas as demais rotas exigem usuário com `ROLE_ADMIN`.
- Toda mutação autenticada exige CSRF. Mutação pública sob `/api/v1/shared/**` usa token de compartilhamento e é isenta da sessão/CSRF administrativo.
- A identidade local é `issuer=urn:memora:local`, `subject=<e-mail configurado normalizado>`.
- Criação de mural/aniversário é feita por `POST /api/v1/murals`, protegido por `ROLE_ADMIN`; `POST /api/v1/public/murals` não é um contrato disponível. Convidados só usam `/api/v1/shared/**` com token emitido para a festa.
