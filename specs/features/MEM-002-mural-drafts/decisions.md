# MEM-002 — Decisões

## MEM-002-ADR-01 — Identidade interna e issuer

`UserId` é UUID aleatório; usuários identificam-se pelo par separado `(identity_issuer, identity_subject)` com constraint única. JWT validado fornece `iss` e `sub`; local usa `urn:memora:local` e `local-development-owner`. O issuer configurado é aplicado ao backfill de subjects V1 por placeholder Flyway. Trocar de provedor não mescla usuários automaticamente; exige fluxo futuro de vinculação.

## MEM-002-ADR-02 — Migração da propriedade das fotos

A V2 cria `users`, popula uma linha para cada subject distinto presente em `photo_assets.owner_id`, adiciona owner UUID temporário, migra todas as linhas e estados, aplica FK/NOT NULL, substitui a coluna antiga e recria índices. `storage_key`, metadados e bytes MinIO não mudam. A migração é exercitada em PostgreSQL real sem dados e com dados V1.

Não existe deployment de produção nesta fase, então o corte é atômico local. Antes de rolling deploy, será necessária migração expand/backfill/switch/contract.

## MEM-002-ADR-03 — Paginação e filtros

Lista ordenada por `created_at DESC, id DESC`; índice por `(owner_id, created_at DESC, id DESC)`. O cursor Base64URL codifica filtro (`ALL` ou status), timestamp persistido e UUID. Busca `limit + 1`, retorna `nextCursor` apenas quando existir outra página e rejeita cursor associado a filtro diferente.

## MEM-002-ADR-04 — Idempotência de criação

`POST /murals` exige UUID em `Idempotency-Key`. O mural guarda essa chave e hash SHA-256 do par de texto normalizado; unicidade é por owner. Repetição idêntica devolve o mesmo recurso; payload diferente resulta em conflito. Chaves persistem durante a vida do mural, sem tabela de limpeza separada.

## MEM-002-ADR-05 — Normalização e limites

Trim de Unicode White_Space antes de validar. Título: 1–100 code points; descrição: 0–500, e valor vazio vira `null`. O limite de descrição é inicial e pode mudar com evidência de uso antes da abertura pública.

## MEM-002-ADR-06 — Versão pública e JPA

Hibernate inicia o `@Version` em 0. A API publica `lockVersion + 1`, de modo que o primeiro mural seja versão 1 e o próximo update seja versão 2. Testes de persistência confirmam o mapeamento.

## MEM-002-ADR-07 — UI local sem autenticação de produção

`/meus-murais` consome a API real; no perfil `local` usa o principal local já documentado. `401` em outro perfil é um estado de acesso indisponível, não vazio. Login de navegador não é simulado nem incluído.

## MEM-002-ADR-08 — Provisionamento idempotente do usuário

O adaptador de identidade extrai issuer e subject do principal já validado e resolve ou cria o usuário interno sob a constraint única `(identity_issuer, identity_subject)`. Isso atende instalações novas sem depender de uma foto legada para criar o usuário local. `Authentication.name` isolado não identifica a conta porque pode colidir entre issuers. Resoluções concorrentes convergem para o mesmo `UserId`; o UUID permanece interno.
