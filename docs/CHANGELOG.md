# Changelog — Nexora Semantic Engine

Historial cronológico (de más antiguo a más reciente). Los bloques 1–7 se realizaron el **19 oct 2026** y los 8–11 el **20 oct 2026**, sobre
la versión de artefacto `0.1.0-SNAPSHOT`. Los resultados de pruebas son los de `mvn clean verify` ejecutado al
cerrar cada bloque. Estado actual: [PROJECT_STATUS.md](PROJECT_STATUS.md).

---

## 1. S0 — Diseño arquitectónico

- Informe de arquitectura: módulos, paquetes, modelo de dominio, esquema PostgreSQL, estados, API REST,
  integración con Discovery y Qwen, seguridad, riesgos y plan S1–S5.
- Contrato de Discovery basado en el repositorio local (`Nexora Discovery` 0.1.0-SNAPSHOT, `API.md`), con un
  sobre de importación marcado como **provisional**.
- Aprobado con 10 ajustes: grano y fan-out sin excepciones, reproducibilidad, vuelta a DRAFT al editar en
  revisión, OIDC configurable, separación autor/aprobador, `JdbcClient` + Flyway, bloqueo de mezcla de monedas.

## 2. S1 — Implementación inicial

### Añadido
- Proyecto Maven, Java 21, **Spring Boot 3.5.16** (la última 3.x publicada en ese momento).
- Arquitectura hexagonal: `domain` (Java puro), `application` (solo `spring-tx`), `infrastructure`, `api`, con
  9 reglas ArchUnit.
- Migraciones **V1** `tenant`, **V2** `data_source`, **V3** `audit_event` (append-only con grants y triggers);
  rol propietario para Flyway separado del rol DML de la aplicación.
- OAuth2 Resource Server con JWT; permisos por rol; restricción de principales AI y SERVICE.
- Aislamiento multiempresa: tenant del token, repositorios filtrados por `tenant_id`, 404 entre tenants.
- Endpoints `/api/v1/admin/tenants`, `/api/v1/data-sources`, `/api/v1/audit-events`; ETag/If-Match (428/412).
- Errores RFC 7807 con `code` y `correlationId`; propiedades JSON desconocidas rechazadas.
- Entorno de desarrollo: PostgreSQL 17.6 y Keycloak 26.4.0 (solo desarrollo).
- `docs/ARCHITECTURE.md` y `README.md`.

### Verificación
- **169 pruebas, 0 fallos.** Verificación manual contra Keycloak; corregido el realm de desarrollo (faltaba el
  mapper `oidc-sub-mapper`, porque en Keycloak 25+ `sub` lo aporta el scope `basic`).

## 3. Auditoría técnica de S1 (solo informe)

- Hallazgos bloqueantes B1–B2, importantes I1–I8 y opcionales O1–O16.
- Verificado el soporte de Spring Boot: 3.5.16 es la última versión OSS de 3.5.x (soporte OSS hasta el
  30-jun-2026). Alternativas evaluadas para D10.
- Decisiones aprobadas a raíz de la auditoría: D1 (importación del JSON de Discovery), D2 (UUID de tenant en el
  JWT, `principal_type` obligatorio) y D10 (migrar a 4.1.1 en una tarea separada).

## 4. Correcciones de seguridad B1, B2, I1, I2, I4, I5, I6

### Cambiado
- **B1:** el claim `tenant` pasa a ser el **UUID** del tenant (canónico); malformado ⇒ 401; ausente ⇒ 401
  salvo `PLATFORM_ADMIN`. Flujo: primero se registra el tenant y después se configura en el proveedor OIDC.
- **B2:** `principal_type` obligatorio y exacto (`HUMAN`, `SERVICE` o `AI`); ya no se asume `SERVICE`.
- **I2:** el `X-Request-Id` lo genera siempre el servidor; el del cliente se ignora.
- **I4:** `issuer-uri` obligatorio; validador de emisor aplicado a cualquier decodificador.

### Añadido
- **I1:** migración **V4** `protect_immutable_columns` (`UPDATE` por columna y triggers; un `data_source` nunca
  cambia de tenant).
- **I5:** prueba de atomicidad (si falla la auditoría, la operación se revierte).
- **I6:** proveedor OIDC local de prueba (discovery + JWKS), para validar los tokens como en producción.
- Realm de desarrollo con UUID de tenant por variables de entorno.

### Verificación
- **223 pruebas, 0 fallos.** Verificación manual con Keycloak (configuración del UUID en el proveedor,
  aislamiento, `X-Request-Id` falsificado ignorado). Tras un reinicio de la extensión de VS Code se comprobó en
  disco que todas las correcciones seguían aplicadas; 223 pruebas en verde.

## 5. I3 — Separación de migraciones de producción

### Añadido
- Job independiente `SchemaMigrationMain` / `SchemaMigrator` en el mismo JAR (`PropertiesLauncher`), sin
  contexto Spring.
  - Configuración por variables de entorno, contraseña preferentemente por fichero.
  - Comandos `migrate` y `validate`; códigos de salida 0, 1 y 2.
