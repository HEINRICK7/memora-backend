# Memora — Protocolo SDD + TDD + Clean Architecture

**Versão:** 1.0 · Normativo para todos os agentes.  
**SDD:** Spec-Driven Development. **TDD:** Test-Driven Development.

## 1. Regra-mãe

Nenhuma feature relevante começa pela UI ou controller. Primeiro modelamos comportamentos em uma spec aprovada e rastreável; os testes nascem da spec; os adapters, endpoints e componentes só aparecem depois que a regra estiver protegida.

**Ordem:** SPEC → ACCEPTANCE → TEST RED → DOMAIN GREEN → REFACTOR → APPLICATION TEST RED/GREEN → INFRA INTEGRATION → PRESENTATION CONTRACT → E2E → REVIEW.

## 2. Artefatos mínimos por feature

```text
specs/features/<feature-id>-<slug>/
  spec.md         # problema, escopo, regras e cenários
  acceptance.md   # casos observáveis Given/When/Then
  contracts.md    # DTOs, eventos, HTTP quando aplicável
  decisions.md    # trade-offs e ADRs locais
```

O `spec.md` precisa ter: identificador estável, motivação/usuário, ações, pré-condições, invariantes, estados, erros, privacidade, dependências, casos de sucesso, fora de escopo, critérios observáveis e decisões abertas. Nunca marcar uma spec como pronta antes de resolver bloqueios que alterem comportamento.

## 3. Traceability

| Artefato | Exemplo |
|---|---|
| Feature ID | `MEM-004` |
| Requirement | `MEM-004-R03`: token VIEW não aceita contribuição |
| Acceptance | `MEM-004-AC03`: tentativa de envio retorna erro seguro |
| Teste de domínio | `ShareGrantPolicyTest.viewCannotContribute()` |
| Use case | `SubmitContribution` |
| HTTP | `POST /api/v1/shared/{token}/contributions` |
| E2E | `mural-view-link-rejects-upload.spec.ts` |

IDs fazem vínculo explícito entre comportamento, teste e entrega. Não testar estrutura interna por estética.

## 4. Exemplo de especificação comportamental

### MEM-004 — Link de visualização

**Problema:** proprietário quer compartilhar um mural sem autorizar modificações.  
**Usuário:** proprietário e convidado.  
**Pré-condições:** mural publicado e proprietário autorizado.  
**Regra:** `VIEW` permite leitura de fotos aprovadas, nunca upload, edição ou moderação.  
**Privacidade:** token aleatório e revogável; acesso não revela fotos de outros murais.

```gherkin
Feature: Compartilhar mural somente para visualização
  Scenario: Convidado visualiza um mural por link válido
    Given que um mural publicado possui um link VIEW ativo
    When o convidado abre o link
    Then vê apenas fotos aprovadas do mural
    And não encontra ações de edição ou contribuição

  Scenario: Link revogado
    Given que o link VIEW foi revogado
    When alguém tenta abrir o link
    Then nenhuma foto privada é retornada
    And a interface informa que o link não está disponível

  Scenario: Upload não autorizado
    Given que o convidado possui somente um link VIEW
    When tenta enviar uma fotografia
    Then a aplicação rejeita a operação
    And o mural permanece inalterado
```

## 5. Trabalho simultâneo frontend/backend

1. Especificar feature e invariantes compartilhadas, com casos de aceite.
2. Aprovar contrato OpenAPI e exemplo de payload; isso não exige backend pronto.
3. Backend cria testes de domínio/aplicação e implementa use case isolado.
4. Frontend cria testes de presenter/viewmodel e UI com fixture local declarada **somente para desenvolvimento de interface**, sem fingir integração concluída.
5. Backend integra infraestrutura e prova contrato com testes.
6. Frontend substitui fixture pelo adapter HTTP real e roda E2E.
7. Registrar evidências de testes e diferenças contra a spec.

Fixture visual nunca vale como validação de API/infra real. Mocks/fakes são permitidos em testes de isolamento quando representam contrato declarado; não inventar resultados operacionais.

## 6. Clean Architecture e SOLID sem cerimônia

```text
presentation/  -> application/ -> domain/
infrastructure/ -> application/ -> domain/
```

- **Domain:** política e invariantes puras, sem bibliotecas de UI, HTTP, ORM ou storage.
- **Application:** casos de uso e portas pequenas, sem lógica de framework.
- **Infrastructure:** implementações de repositório, APIs, render, OAuth e storage.
- **Presentation:** controllers finos, componentes e hooks que apenas adaptam entrada/saída e estado.
- DIP para dependências externas reais; não criar abstrações vazias para funções triviais.
- Componentes React não decidem visibilidade/autorização no lugar do backend; apenas refletem capacidades retornadas.

## 7. Gates obrigatórios

| Gate | Bloqueia avanço quando... |
|---|---|
| Spec Review | objetivos, estados, erros ou privacidade ambíguos |
| RED | não existe teste falhando pelo comportamento esperado |
| GREEN | teste ainda falha ou foi alterado para esconder problema |
| Refactor | regras vazam para UI/infra ou dependência concreta invade domínio |
| Contract | frontend/backend divergentes ou schema não versionado |
| Integration | testes usam fake como substituto da integração crítica |
| E2E | fluxo essencial não foi exercitado em navegador/dispositivo alvo |
| Release | ausência de evidência, migração/rollback ou avaliação de segurança |

## 8. Test matrix inicial

| Domínio | Domain unit | Application | Infra | E2E |
|---|---|---|---|---|
| Templates/Composição | slots, transforms | salvar/exportar | renderer | criar/exportar |
| Murais | status, ownership | criar/publicar | PostgreSQL | publicar e reabrir |
| Sharing | papel/expiração | grant/revoke | token hash | convidado abre e revogação |
| Fotos | status/ownership | upload/attach | S3/MIME | foto aparece só quando READY |
| Contribuições | transições | enviar/aprovar | DB/storage | convidado → aprovação |
| Google Fotos | seleção autorizada | import | OAuth/Picker | somente após integração real |

## 9. Revisão de implementação

- Mudança de requisito altera primeiro a spec, depois testes, depois código.
- Alteração incompatível de API exige atualização de contrato/versionamento e migração.
- Falha conhecida e caso não verificado ficam explícitos no relatório de feature.
- Nenhum agente declara funcionalidade pronta apenas porque a página renderiza.
- Implementar o menor comportamento que satisfaz o teste; abstrair apenas quando protege domínio, integra API externa, melhora testes ou reduz custo de mudança.

