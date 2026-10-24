# MEM-006 — Evidências de implementação

**Branch:** `feature/mem-006-mural-photo-association`  
**Status:** implementação e validação backend concluídas; aguardando revisão do juiz.

## Rastreabilidade

| Acceptance | Evidência automatizada |
|---|---|
| AC01–AC05, AC08–AC14, AC17–AC19 | `MemoraMuralPhotoAssociationComposeIntegrationTest` |
| AC06, AC08–AC11 | `MuralPhotoCollectionTest` |
| AC01, AC08, AC11 e AC13 | testes de aplicação `AttachPhotoToMuralTest`, `ReorderMuralPhotosTest`, `RemovePhotoFromMuralTest` |
| AC02–AC03, AC12 | `ListMuralPhotosTest`, `DeletePhotoAssociationTest` e integração Compose |
| AC15 | `MuralPhotoPresignFailureTest#AC15_mapsSecondPhotoPresignFailureToSanitized503WithoutPartialCollection` injeta erro no segundo presign via MockMvc e confirma 503 sem URL pré-assinada, credencial ou storage key no corpo |
| AC16 POST | `MemoraMuralPhotoAssociationComposeIntegrationTest#AC16_postDatabaseFailureRollsBackLinksVersionAndUpdatedAt` injeta erro transacional por trigger PostgreSQL e confere rollback |
| AC16 PUT | `MemoraMuralPhotoAssociationComposeIntegrationTest#AC16_putDatabaseFailureRollsBackPositionsVersionAndUpdatedAt` injeta erro transacional por trigger PostgreSQL e confere rollback |
| AC16 DELETE | `MemoraMuralPhotoAssociationComposeIntegrationTest#AC16_deleteDatabaseFailureRollsBackLinksPositionsVersionAndUpdatedAt` injeta erro transacional por trigger PostgreSQL e confere rollback |

### Rastreabilidade R–AC das falhas

| Requisito | Acceptance | Verificação automatizada |
|---|---|---|
| MEM-006-R04, MEM-006-R12 | MEM-006-AC15 | MockMvc: segundo `PhotoStorage.presignGet` falha; resposta `503 dependency_unavailable` é sanitizada e não contém coleção parcial nem URL já gerada |
| MEM-006-R08, MEM-006-R09, MEM-006-R16 | MEM-006-AC16 | PostgreSQL real no Compose: triggers de teste falham INSERT (POST), UPDATE (PUT) e DELETE; cada pedido responde 503 e preserva vínculos/ordem, `lock_version` e `updated_at` |

## Execuções

| Fase | Comando/ambiente | Resultado |
|---|---|---|
| RED HTTP | Compose, backend sem rotas MEM-006; `MemoraMuralPhotoAssociationComposeIntegrationTest` | Falhou como esperado: `POST /api/v1/murals/{id}/photos` respondeu `404 No static resource ...` |
| RED domínio/aplicação | `docker compose run --build --rm backend-tests mvn -B -DskipTests test-compile` antes da implementação | Falhou como esperado: símbolos de domínio/portas/use cases MEM-006 ausentes |
| GREEN compile | `docker compose run --build --rm backend-tests mvn -B -DskipTests test-compile` após implementação | Passou para código principal e testes |
| PostgreSQL/Migration | serviço Compose `backend` conectado ao PostgreSQL 17.11 | Flyway validou V1–V3 e aplicou `V3__create_mural_photos.sql`; startup JPA concluído |
| Suíte unitária | `docker compose run --build --rm backend-tests mvn -B test` | 80 testes, 0 falhas/erros; 40 testes de integração ficaram skip por flags desativadas. Inclui AC15. |
| Integração MEM-006 | PostgreSQL 17.11, backend e MinIO reais no Compose; `docker compose -f compose.yaml -f /tmp/memora-mem006-compose.override.yaml run --build --rm -e MEMORA_MURAL_PHOTO_INTEGRATION_TESTS=true backend-tests mvn -B -Dtest=MemoraMuralPhotoAssociationComposeIntegrationTest test` | 11 testes, 0 falhas/erros/skips. Inclui AC16 para falha transacional em POST/PUT/DELETE, GET assinado/CORS MinIO, isolamento de owner, swap deferred e corrida attach/delete. |
| Cleanup das fixtures e do trigger | Consulta somente leitura no PostgreSQL após a integração | `0` murals de teste, `0` owners estrangeiros, `0` fotos estrangeiras; trigger e função de falha ausentes; migration V3 aplicada (`1`). |
| Restauração do backend local | `docker compose up -d --force-recreate backend`; readiness e ambiente do container | Backend saudável (`/actuator/health/readiness` = `UP`); `MINIO_ENDPOINT_PUBLIC=http://localhost:9000` restaurado. |

## Limites conhecidos

- O perfil Compose `local` mapeia todos os pedidos à identidade fixa de desenvolvimento. A integração comprova isolamento de proprietário usando fixtures de outro owner no PostgreSQL; não representa validação de JWT de produção.
- O teste assinado do MinIO precisa executar dentro da rede Compose com `MINIO_ENDPOINT_PUBLIC=http://minio:9000`, pois a configuração local padrão `localhost:9000` é destinada ao navegador no host.
- O trigger AC16 existe apenas durante cada teste; `finally` e `@AfterEach` o removem. A execução confirmou ausência do trigger e função após a limpeza.