- Perfil `prod` (`application-prod.yml`): `spring.flyway.enabled=false` y
  `nexora.semantic.database.enforce-least-privilege=true`.
- `LeastPrivilegeConfiguration` + `LeastPrivilegeVerifier`: la aplicación no arranca si Flyway está activo, si
  hay credenciales de propietario o si su rol puede alterar la estructura.
- `deploy/postgres/create-roles.sql` (identidades de producción, sin contraseñas).
- Decisión **D15**; procedimiento de despliegue en el README.

### Cambiado
- Regla ArchUnit `noCredentialFields`: admite constantes `static final` con *nombres* de variables de entorno;
  sigue prohibiendo credenciales como estado.

### Verificación
- **278 pruebas, 0 fallos** (55 nuevas). Sin cambios en V1–V4 ni migraciones nuevas.
- Verificación manual sobre un PostgreSQL limpio:
  - `create-roles.sql` con psql;
  - job `migrate` y `validate` desde el JAR;
  - aplicación `prod` sin Flyway;
  - arranque rechazado con la identidad migradora y con credenciales de propietario.

## 6. D10 — Migración a Spring Boot 4.1.1

Basada en la guía oficial de migración a Spring Boot 4.0 y en las notas de 4.1. Java 21 se mantiene.

### Cambiado
- **Plataforma:** Spring Boot 3.5.16 → **4.1.1**; Spring Framework 7.0.9; Spring Security 7.1.1; Jackson 3.1.5;
  Flyway 12.4.0; Testcontainers 2.0.5; JUnit 6.0.3; Mockito 5.23.0; driver PostgreSQL 42.7.13.
- **Starters modulares:** `-webmvc`, `-security-oauth2-resource-server`, `-flyway`; pruebas con
  `-webmvc-test` y `-security-oauth2-resource-server-test`.
- **Jackson 3:**
  - paquetes `com.fasterxml.jackson.*` → `tools.jackson.*`;
  - `JsonProcessingException` → `JacksonException`;
  - `JsonNode.asText()` → `asString()` en las pruebas.
- **Paquetes movidos en Boot 4:** `OAuth2ResourceServerProperties`, `FlywayMigrationInitializer`,
  `@AutoConfigureMockMvc`.
- **Testcontainers 2:** `org.testcontainers.postgresql.PostgreSQLContainer`.
- **API obsoleta de Spring Framework 7:**
  - `org.springframework.lang.@NonNull/@Nullable` → JSpecify;
  - `HttpStatus.PAYLOAD_TOO_LARGE` → `CONTENT_TOO_LARGE`.

### Corregido
- **Configuración ignorada en silencio:** Boot 4 ya no reconoce `server.error.*`. Se renombró a
  `spring.web.error.*`. Los valores efectivos coincidían con los predeterminados de Boot 4, así que no hubo
  exposición de información.
- **Contrato RFC 7807:** Spring Framework 7 no inicializa `ProblemDetail.type` y el campo `"type":"about:blank"`
  desaparecía de las respuestas. `Problems` lo fija explícitamente; se añadieron aserciones de regresión.

### Verificación
- **278 pruebas, 0 fallos.** `spring-boot-properties-migrator` usado temporalmente: informe limpio tras las
  correcciones, y luego retirado.
- **JAR 4.1.1** (`PropertiesLauncher` presente): job `migrate`/`validate` real sobre un PostgreSQL limpio con
  `create-roles.sql`; 4 migraciones instaladas solo por el migrador.
- **Perfil `prod`:** sin Flyway, conectado solo como `nexora_semantic_app`; rechaza el arranque con la identidad
  migradora, con `SPRING_FLYWAY_ENABLED=true` y con `SEMANTIC_DB_OWNER_PASSWORD`.
- **Perfil `dev` contra Keycloak 26.4:** Flyway al arrancar; alta de tenants; 201, 403, 400, 404, 428, 412 y
  auditoría con el mismo comportamiento que antes.
- **Diferencia no contractual:** Jackson 3 ordena alfabéticamente las propiedades JSON.

### Documentación
- `ARCHITECTURE.md` v1.3 (D10 implementada; D15 verificada con 4.1.1; R12 mitigado; R13 nuevo).
- Nuevos `PROJECT_STATUS.md` y `CHANGELOG.md`; README actualizado.

## 7. S2 Basic — Importación persistente de metadatos de Discovery

Precedido de la aceptación de S1. Discovery se consultó en modo de solo lectura: código, DTO (`DiscoveryResponse`,
`ColumnMetadata`, `RelationshipMetadata`) y `API.md`; el contrato coincide con el observado en S0.

### Añadido
- **Respuestas reales de Discovery como fixtures** (`src/test/resources/discovery`):
  - se capturaron ejecutando, sin compilar ni modificar su repositorio, el JAR existente de Discovery
    0.1.0-SNAPSHOT contra un PostgreSQL 17.6 desechable inicializado con sus propios scripts;
  - cuatro capturas: todos los esquemas, `sales`, `sales`+`restricted` y una segunda versión del esquema;
  - ninguna contiene credenciales ni host.
