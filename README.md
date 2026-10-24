# Memora

Memora é um estúdio de lembranças com uma interface Next.js, uma API Spring Boot, PostgreSQL e armazenamento privado MinIO. O workspace usa dois repositórios irmãos: `memora-backend` e `memora-frontend`.

## Subir a stack local

Clone os dois repositórios na mesma pasta, copie o arquivo de ambiente de desenvolvimento e inicie os serviços com Docker Compose:

```sh
git clone --branch feature/mem-004-update-mural-metadata https://github.com/HEINRICK7/memora-backend.git memora-backend
git clone --branch feature/mem-005-local-composition-editor https://github.com/HEINRICK7/memora-frontend.git memora-frontend
cd memora-backend
cp .env.example .env
docker compose up --build
```

O Compose mantém PostgreSQL e MinIO em volumes Docker. As portas publicadas escutam somente em `127.0.0.1`.

| Serviço | Endereço local |
|---|---|
| Frontend | `http://localhost:3300` |
| API | `http://localhost:8080` |
| Console MinIO | `http://localhost:9001` |

Os valores do `.env.example` são exclusivos para desenvolvimento local. Não os use em produção nem envie um arquivo `.env` ao GitHub.

## Backend

API Spring Boot 3 / Java 21. PostgreSQL guarda owners, estado e metadados; MinIO mantém objetos em bucket privado. O browser envia bytes por uma URL S3 temporária e a API valida tamanho e conteúdo antes de marcar uma foto como `READY`.

O perfil `local` usa somente o owner fixo `local-development-owner` e não representa autenticação de produção. Fora dele, a API exige tokens JWT assinados por `OIDC_ISSUER_URI` e usa o subject autenticado como owner. Não há suporte para header arbitrário de identidade.

`/actuator/health/readiness` verifica o processo, PostgreSQL e bucket MinIO. As URLs assinadas não são persistidas nem registradas pela aplicação.

### Contratos HTTP atuais

- `POST /api/v1/uploads`, `PUT {uploadUrl}` e `POST /api/v1/uploads/{uploadId}/complete` controlam upload privado.
- `GET /api/v1/photos` lista somente fotos `READY` do owner atual; `DELETE /api/v1/photos/{photoId}` solicita a remoção.
- `POST /api/v1/murals` cria mural em rascunho; `GET /api/v1/murals` lista somente os murais do owner.
- `GET /api/v1/murals/{muralId}` e `PATCH /api/v1/murals/{muralId}` retornam `ETag`; o PATCH exige `If-Match` e só altera metadados de um mural em rascunho.

Os contratos OpenAPI versionados estão em `src/main/resources/static/openapi/`. Erros REST usam `application/problem+json` com código estável e `traceId`.

## Verificações

Testes unitários e de domínio em container Maven:

```sh
docker compose run --rm backend-tests
```

Com a stack ativa, a integração de metadados do mural usa a API e o PostgreSQL reais:

```sh
MEMORA_MURAL_METADATA_INTEGRATION_TESTS=true \
docker compose run --rm backend-tests
```

Os testes de upload que verificam URLs assinadas do MinIO precisam executar em um host que alcance `localhost:9000`; rode-os do workspace local com `MEMORA_INTEGRATION_TESTS=true` e a stack ativa.

As features e contratos versionados estão em `specs/features/` e `src/main/resources/static/openapi/`. Antes de iniciar uma feature, leia `AGENTS.md`, `BACKEND_SPEC.md` e `SDD_TDD_PROTOCOL.md`.
