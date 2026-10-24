# MEM-006 — Associar fotografias a um mural

**Estado:** aprovado pelo juiz para implementação incremental.\
**Escopo:** associar fotografias `READY` do proprietário a um mural em rascunho, consultar os vínculos em ordem, reordená-los e remover um vínculo sem apagar o ativo.\
**Fontes:** `BACKEND_SPEC.md` (casos de uso 6 e API de fotos), `../memora-frontend/FRONTEND_SPEC.md`, `../memora-frontend/BRAND_SYSTEM.md`, `../SDD_TDD_PROTOCOL.md`.

## Problema e usuário

Depois de criar um rascunho de mural e guardar fotografias no storage privado, o proprietário precisa escolher quais fotografias pertencem àquele mural e a ordem em que serão apresentadas. Essa relação é `MuralPhoto`; ela não transforma o mural em uma Composition e não copia nem publica o arquivo.

## Atores e pré-condições

- O ator é o proprietário resolvido no backend a partir do principal autenticado. No Compose, usa-se somente a identidade local de desenvolvimento definida por MEM-002.
- O mural existe, pertence ao ator e está em `DRAFT`.
- A fotografia pertence ao mesmo `UserId` e está em `READY`.
- Toda mutação recebe `If-Match` com a versão forte de `GET /api/v1/murals/{muralId}` ou com `muralVersion` lido de `GET /api/v1/murals/{muralId}/photos` formatado como ETag (`"{muralVersion}"`).

## Requisitos rastreáveis

- **MEM-006-R01:** o proprietário pode associar um ativo `READY` próprio a um mural próprio `DRAFT`; o cliente não envia `ownerId`.
- **MEM-006-R02:** um mesmo ativo pode pertencer a mais de um mural do proprietário, mas só pode ter um vínculo em cada mural. Repetir associação de vínculo existente não cria duplicata.
- **MEM-006-R03:** cada vínculo tem posição inteira, zero-based, única e contígua. Uma nova fotografia é acrescentada ao fim da ordem atual.
- **MEM-006-R04:** o proprietário pode consultar somente as fotografias associadas ao próprio mural. A resposta apresenta os vínculos na ordem persistida e uma URL temporária de leitura emitida após autorização; `muralVersion` informa a versão sem atribuir ETag forte a um corpo que contém URL assinada variável.
- **MEM-006-R05:** reordenação recebe uma permutação completa dos IDs atualmente associados. A operação é atômica e mantém posições contíguas. Uma lista vazia é válida quando não há vínculos.
- **MEM-006-R06:** remover um vínculo afeta apenas aquele `MuralPhoto`; não apaga o `PhotoAsset`, não remove o objeto do MinIO e não revoga outros vínculos do mesmo ativo.
- **MEM-006-R07:** associação, reordenação e remoção são permitidas somente quando o mural está em `DRAFT`. `PUBLISHED` e `ARCHIVED` respondem conflito sem alteração.
- **MEM-006-R08:** `If-Match` é obrigatório nas três mutações. Versão desatualizada ou colisão concorrente resulta em `409 stale_mural_version`, sem gravação parcial.
- **MEM-006-R09:** cada mudança efetiva no conjunto ou na ordem incrementa a versão pública do mural uma vez e atualiza `updatedAt` pelo relógio da aplicação. Repetir a associação existente ou enviar a ordem já persistida não incrementa versão nem timestamp.
- **MEM-006-R10:** qualquer leitura/mutação de mural ausente ou alheio retorna o mesmo `404`; foto ausente ou alheia também retorna o mesmo `404`. Erros não revelam owner, existência alheia, chave de storage ou dados privados.
- **MEM-006-R11:** ativo próprio que não esteja `READY` não pode ser associado e retorna `409 photo_not_ready`; nenhum estado de ativo é alterado.
- **MEM-006-R12:** respostas privadas incluem `Cache-Control: private, no-store`; URLs de leitura assinadas expiram em até cinco minutos, nunca são persistidas nem incluídas em logs/analytics.
- **MEM-006-R13:** se um ativo ainda estiver associado a qualquer mural, `DELETE /api/v1/photos/{photoId}` não o apaga: responde `409 photo_attached`. O proprietário deve primeiro remover cada vínculo pelo endpoint deste recurso.
- **MEM-006-R14:** a tabela de vínculos tem chave estrangeira para mural e ativo, unicidade `(mural_id, photo_id)` e unicidade `(mural_id, position) DEFERRABLE INITIALLY DEFERRED`, para permitir troca de posições dentro da transação sem colisões intermediárias. Constraints/migrations são verificadas em PostgreSQL real antes da implementação ser aprovada.
- **MEM-006-R15:** associação e exclusão de um mesmo `PhotoAsset` serializam pela linha do ativo e verificam estado/vínculos dentro da fronteira transacional; corrida nunca deixa vínculo para ativo `DELETED`, FK inconsistente ou objeto removido de ativo ainda vinculado.
- **MEM-006-R16:** reordenação grava todas as posições numa única transação PostgreSQL; a constraint de posição é validada no commit e rejeita qualquer estado final duplicado.
- **MEM-006-R17:** CORS permite `PUT` e `If-Match` para reordenar nos origins já configurados, sem ampliar a lista de origins; respostas continuam expondo `ETag` somente a esses origins.