- **Dominio:** `NaturalKey` (identificadores entre comillas, exacta y sensible a mayúsculas),
  `DiscoveredDatabase`, `PhysicalObject`, `ObjectAttributes` (huella SHA-256), `MetadataSnapshot` y
  `MetadataReconciler` (conciliación pura por alcance: ACTIVE / MISSING / UNAVAILABLE, sin borrados).
- **Aplicación:** `MetadataImportService` (una transacción, fila de la fuente bloqueada, auditoría
  `METADATA_SNAPSHOT_IMPORTED`) y `PhysicalMetadataQueryService`.
- **Capa anticorrupción (I7):** `JacksonDiscoveredMetadataReader` + `DiscoveryPayloadV01`.
  - mapper propio;
  - sobre `discovery-api/0.1` estricto y payload tolerante;
  - validación estructural con rutas JSON y sin valores.
- **Persistencia:** migración **V5** `create_physical_metadata` (`metadata_snapshot`, `physical_object`).
  `JdbcMetadataSnapshotRepository`, `JdbcPhysicalObjectRepository` (lotes) y `DataSourceRepository.lockById`.
- **API:**
  - `POST /api/v1/data-sources/{id}/snapshots` (lectura acotada del cuerpo; 413 por encima de
    `nexora.semantic.discovery.max-import-bytes`, 16 MiB por defecto);
  - `GET …/snapshots`, `GET …/snapshots/{id}`, `GET …/objects?type&schema&status&parentId` y
    `GET …/objects/{id}`;
  - nuevos códigos `snapshot_invalid` (422), `snapshot_mismatch` (422), `unsupported_contract_version` (400) y
    `data_source_archived` (409).
- **Decisiones** D16 (sobre provisional), D17 (alcance de S2 Basic) y D18 (escrituras por lotes).

### Cambiado
- Pruebas de migración y del perfil `prod`: ahora esperan 5 migraciones y los triggers nuevos.

### Verificación
- **343 pruebas, 0 fallos** (65 nuevas). Con las respuestas reales de Discovery:
  - importación completa con 48 objetos;
  - reimportación `UNCHANGED` con los mismos ids;
  - versión 2 del esquema ⇒ 6 añadidos, 2 actualizados y 5 `MISSING`;
  - vuelta a v1 ⇒ 5 reaparecidos con el mismo id;
  - alcance parcial sin efectos en otros esquemas;
  - `restricted` ⇒ `unavailableSchemas`.
- También verificado:
  - errores 400, 413, 415, 422 y 409 sin efectos en la base de datos;
  - aislamiento entre tenants (404) y permisos;
  - rollback si falla la auditoría;
  - inmutabilidad, append-only y FK compuestas en PostgreSQL;
  - el rol de aplicación sigue siendo de mínimo privilegio.
- La V5 se aplica igual con Flyway al arrancar y con el job independiente.

## 8. S3 Basic — Catálogo semántico mínimo (20 oct 2026)

Precedido de la aceptación de S2 Basic. Antes de implementar se documentaron dos contradicciones con S0:
- el modelo de propuestas y versiones frente al ciclo mínimo pedido (decisión **D19**);
- la separación autor/aprobador de D3, que se mantiene.

### Añadido
- **Dominio `catalog`:**
  - `SemanticDefinition`, con ciclo DRAFT → APPROVED → INACTIVE e invariantes de aprobación;
  - especificaciones tipadas `DefinitionSpec` (Concept, Metric, Dimension);
  - `Aggregation` (COUNT, SUM, AVG), `DimensionType`, `TimeGranularity`;
  - `TypeCategory`, que asigna categorías a los tipos PostgreSQL de Discovery;
  - `DefinitionValidator` (referencias, `MISSING`, compatibilidad de tipos, concepto aprobado);
  - `DefinitionKey` y excepciones.
- **Aplicación:**
  - `SemanticCatalogService`: crear, revisar, aprobar, desactivar y consultar, con `usable` e `issues`;
  - `CatalogInvalidationService`, que se ejecuta dentro de la importación de S2 (**D21**);
  - el puerto `SemanticDefinitionRepository` y `PhysicalObjectRepository.findByIds`.
- **Persistencia:** migración **V6** `create_semantic_catalog` y `JdbcSemanticDefinitionRepository`.
  Las referencias se guardan en columnas tipadas con FK compuestas (**D20**).
- **API** (`/api/v1/data-sources/{id}/definitions`):
  - `POST`, `GET` (con `?kind&status`), `GET /{id}` y `PUT /{id}`;
  - `POST /{id}/approve` y `POST /{id}/deactivate`;
  - nuevos códigos `definition_invalid` (422), `invalid_state_transition` (409), `self_approval_forbidden` y
    `non_human_approval_forbidden` (403).
