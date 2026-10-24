# Instruções para agentes — Memora Backend

Leia `BACKEND_SPEC.md`, `../memora-frontend/BRAND_SYSTEM.md`, `../memora-frontend/FRONTEND_SPEC.md` e `SDD_TDD_PROTOCOL.md` por completo antes de iniciar feature. Esses documentos definem domínio, contratos, privacidade e stack.

## Modo de trabalho

1. Atualize spec, acceptance, contracts e decisões antes do código.
2. Escreva testes que falham pelo comportamento especificado antes da implementação.
3. Preserve dependências Clean Architecture: presentation/infrastructure → application → domain.
4. Use integração real para PostgreSQL e MinIO nos testes apropriados.
5. Nunca publique contribuições sem aprovação, registre tokens, URLs assinadas ou credenciais, ou crie integração falsa com Google Fotos.
6. Mantenha domínio independente de Spring, JPA, storage e APIs externas.

## Critérios de revisão

- Autorização resolvida no backend para cada recurso privado.
- Bucket e objetos privados; somente URLs assinadas temporárias após autorização.
- Erros HTTP e OpenAPI compatíveis com os contratos rastreáveis.
- Nenhuma regra de domínio em controller ou entidade JPA.
- Verificações e limitações não exercitadas declaradas como não verificadas.