## Estados e invariantes

- Ativo associado: `PhotoAsset.READY` → `MuralPhoto(muralId, photoId, position)`; o status do ativo permanece `READY`.
- Posições de cada mural formam exatamente `0..n-1`. Anexar acrescenta em `n`; remover compacta as posições; ordenar substitui todas as posições numa transação.
- `MuralPhoto` é uma relação ordenada, não um arquivo novo, cópia, capa ou Composition.
- Uma foto pode ser reutilizada em murais diferentes do mesmo proprietário. Nunca é associada a mural de outro proprietário.
- Todos os endpoints deste feature são privados; o contrato não cria visualização pública nem acesso por link.

## Concorrência e erros

- A versão retornada no `ETag` do mural também guarda a coleção de fotografias. Toda mutação verifica a versão e status dentro da fronteira transacional; conflito otimista no commit retorna `409`.
- Mutações sem mudança semântica preservam versão e `updatedAt`. Mesmo para uma operação sem mudança, `If-Match` precisa corresponder à versão atual.
- Associação e exclusão do mesmo ativo bloqueiam a linha `photo_assets` com estratégia compatível com PostgreSQL (`SELECT ... FOR UPDATE` ou equivalente) antes de avaliar READY/DELETED e inserir vínculo/verificar referências.
- Body ou UUID malformado e campos não declarados: `400`. `If-Match` ausente: `428`; malformado: `400`.
- Mural inexistente/alheio, ou fotografia inexistente/alheia: `404` genérico e indistinguível.
- Versão obsoleta ou mural fora de `DRAFT`: `409` (`stale_mural_version` ou `mural_not_editable`). Ativo próprio não `READY`: `409 photo_not_ready`.
- Ordem com IDs duplicados ou conjunto diferente dos vínculos atuais: `422 invalid_photo_order`; nenhuma posição é modificada.
- Falha ao emitir URL temporária ou indisponibilidade de persistência: `503` seguro, sem expor endpoint/credencial/URL assinada.

## Privacidade e segurança

- O servidor deriva o `UserId`; `ownerId` e caminho de storage não são aceitos do cliente.
- Confere ownership do mural antes de operar nos ativos, e ownership do ativo antes de criar relação. UUID não é credencial.
- URL assinada só é criada depois de autenticar e autorizar leitura do mural; tem TTL limitado e só aparece no corpo autorizado de `GET /murals/{id}/photos`.
- Logs, erros e corpos de mutação não incluem nome de arquivo, issuer/subject, `ownerId`, storage key, bytes da foto, URL assinada ou segredos S3. A URL assinada só aparece no corpo da leitura GET autorizada.
- Remoção de vínculo nunca apaga ou torna público o objeto; a exclusão do ativo permanece sujeita à autorização do endpoint de fotos e à ausência de todos os vínculos.
- Não retornar `403` em lugar do `404` para mural/foto inexistente ou pertencente a outro usuário.

## Fora de escopo

Salvar `Composition`, escolher capa, upload/confirmar foto, publicação/arquivamento, links compartilhados, contribuição/moderação, exclusão definitiva/retention policy, conversão/derivados, paginação da coleção, limite/quota de fotos por mural e autenticação de produção.

## Dependências

- MEM-001 para estados de upload, validação real e storage MinIO privado.
- MEM-002 para identidade, propriedade, mural persistido e paginação/consulta.
- MEM-004 para `ETag`, `If-Match`, versão e conflito otimista.
- A experiência local de Composition MEM-005 identifica ativos prontos por UUID, mas ainda não prova persistência de composição nem fluxo E2E de vínculo.

## Critérios observáveis

1. Proprietário associa um ativo `READY` próprio ao seu mural `DRAFT`, e uma leitura retorna o vínculo em posição zero-based com URL temporária.
2. A lista mantém ordem estável, usa `Cache-Control: private, no-store`, não expõe chave/owner e não é acessível por outro principal.
3. Reordenação válida substitui toda a ordem em uma transação; IDs ausentes, desconhecidos ou duplicados não mudam nenhum vínculo.
4. Desassociar uma foto preserva ativo, bytes MinIO e associações em outros murais.
5. Alteração efetiva atualiza ETag/versão uma vez; versão obsoleta e corrida concorrente respondem conflito e preservam estado consistente.
6. Upload `UPLOADING`, `FAILED` ou `DELETED`, foto de outro owner, mural de outro owner e status incompatível seguem os erros especificados sem vazamento.
7. Excluir ativo ainda associado não remove bytes nem metadados; depois da remoção de todos os vínculos a exclusão permanece disponível pelo endpoint de fotos existente.

## Limites e decisões futuras

- MEM-001 foi atualizado e aprovado conjuntamente para registrar a pré-condição `photo_attached` e a serialização do DELETE.
- Quota/limite máximo de vínculos por mural continua pendente da ADR de custo definida em `BACKEND_SPEC.md`; esta feature não inventa um número.
- A primeira versão bloqueia edição após publicação. Uma futura política de despublicação/edição deve criar contrato próprio e definir o que acontece com links vigentes.