- **Auditoría:** `DEFINITION_CREATED`, `_UPDATED`, `_APPROVED`, `_DEACTIVATED` e `_INVALIDATED`; la auditoría
  de cada importación incluye `invalidatedDefinitions`.
- **D14 resuelta (mínima):** zona IANA solo en dimensiones temporales sobre `timestamptz` (`UTC` por defecto).

### Cambiado
- `MetadataImportService` invoca la invalidación del catálogo en la misma transacción.
- Pruebas de migración y del perfil `prod`: ahora esperan 6 migraciones y el trigger nuevo; la prueba del
  perfil `prod` también importa y aprueba un concepto con solo el rol de aplicación.

### Verificación
- **394 pruebas, 0 fallos** (51 nuevas). Sobre metadatos reales de Discovery:
  - creación y aprobación de conceptos, métricas y dimensiones;
  - referencias inválidas, de otra fuente o de otro tenant ⇒ 422, y prohibidas por FK en la base de datos;
  - incompatibilidades de tipo ⇒ 422;
  - vista eliminada ⇒ definiciones `INACTIVE`, que siguen inactivas al restaurar la vista hasta revisarlas;
  - cambio de `numeric` a `text` ⇒ métrica `INACTIVE`;
  - autoaprobación, IA o servicio como aprobador ⇒ 403;
  - aislamiento entre tenants, auditoría, rollback si falla la auditoría;
  - inmutabilidad y checks en PostgreSQL;
  - perfil `prod`.

## 9. S3.1 — Catálogo de relaciones semánticas (20 oct 2026)

Precedido de la aceptación de S3 Basic. La especificación se recibió en dos partes (la primera, cortada); el
análisis previo se aceptó con un cambio del usuario: la pérdida de la FK que respalda una relación la bloquea y
exige una nueva aprobación (**D27**). No se encontraron contradicciones que comprometan la integridad, el
aislamiento multi-tenant o la seguridad.

### Añadido
- **Dominio `relationship`:**
  - `SemanticRelationship` (pares inmutables, ciclo D19, `replacesId`);
  - `ColumnPair`, `RelationshipOrigin`, `DeclaredCardinality`, `JoinType`;
  - `RelationshipValidator` (validación física y evidencia);
  - `RelationshipEvidence`, `UniquenessEvidence`, `ForeignKeyEvidence`, `CardinalityAssurance` y
    `RowMultiplicationRisk`;
  - `RelationshipSignature` (equivalencia sin orden ni dirección);
  - `ForeignKeyResolver` (clave natural exacta);
  - `RelationshipCandidateFinder` y `RelationshipCandidate`;
  - `RelationshipContract` (contrato para S4);
  - excepciones `InvalidRelationshipException` (422) y `EquivalentRelationshipException` (409).
- **Dominio `catalog`:**
  - `ApprovalRules`: regla D3 extraída de `SemanticDefinition` y compartida, sin cambio de comportamiento;
  - nuevos códigos de `DefinitionIssue`: `COLUMN_NOT_IN_RELATION`, `INCOMPATIBLE_TYPES`, `DUPLICATE_PAIR`,
    `CONTRADICTORY_PAIR`, `FOREIGN_KEY_LOST`, `REFERENCE_UNRESOLVABLE` y `REPLACEMENT_INVALID`.
- **Aplicación:**
  - `SemanticRelationshipService`: crear SEMANTIC, crear desde FK, editar, aprobar con sustitución controlada,
    desactivar, consultar, candidatas y `approvedContracts`;
  - `RelationshipInvalidationService`, invocado por la importación de S2 en la misma transacción;
  - el puerto `SemanticRelationshipRepository` y `PhysicalObjectRepository.findByNaturalKeys`.
- **Persistencia:** migración **V7** `create_semantic_relationships` (`semantic_relationship` y
  `semantic_relationship_pair`) y `JdbcSemanticRelationshipRepository`.
- **API:**
  - `/api/v1/data-sources/{id}/relationships`: `POST`, `POST /from-foreign-key`, `GET` (con
    `?status&origin&objectId`), `GET /{rid}`, `GET /{rid}/contract`, `PUT /{rid}`, `POST /{rid}/approve` y
    `POST /{rid}/deactivate`;
  - `GET /api/v1/data-sources/{id}/relationship-candidates`;
  - nuevos códigos `relationship_invalid` (422) y `relationship_equivalent_exists` (409).
- **Auditoría:** `RELATIONSHIP_CREATED`, `_UPDATED`, `_APPROVED`, `_DEACTIVATED`, `_INVALIDATED` y `_REPLACED`,
  con el tipo de destino `SEMANTIC_RELATIONSHIP` (el CHECK de V3 usa un patrón, así que no hace falta
  migración); la auditoría de cada importación incluye `invalidatedRelationships`.
- **Pruebas:** utilidad `SyntheticCatalog`, un payload sintético con la forma de Discovery para los ejemplos que
  la base ERP real no contiene.
- **Decisiones D22–D27** y riesgos R17–R19 en `ARCHITECTURE.md`.

### Cambiado
- `MetadataImportService` recibe además `RelationshipInvalidationService`.
- `SemanticDefinition.approve` delega en `ApprovalRules`.
- Pruebas de migración y del perfil `prod`: ahora esperan 7 migraciones y los 3 triggers nuevos. La prueba del
  perfil `prod` también crea, aprueba y consulta el contrato de una relación desde FK. No se eliminó ni se
  debilitó ninguna prueba.

### Verificación
- `mvn clean verify` ⇒ **BUILD SUCCESS, 448 pruebas, 0 fallos, 0 errores, 0 omitidas** (54 nuevas).
- Cobertura de las 30 pruebas obligatorias (sección 14):

| # | Requisito | Pruebas |
|---|---|---|
| 1 | Relación desde FK | API `createsADraftFromACompositeCrossSchemaForeignKeyWithItsEvidence`; `RelationshipCandidateFinderTest.foreignKeysResolveByExactNaturalKey…`; perfil `prod` |
| 2 | Sin FK | API `relatesColumnsWithDifferentNamesWithoutAnyForeignKey` |
| 3 | Caso equipment | API `equipmentDbidIsApprovableButItsUniquenessIsNeverPresentedAsGuaranteed`; `RelationshipValidatorTest.equipmentDbid…` |
| 4 | Nombres distintos | API `relatesColumnsWithDifferentNamesWithoutAnyForeignKey` (`client_code = account_number`) |
| 5 | Compuesta | API `compositeCrossSchemaRelationshipIsBackedByTheExactTargetPrimaryKey` |
| 6 | Entre esquemas | Misma prueba (`finance` → `docs`) y FK real `sales.order_lines` → `inventory.stock` |
| 7 | Columnas inexistentes | API `rejectsUnknownColumnsColumnsOfOtherTablesAndIncompatibleTypes`; `RelationshipValidatorTest.columnsMustExist…` |
| 8 | Tipos incompatibles | Misma prueba de API; `incompatibleTypesAreRejected`; `neverSuggestsBetweenIncompatibleTypes` |
| 9 | Duplicadas o ambiguas | API `rejectsRepeatedPairsAndEquivalentRelationshipsInAnyDirection`; `repeatedOrContradictoryPairsAreRejected`; BD `twoEquivalentRelationshipsCanNeverBeApprovedAtTheSameTime` |
| 10–11 | Aislamiento entre tenants y fuentes | API `isolatesTenantsAndDataSources`; BD `relationshipsAndPairsCannotReferenceObjectsOfAnotherTenantOrDataSource`; `objectsOfAnotherTenantOrDataSourceAreNotFound` |
| 12 | Candidatas no aprobadas | API `candidatesAreExplainableSuggestionsThatAreNeverStored`; `RelationshipCandidateFinderTest` |
| 13 | Cardinalidad declarada sin evidencia | `declaredCardinalityWithoutEvidenceIsReportedAsDeclaredOnly`; API (`DECLARED_ONLY`) |
| 14 | Unicidad por PK | `uniquenessEvidenceRequiresAnExactPrimaryKeyMatch`; API compuesta (`PRIMARY_KEY`) |
| 15 | Sin UNIQUE | Caso equipment (`targetUniqueness = UNKNOWN`) |
| 16–17 | Aprobador distinto; rechazo de IA y servicio | API `approverMustBeAnotherHumanAndNeverAServiceOrAnAi`; `SemanticRelationshipTest.approverMustBeAHuman…` |
| 18 | Rechazo de uso de DRAFT o INACTIVE | API `draftAndInactiveRelationshipsAreNeverUsable` |
| 19 | Invalidación por MISSING | API `aMissingTableInvalidatesAndRestoringItDoesNotReactivate` |
| 20 | Columna desaparecida | API `aMissingColumnInvalidates` |
| 21 | Cambio de tipo | API `anIncompatibleTypeChangeInvalidatesAndAReimportIsIdempotent`; `retypedColumnInvalidates` |
| 22 | Pérdida de FK | API `aLostForeignKeyBlocksUseKeepsHistoryAndRequiresAnExplicitSemanticReplacement` |
| 23 | Sin reactivación | API `aMissingTable…RestoringItDoesNotReactivate`; `lifecycleTransitionsFollowD19` |
| 24 | `If-Match` | API `writesRequireAMatchingIfMatch` |
| 25 | Auditoría | API `everyChangeIsAudited` y comprobaciones de `RELATIONSHIP_INVALIDATED` y `_REPLACED` |
| 26 | Inmutabilidad de pares | API `editingDescriptiveFieldsReturnsToDraftAndPairsAreImmutable`; BD `pairsAreAppendOnlyForEveryRole`, `applicationRoleCannotChangeImmutableColumns` (7 casos), `evenTheOwnerCannotChangeEndpoints…`; `pairsCannotBeChangedThroughTheModel` |
| 27 | Sustitución con historial | API `controlledReplacementNeverLeavesTwoApprovedAndKeepsHistory`; flujo de pérdida de FK |
| 28 | Idempotencia | Reimportación sin nuevas invalidaciones; FK repetida ⇒ 409 |
| 29 | Migración nueva | `SchemaMigrationIntegrationTest` (7 migraciones, triggers de V7) y `SemanticRelationshipDatabaseIntegrationTest` |
| 30 | Perfil `prod` | `ProductionProfileIntegrationTest.servesRequestsWithOnlyTheApplicationRole` |

## 10. S4.1 — Resolución semántica (20 oct 2026)

Punto de partida: 448 pruebas (S3.1). Antes de implementar se inspeccionaron los contratos reales. La vinculación
física de S3 (métrica o dimensión → concepto → tabla y columna) basta para resolver hasta objetos y columnas
físicos, así que no hizo falta ningún cambio de modelo ni ninguna migración. No se encontraron contradicciones
estructurales con S1–S3.1.

### Añadido
- **Dominio `resolution`:**
  - `ResolutionRequest`, `FilterOperator`, `FilterLiteral` y `FilterValue`;
  - `SemanticResolver`, con las reglas puras de resolución;
  - `SemanticResolution`, el resultado inmutable;
  - `ResolutionOutcome` (`Resolved` / `Unresolvable`), `ResolutionDiagnostic` (14 códigos) y
    `UnresolvableRequestException`.
- **Aplicación:**
  - `SemanticResolutionService` (transacción `readOnly` con aislamiento `REPEATABLE READ`);
  - `SemanticRelationshipService.contracts(…)`, que reutiliza la evaluación de S3.1.
- **API:** `POST /api/v1/data-sources/{id}/semantic-resolutions` (`SemanticResolutionController`,
  `ResolutionDtos`), con el permiso existente `semantic:resolve` (`Permission.Require.RESOLVE`), y el código
  `semantic_resolution_failed` (422) con `errors[{code, field, message, causes}]`.
- **Decisiones D28–D32** y riesgos R20–R22 en `ARCHITECTURE.md`.

### Cambiado (mínimo)
- `SemanticDefinition.IMPORT_INVALIDATION_PREFIX`: el motivo de invalidación pasa a ser una constante de dominio,
  que `CatalogInvalidationService` reutiliza; el valor no cambia.
- `CatalogContextLoader` pasa a ser público, para que la resolución reutilice la carga del catálogo sin duplicarla.
- `RelationshipDtos.ContractResponse.from` pasa a ser público, para devolver los contratos de S3.1.
- No se modificó ninguna prueba existente ni ninguna migración (V1–V7), y no se amplió ningún permiso.

### Verificación
- `mvn clean verify` ⇒ **BUILD SUCCESS, 475 pruebas, 0 fallos, 0 errores, 0 omitidas** (27 nuevas: 14 unitarias
  en `SemanticResolverTest` y 13 de integración con PostgreSQL en `SemanticResolutionApiIntegrationTest`).
- Pruebas obligatorias:

| # | Requisito | Pruebas |
|---|---|---|
| 1–4 | Una o varias métricas, con una o varias dimensiones | `resolvesOneApprovedMetric…`, `resolvesSeveralMetricsAndDimensions…`; API `resolvesMetricsDimensionsAndTypedFiltersWithoutSql` |
| 5 | Filtro válido | `typesValidFiltersAndPlacesThemBeforeOrAfterAggregation`; API (fecha, `IN` y filtro posterior a la agregación) |
| 6–8 | Tipo incompatible, operador incompatible, referencia no autorizada | `rejectsValuesOfTheWrongType`, `rejectsOperatorsThatCannotBeValidated…`, `filtersOnlyReferenceUsableFields…`; API `rejectsIncompatibleValuesOperatorsAndUnauthorizedFilterReferences` |
| 9–10 | Métrica en DRAFT o INACTIVE | `rejectsDraftDeactivatedAndInvalidatedDefinitionsDistinctly`; API `draftAndDeactivatedDefinitionsAreNotResolved` |
| 11 | Invalidada físicamente | API `aTypeChangeInvalidatesTheMetricAndItIsNotResolved` |
| 12–13 | Objeto o columna desaparecidos | `approvedDefinitionsWithMissingObjectsOrColumnsAreNotUsable`; API `aMissingTableInvalidates…` y `aMissingColumnInvalidatesTheDimension` |
| 14–15 | Tenant o fuente ajenos | `unknownWrongKindAndForeignIdsAreNotFoundWithoutDisclosure`; API `anotherTenantNeitherSeesTheDataSourceNorCanUseItsIds` y `definitionsAndRelationshipsOfAnotherDataSourceAreNotFound` |
| 16–17 | Duplicados; solicitud sin métricas | `requiresMetricsAndRejectsDuplicates`; API `rejectsDuplicatesAndRequestsWithoutMetrics` |
| 18–20 | Relación preferida utilizable, no utilizable, y dos ambiguas sin selección | `preferredRelationshipsAreValidatedButNeverSelected`; API `preferredRelationshipsAreValidatedAndEchoedButNeverChosen` |
| 21 | Sin SQL | `theResultCarriesNoSqlJoinOrRoute`; API (nombres de campo y texto de la respuesta) |
| 22 | Inmutable | `theResultIsImmutable` |
| 23 | Consistencia y revisiones | `theFingerprintIsDeterministicAndCapturesEveryRevision`; API `capturesRevisionsSoThatLaterChangesAreDetectable` (incluye el aislamiento `REPEATABLE READ`) |
| 24 | Autorización | API `onlyTheExistingResolvePermissionCanResolveAndResolvingChangesNothing` (incluye solo lectura: sin auditoría ni cambios de versión) |
| 25 | PostgreSQL de pruebas | Toda `SemanticResolutionApiIntegrationTest` (Testcontainers 17.6, rol de aplicación) |
| — | Adicional | API `anArchivedDataSourceIsNotResolved` (409) |

## 11. S4.2 — Planificación lógica segura (20 oct 2026)

Punto de partida: 475 pruebas (S4.1). En la inspección previa se comprobó que el modelo permite un plan seguro con
una política conservadora: un único objeto base y JOIN solo hacia una PK exacta. También se detectaron dos
carencias, ninguna de las cuales obligó a relajar validaciones:
- S3 no tiene unidades ni monedas: no se atribuyen y `INCOMPATIBLE_UNITS` queda reservado;
- el contrato de S4.1 no tenía granularidad temporal: se añade `timeGranularities` a la petición del plan.

### Añadido
- **Dominio `planning`:**
  - `LogicalPlanner`, planificador puro: grano, rutas, ambigüedad, fan-out, tipos de JOIN, filtros y límites;
  - `LogicalPlan`, el plan inmutable y autocontenido (formato `nexora-logical-plan/1`);
  - `PlanRequest`, `PlanDiagnostic` (12 códigos), `PlanningOutcome` y `PlanRejectedException`;
  - `PlanDependency`, `StoredPlan`, `PlanValidity` y `PlanValidityChecker`.
- **Aplicación:**
  - `LogicalPlanService`: crea planes bloqueando la fuente y recalculando S4.1, los consulta y comprueba su vigencia;
  - `SemanticRelationshipService.approved(…)`, que reutiliza la evaluación de S3.1;
  - el puerto `LogicalPlanRepository`.
- **Persistencia:** migración **V8** `create_logical_plans` (`logical_plan` y `logical_plan_dependency`,
  append-only, con FK compuestas) y `JdbcLogicalPlanRepository`.
- **API:** `POST /api/v1/data-sources/{id}/logical-plans`, `GET …/{planId}` y `GET …/{planId}/validity`, con el
  permiso existente `semantic:resolve`, y el código `logical_plan_rejected` (422) con
  `errors[{code, field, message, relationshipIds, alternatives}]`.
- **Auditoría:** `LOGICAL_PLAN_CREATED`, con destino `LOGICAL_PLAN`. Registra recuentos, huella y estrategia, nunca
  valores de filtros.
- **Decisiones D33–D40** y riesgos R23–R25 en `ARCHITECTURE.md`.

### Cambiado (mínimo)
- S4.1: `SemanticResolution.filterDimensions` (dimensiones usadas solo en filtros, con su versión e incluidas en la
  huella), para que el plan copie y vigile también esas definiciones. Ninguna prueba de S4.1 tuvo que cambiar.
- `ResolutionDtos.ResolutionRequestBody.toDomain` pasa a ser público, para reutilizar la validación de S4.1.
- Pruebas de migración y del perfil `prod`: ahora esperan 8 migraciones y los 4 triggers nuevos. No se modificó
  V1–V7 ni se eliminó o debilitó ninguna aserción.

### Verificación
- `mvn clean verify` ⇒ **BUILD SUCCESS, 510 pruebas, 0 fallos, 0 errores, 0 omitidas** (35 nuevas: 20 unitarias
  en `LogicalPlannerTest` y 15 de integración con PostgreSQL en `LogicalPlanApiIntegrationTest`).
- Pruebas obligatorias:

| # | Requisito | Pruebas |
|---|---|---|
| 1–3 | Una métrica, métrica con dimensión, varias métricas | `plansOneMetricOnOneTableWithoutJoins`, `plansCompatibleMetricsWithDimensionsAndFilters…`; API `plansMetricsDimensionsAndFiltersOnOneTable…` |
| 4–5 | Filtros previos y posteriores a la agregación | Mismas pruebas (`FILTER_ROWS` y `FILTER_GROUPS`) |
| 6–7 | INNER y LEFT autorizados | `joinsToAPrimaryKeyWithAnAuthorizedInnerJoin`, `prefersAnAuthorizedLeftJoin…`; API `anInnerJoinOverACompositeCrossSchema…` y `anAuthorizedLeftJoinKeepsRows…` |
| 8–10 | Compuesta, entre esquemas, columnas distintas de `id` | `joinsCompositeCrossSchemaPairsThatAreNotIdColumns`; API (`company_code`/`number`) |
| 11 | Aprobada pero no utilizable | `approvedButUnusableRelationshipsAreNeverUsed` |
| 12–13 | Relación de otro tenant u otra fuente | API `plansAndRelationshipsOfOtherTenantsAndDataSourcesAreNotVisible` |
| 14–16 | Ambigüedad, selección explícita, ruta inexistente | `neverChoosesBetweenTwoRoutes…`, `reportsMissingRoutes`; API `twoApprovedRoutesAreAmbiguousUntilOneIsPreferred` |
| 17–20 | Fan-out, unicidad desconocida, cardinalidad declarada sin evidencia, seguridad por PK | `joiningTowardsTheManySideIsFanOut`, `equipmentDbidIsApprovedButAggregatingAfterItIsNotProvenSafe`, `aDeclaredOneToOneWithoutEvidenceIsNotProof`; API (caso equipment y PK) |
| 21 | Granos incompatibles | `metricsOverDifferentTablesHaveIncompatibleGrains`; API |
| 22 | Filtro inseguro sobre LEFT | `aRowFilterOnTheOptionalSideOfALeftJoinIsUnsafe`; API |
| 23 | Unidades incompatibles | No expresable: el catálogo no tiene unidades (documentado, código reservado) |
| 24–25 | Persistencia inmutable y snapshot histórico | API `persistedPlansAreImmutableEvenForTheOwner`, `aRevisedDefinitionMakesThePlanStale…` (GET idéntico) |
| 26–29 | Definición cambiada, relación desactivada, cambio físico, fuente archivada | API `aRevisedDefinition…`, `aDeactivatedRelationship…`, `anIncompatiblePhysicalChange…`, `anArchivedDataSource…` |
| 30–32 | Entre tenants, entre fuentes, autorización | API `plansAndRelationshipsOfOther…`, `onlyTheResolvePermissionPlans…` |
| 33 | Auditoría de creación | API `plansMetricsDimensionsAndFilters…` (sin valores de filtros) |
| 34 | Huella determinista | `theFingerprintIsDeterministicAndContentSensitive`; API `identicalRequestsGiveIdenticalFingerprints…` |
| 35 | Límites de complejidad | `boundsTheRouteSearch`, `boundsTheNumberOfObjects` |
| 36 | Sin SQL | `thePlanIsImmutableSelfContainedAndHasNoSql`; API |
| 37 | Integración real con S4.1 | API `semanticResolutionFailuresAreDistinctFromPlanningFailures` y todas las creaciones (S4.1 recalculado) |
| 38 | `mvn clean verify` sin regresiones | 510/510 |
| — | Adicionales | `temporalDimensionsNeedAnAllowedGranularity`, `neverReversesALeftJoin…`, `anInnerJoinAfterALeftJoinIsRejected` |

## 12. INT-3.4 — `valueType` en el catálogo de dimensiones (28 oct 2026)

Cambio mínimo autorizado (decisión D1 = B de INT-3.4) para que Nexora Intelligence conozca la categoría de valor de
cada dimensión sin llamadas adicionales por columna ni duplicar el mapeo de tipos.

### Añadido
- `DefinitionValidator.valueType(spec, context)`: categoría (`TypeCategory`) de la columna de una dimensión, con el
  mismo método privado `category` que ya usa la validación (tipos o motores desconocidos ⇒ `OTHER`). Vacío para
  conceptos, métricas y columnas que no están en los metadatos cargados.
- `SemanticCatalogService.EvaluatedDefinition.valueType` (calculado al evaluar la definición con el mismo contexto).
- `DefinitionDtos.DimensionResponse.valueType`: campo nuevo en `GET /definitions`, `GET /definitions/{id}` y en las
  respuestas de creación, edición, aprobación y desactivación. Se omite (inclusión no nula) cuando no se conoce.

### Sin cambios
- Formato `nexora-logical-plan/1`, resolución S4.1, planificación S4.2, migraciones, seguridad y peticiones de la API.
  No se expone ningún nombre físico adicional. Cambio aditivo: los consumidores existentes (Nexora Reports no lee
  definiciones) no se ven afectados.

### Verificación
- `mvn clean verify` ⇒ **BUILD SUCCESS, 517 pruebas, 0 fallos, 0 errores, 0 omitidas** (entorno de Claude; punto
  de partida 513). Nuevas: 3 unitarias en `DefinitionValidatorTest` y 1 de integración en
  `SemanticCatalogApiIntegrationTest` (`dimensionsExposeTheValueTypeOfTheirColumn`).
- Verificado por el usuario en Windows: `mvn clean verify` ⇒ **517 pruebas, 0 fallos, 0 errores, 0 omitidas**
  (verificación automatizada; sin E2E real con Keycloak todavía).
