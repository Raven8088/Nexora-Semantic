# Nexora Semantic Engine — Arquitectura

| Versión | Estado | Fecha |
|---|---|---|
| 1.0 | S0 aprobada con ajustes · S1 implementada | 19 oct 2026 |
| 1.1 | Correcciones de auditoría B1, B2, I1, I2, I4, I5, I6 | 19 oct 2026 |
| 1.2 | I3: migraciones de producción en un job independiente (D15) | 19 oct 2026 |
| 1.3 | D10: migración a Spring Boot 4.1.1 (Java 21) | 19 oct 2026 |

Estado vivo del proyecto: [PROJECT_STATUS.md](PROJECT_STATUS.md) · Historial: [CHANGELOG.md](CHANGELOG.md).

Nexora Semantic Engine transforma los metadatos técnicos que produce **Nexora Discovery** en conocimiento
empresarial **validado, versionado y reutilizable** por **Nexora Reports**. Ejemplo: Discovery informa de
`sales.orders.amount` y `sales.orders.status`; una IA (Qwen) o una persona propone que representan "ventas";
Semantic permite corregir la interpretación, definir qué pedidos cuentan como venta, validarla, aprobarla,
versionarla y servirla a Reports como un plan lógico tipado.

**Fuera de alcance de Semantic:** ejecutar SQL empresarial, conectarse a bases de clientes, guardar
credenciales, interpretar lenguaje natural (eso es Nexora Intelligence) y ejecutar consultas (eso es el
futuro Motor de consultas).

---

## 0. Hallazgos sobre Nexora Discovery que condicionan el diseño

Contrato observado en el repositorio local `Nexora Discovery` (0.1.0-SNAPSHOT, `API.md`):

| # | Hecho observado | Consecuencia |
|---|---|---|
| H1 | `POST /api/discovery` es síncrono, sin estado y exige la **contraseña en cada llamada**. | Semantic no invoca a Discovery con credenciales (D1). |
| H2 | Discovery **no tiene autenticación**. | No se trata como servicio de confianza en red abierta. |
| H3 | La respuesta **no trae identificadores**, solo nombres (`signature` para rutinas, `name` para FK). | Semantic crea referencias estables propias (UUID ↔ clave natural). |
| H4 | La respuesta **no devuelve los esquemas solicitados** y **omite en silencio** los esquemas sin `USAGE`. | Ausencia ≠ eliminación; diff consciente del alcance (ajuste 2). |
| H5 | No expone constraints `UNIQUE`; las vistas no tienen PK/FK. | La cardinalidad de relaciones requiere **aserción humana**. |
| H6 | `definition` (SQL de vistas/rutinas) es opcional y sensible. | Se descarta al importar (D9). |
| H7 | `SchemaMetadata.table()` busca con `equalsIgnoreCase`. | Semantic compara identificadores **de forma exacta** (riesgo R1). |
| H8 | Errores RFC 7807 con propiedad `code` estable. | Semantic adopta el mismo formato. |

---

## 1. Arquitectura general y módulos

Monolito modular, **un único módulo Maven**, arquitectura hexagonal y DDD ligero (igual que Discovery).
Sin microservicios, mensajería, CQRS ni event sourcing. Las reglas de capas se verifican con ArchUnit.

```
                 Clientes: UI de gobierno · Nexora Reports · Nexora Intelligence · stewards
                                           │ REST /api/v1 (JWT OIDC)
┌──────────────────────────────────────────▼──────────────────────────────────────────┐
│ api             Controladores · DTO · seguridad (JWT → permisos) · RFC 7807 · ETags  │
├──────────────────────────────────────────────────────────────────────────────────────┤
│ application     Casos de uso + puertos de salida (repositorios, auditoría, Discovery,│
│                 IA). Única dependencia de Spring permitida: spring-tx @Transactional │
├──────────────────────────────────────────────────────────────────────────────────────┤
│ domain          Java puro: shared · tenant · source · catalog · proposal ·           │
│                 resolution · audit                                                   │
├──────────────────────────────────────────────────────────────────────────────────────┤
│ infrastructure  persistence (JdbcClient + Flyway) · discovery (ACL) · ai · config    │
└───────────┬──────────────────────────────┬─────────────────────────────┬────────────┘
            ▼                              ▼                             ▼
   PostgreSQL propio de Semantic   Payload de Discovery (importado)   Qwen/Ollama (S5, opcional)
```

Regla de dependencias: `api` → `application` → `domain`; `infrastructure` → `application` → `domain`;
`api` e `infrastructure` **no se conocen entre sí**; `domain` no depende de ningún framework.

| Módulo | Responsabilidad | Etapa |
|---|---|---|
| `tenant` | Empresas y su estado | S1 |
| `source` | Fuentes de datos, snapshots, objetos físicos con referencia estable, detección de cambios | S1 (fuentes) · S2 |
| `audit` | Registro append-only de decisiones y modificaciones | S1 |
| `catalog` | Definiciones tipadas, versiones inmutables, sinónimos, revisión de catálogo | S3 |
| `proposal` | Propuestas IA/manuales, revisiones, evidencias, aprobación | S3 |
| `resolution` | Solicitud estructurada → plan lógico tipado y reproducible | S4 |
| `suggestion` | Propuestas generadas por Qwen | S5 |

---

## 2. Estructura de paquetes Java

```
com.nexora.semantic
├── NexoraSemanticApplication
├── domain
│   ├── shared        TenantId, PrincipalRef, PrincipalType, CallerContext, PageQuery, Page,
│   │                 TextRules, excepciones de dominio
│   ├── tenant        Tenant, TenantKey, TenantStatus
│   ├── source        DataSource, DataSourceId, DataSourceKey, DataSourceStatus, EngineCode
│   │                 (S2) MetadataSnapshot, PhysicalObject, NaturalKey, SchemaChange, ChangeDetector
│   ├── audit         AuditEvent, AuditAction, AuditTargetType
│   ├── catalog       (S3) Definition, DefinitionVersion, DefinitionSpec (sealed), Labels, Synonym,
│   │                 CatalogRevision, Dependency, SpecValidator
│   ├── catalog.expr  (S3) Expression, Predicate (sealed), Aggregation, Additivity, Granularity, Unit,
│   │                 Cardinality, JoinOptionality
│   ├── proposal      (S3) Proposal, ProposalState, ProposalRevision, Origin, Provenance, Evidence,
│   │                 ApprovalPolicy
│   └── resolution    (S4) SemanticRequest, ResolvedPlan, MetricGroup, EntityGraph, JoinPathFinder,
│                     FanoutAnalyzer, GrainAnalyzer, UnitAnalyzer, TermResolver
├── application
│   ├── tenant        TenantService
│   ├── source        DataSourceService (S2: SnapshotImportService, ChangeImpactService)
│   ├── audit         AuditQueryService
│   ├── catalog · proposal · resolution · suggestion      (S3–S5)
│   └── port          TenantRepository, DataSourceRepository, AuditLog
│                     (S2+) SnapshotRepository, PhysicalObjectRepository, DiscoveredMetadataReader,
│                     DefinitionRepository, ProposalRepository, InterpretationSuggestionPort, …
├── infrastructure
│   ├── persistence   Jdbc*Repository (JdbcClient), traducción de errores SQL, LeastPrivilegeVerifier
│   ├── migration     SchemaMigrator, SchemaMigrationMain (job de migraciones de producción, sin Spring)
│   ├── discovery     (S2) DTO de lectura tolerante del contrato Discovery (ACL)
│   ├── ai            (S5) StubSuggestionAdapter, OllamaQwenSuggestionAdapter
│   └── config        Clock, cableado, LeastPrivilegeConfiguration (salvaguarda del perfil prod)
└── api
    ├── security      SecurityConfig, JwtPrincipalConverter, Permission, Role, CallerContextResolver,
    │                 manejadores 401/403
    ├── web           CorrelationIdFilter, ETags, paginación
    ├── error         ApiExceptionHandler (RFC 7807 + code)
    ├── tenant · source · audit       controladores + DTO
    └── catalog · proposal · resolution · suggestion      (S3–S5)
resources/db/migration   Flyway V1…Vn
```

> **Ajuste respecto a S0:** la seguridad HTTP vive en `api.security` (no en `infrastructure.security`)
> porque es parte del adaptador de entrada; así `api` e `infrastructure` permanecen independientes.

---

## 3. Modelo de dominio

```
Tenant 1──* DataSource 1──* MetadataSnapshot
                 │ 1
                 └──* PhysicalObject (padre: schema → table/view → column; table → fk)
SchemaChange *──1 MetadataSnapshot, *──1 PhysicalObject

DataSource 1──* Definition 1──* DefinitionVersion (inmutable)
                    │                 ├─* Dependency → PhysicalObject | Definition
                    │                 └ labels.synonyms → SynonymIndex (proyección)
                    └ current_version → DefinitionVersion

Proposal *──0..1 Definition · base_version → DefinitionVersion
   1──* ProposalRevision (inmutable) · 1──* Evidence → PhysicalObject · 0..1 → DefinitionVersion

SemanticPlan (inmutable) ─* PlanDependency → DefinitionVersion | PhysicalObject (+ hash observado)
AuditEvent → cualquier agregado (target_type, target_id)
```

| Agregado | Invariantes |
|---|---|
| **Tenant** | `key` única (`^[a-z0-9][a-z0-9-]{1,62}$`), estado ACTIVE/SUSPENDED. Un tenant suspendido no puede operar. |
| **DataSource** | Pertenece a un tenant; `key` única por tenant; `engine` y `databaseName` inmutables tras el alta; **sin host, usuario ni contraseña**. Estado ACTIVE/ARCHIVED. |
| **MetadataSnapshot** | Inmutable; registra alcance solicitado, hash del contenido normalizado y `productVersion`. |
| **PhysicalObject** | Identidad `(dataSource, type, naturalKey)` → UUID estable; ACTIVE/MISSING; nombres exactos; comentario marcado **no confiable**. |
| **Definition** | Identidad semántica (`kind`, `key`) por fuente; `status` ACTIVE/DEPRECATED/RETIRED; `health` OK/AT_RISK/BROKEN. |
| **DefinitionVersion** | **Inmutable**: `versionNo`, spec tipado, `labels`, `contentHash`, `catalogRevision`, proposal de origen, publicador. |
| **Proposal** | Única vía para crear o cambiar definiciones; revisiones inmutables; procedencia y evidencia. |
| **SemanticPlan** | Resultado de resolución persistido, inmutable, con todas sus dependencias versionadas (ajuste 4). |

### 3.1 Claves naturales (referencias estables a objetos de Discovery)

| Tipo | Clave natural | Ejemplo |
|---|---|---|
| SCHEMA | `schema` | `sales` |
| TABLE / VIEW / MATERIALIZED_VIEW | `schema.name` | `sales.orders` |
| COLUMN | `schema.relation.column` | `sales.orders.amount` |
| ROUTINE | `schema.signature` | `sales.format_amount(numeric, character)` |
| FOREIGN_KEY | `schema.table#fkName` | `sales.order_lines#order_lines_stock_fk` |

Cada segmento se codifica para que ningún identificador pueda falsificar separadores. Un objeto que
reaparece recupera el **mismo UUID**; un renombrado es baja + alta (reasignación manual en el MVP).

### 3.2 Especificaciones tipadas (sin SQL libre)

Árbol cerrado de expresiones (`sealed` en Java, `jsonb` con discriminador, Jackson **sin default typing**).

| Kind | Campos |
|---|---|
| **CONCEPT** | `binding` → tabla/vista · `grainKey` (columnas que identifican una fila; obligatorio en vistas) · descripción. |
| **DIMENSION** | `concept` · `column` · `category` (CATEGORICAL, TEMPORAL, BOOLEAN, NUMERIC) · `granularities` y `timeZone` si es temporal · `labelColumn` · `viaRelationship` (rol explícito). |
| **METRIC** (MVP `SIMPLE`) | `concept` base · `measure` (expresión intra-fila de columnas del concepto base) · `aggregation` (SUM, COUNT, COUNT_DISTINCT, AVG, MIN, MAX) · `additivity` · `requiredRules` · `unit` · `timeDimension` · `defaultGranularity` · `nullHandling` · `allowedDimensions`. |
| **RELATIONSHIP** | `from`, `to` · `columnPairs` · `cardinality` (MANY_TO_ONE, ONE_TO_ONE) · `optionality` · `role` · `basis` (FK_DECLARED/HUMAN_ASSERTED). |
| **RULE** | `concept` · `predicate` (p. ej. `status IN ('PAID','SHIPPED') AND cancelled_at IS NULL`) · `usage`. |
| **Sinónimos** | En `labels.synonyms[]` de cada versión (`term`, `locale`, `primary`); versionados y aprobados. |

```json
{ "kind": "METRIC", "key": "ventas_netas",
  "labels": { "name": "Ventas netas", "synonyms": [{"term": "ventas", "primary": true}] },
  "spec": { "type": "SIMPLE", "specVersion": 1, "concept": "def:<uuid pedido>",
            "measure": {"op": "SUB", "left": {"col": "obj:<uuid amount>"}, "right": {"col": "obj:<uuid discount>"}},
            "aggregation": "SUM", "additivity": "ADDITIVE",
            "unit": {"type": "CURRENCY", "currencyColumn": "obj:<uuid currency>"},
            "requiredRules": ["def:<uuid pedido_valido>"], "timeDimension": "def:<uuid fecha_pago>",
            "defaultGranularity": "MONTH", "nullHandling": "IGNORE" } }
```

### 3.3 Validaciones del spec (idénticas para personas e IA)

- Toda referencia existe, pertenece a la misma fuente y tenant, y está ACTIVE.
- Compatibilidad de tipos mediante `TypeCategory` (NUMERIC, TEXT, DATE, TIMESTAMP, BOOLEAN, UUID, OTHER):
  `SUM`/`AVG` solo NUMERIC; literales compatibles con su columna; dimensiones temporales DATE/TIMESTAMP.
- Relaciones: pares de columnas del mismo tamaño y tipos compatibles; cardinalidad "verificada" solo si
  es FK declarada hacia la PK; en otro caso la asevera el aprobador.
- Sin ciclos entre reglas; sin referencias a definiciones RETIRED; `key` única; avisos por colisión de sinónimos.

---

## 4. Esquema PostgreSQL

Convenciones: base de datos propia, esquema `semantic`; `tenant_id uuid NOT NULL` en toda tabla de
negocio; **`UNIQUE (tenant_id, id)` y claves foráneas compuestas `(tenant_id, x_id)`** para que una
referencia cruzada entre tenants sea imposible incluso ante un error de código; enums como `text` + `CHECK`;
`timestamptz`; Flyway.

**Roles de base de datos:** el propietario (Flyway, DDL) es distinto del rol de aplicación (DML). El rol de
aplicación no tiene `DELETE` sobre ninguna tabla ni `UPDATE` sobre tablas append-only/inmutables. Los
triggers bloquean además la mutación de esas tablas para cualquier rol.

**Columnas inmutables (V4, hallazgo I1):** el rol de aplicación solo tiene `UPDATE` por columna sobre los
campos mutables (`tenant`: `name, status, updated_at, updated_by, row_version`; `data_source`: además
`description`). Un trigger rechaza, para cualquier rol incluido el propietario, cambios de `id`, `tenant_id`,
`key`, `engine`, `database_name` y `created_*`. En particular, una fuente nunca puede pasar a otro tenant.
Toda tabla nueva con columnas inmutables seguirá el mismo patrón. Las migraciones aplicadas no se modifican:
cada cambio es una migración nueva.

| Tabla | Etapa | Columnas principales | Restricciones |
|---|---|---|---|
| `tenant` | S1 | id, key, name, status, created_at/by, updated_at/by, row_version | `UNIQUE(key)` |
| `data_source` | S1 | id, tenant_id, key, name, engine, database_name, description, status, created_at/by, updated_at/by, row_version | `UNIQUE(tenant_id,key)`, `UNIQUE(tenant_id,id)`; FK tenant; **sin credenciales** |
| `audit_event` | S1 | id, tenant_id, occurred_at, actor_id, actor_type, action, target_type, target_id, correlation_id, details jsonb | append-only (grants + trigger); FK tenant |
| `metadata_snapshot` | S2 | id, tenant_id, data_source_id, contract_version, engine, product_version, database_name, scope_schemas text[], content_sha256, object_count, outcome, imported_by/at | FK compuesta a data_source |
| `physical_object` | S2 | id, tenant_id, data_source_id, parent_id, object_type, schema_name, object_name, child_name, signature, natural_key, attributes jsonb, comment, attributes_hash, status, first/last_seen_snapshot_id, missing_since_snapshot_id | `UNIQUE(tenant_id,data_source_id,object_type,natural_key)` |
| `schema_change` | S2 | id, tenant_id, data_source_id, snapshot_id, physical_object_id, change_type, severity, before, after, status, acknowledged_by/at | — |
| `catalog_state` | S3 | tenant_id, data_source_id, current_revision | bloqueo `FOR UPDATE` al publicar |
| `definition` | S3 | id, tenant_id, data_source_id, kind, key, status, health, health_reason, current_version_id, row_version | `UNIQUE(tenant_id,data_source_id,kind,key)` |
| `definition_version` | S3 | id, tenant_id, definition_id, version_no, spec_version, spec, labels, content_sha256, catalog_revision, source_proposal_id, published_by/at, change_note | inmutable (trigger) |
| `definition_dependency` | S3 | tenant_id, version_id, dep_type, physical_object_id, definition_id | índices para impacto |
| `synonym_index` | S3 | tenant_id, data_source_id, normalized_term, locale, definition_id, version_id, kind, is_primary | proyección reconstruible |
| `proposal` | S3 | id, tenant_id, data_source_id, definition_id, change_kind, kind, proposed_key, base_version_id, state, origin, current_revision_no, confidence, provenance, created_by(+type), submitted_by/at, decided_by/at, decision_reason, published_version_id, row_version | — |
| `proposal_revision` | S3 | id, tenant_id, proposal_id, revision_no, spec, labels, author_id/type, comment, validation_report | inmutable |
| `proposal_evidence` | S3 | id, tenant_id, proposal_id, evidence_type, physical_object_id, excerpt, weight | — |
| `semantic_plan` | S4 | id, tenant_id, data_source_id, catalog_revision, request jsonb, outcome, plan jsonb, plan_hash, requested_by, created_at | inmutable |
| `semantic_plan_dependency` | S4 | tenant_id, plan_id, dep_type, definition_version_id, content_sha256, physical_object_id, attributes_hash, snapshot_id | inmutable |
| `suggestion_run` | S5 | id, tenant_id, data_source_id, snapshot_id, status, model, model_version, prompt_version, requested_by, timestamps, accepted/discarded_count, error_code | — |

> **Ajuste respecto a S0:** `catalog_state` se crea en S3, donde se usa, y no en S1 (ajuste 10).

---

## 5. Estados y reglas de transición

### 5.1 Propuesta (incluye ajuste 5)

```
           edit (rev+1)                          edit (rev+1)
            ┌──┐                                   ┌──┐
            ▼  │        submit                     ▼  │
create ──► DRAFT ───────────────► IN_REVIEW ──────► CHANGES_REQUESTED
            ▲ │                    │  │  │  request_changes   │
            │ │withdraw   approve  │  │  │                    │ submit → IN_REVIEW
            │ ▼                    ▼  │  │ edit (rev+1)       │ withdraw → WITHDRAWN
            │ WITHDRAWN      APPROVED │  └───────────┐
            │                         ▼ reject       │
            └───────────────────── REJECTED          │
            └────────────────────────────────────────┘  (vuelve a DRAFT: requiere nuevo submit y nueva revisión)
```

| Transición | Quién | Precondiciones |
|---|---|---|
| create | EDITOR (MANUAL) · motor IA (AI, **siempre DRAFT**) · sistema (DRIFT) | spec deserializable |
| edit | EDITOR | `If-Match`. Crea `ProposalRevision`. **Si la propuesta estaba IN_REVIEW vuelve a DRAFT**, se anula el envío (`submitted_by/at`) y se requiere nuevo submit y nueva revisión. En CHANGES_REQUESTED permanece en ese estado. |
| submit | EDITOR **humano** | validación completa sin errores; un principal AI/SERVICE no puede enviar |
| approve | APPROVER **humano** | `expectedRevisionNo` = revisión actual; `base_version_id` = versión vigente (si no, 409); revalidación completa; invariantes de separación (§8.2) y política empresarial configurada |
| reject / request_changes | APPROVER | `reason` obligatorio |
| withdraw | autor o EDITOR | desde DRAFT o CHANGES_REQUESTED |
| terminales | — | APPROVED, REJECTED, WITHDRAWN son inmutables |

La aprobación es una única transacción: bloquea `catalog_state`, incrementa revisión, inserta
`definition_version`, actualiza `definition`, recalcula dependencias y sinónimos y escribe `audit_event`.

### 5.2 Definición

- `status`: ACTIVE → DEPRECATED (resoluble con aviso) → RETIRED (no resoluble), siempre vía propuesta aprobada.
- `health` (calculado): OK · AT_RISK (cambio no rompedor en dependencia) · BROKEN (dependencia MISSING,
  tipo incompatible o dependencia BROKEN/RETIRED). BROKEN se excluye de la resolución.
- Versión vigente vs reemplazada se **deriva** de `current_version_id`; la fila nunca cambia.

### 5.3 Objetos físicos y snapshots (incluye ajuste 2)

- La importación es **todo o nada**: un payload inválido o truncado se rechaza completo y no altera el modelo.
- Un objeto solo pasa a MISSING si **su esquema estaba dentro del alcance solicitado y presente en el
  payload**. Los objetos fuera de alcance no se tocan.
- Si falta un esquema solicitado completo se registra `SCHEMA_UNAVAILABLE` (posible pérdida de privilegio) y
  sus hijos **no** se marcan MISSING hasta que un STEWARD lo reconoce.
- Al reaparecer, el objeto vuelve a ACTIVE con el mismo UUID.
- `SchemaChange`: OPEN → ACKNOWLEDGED → RESOLVED. Tipos: OBJECT_ADDED, OBJECT_REMOVED, TYPE_CHANGED,
  NULLABILITY_CHANGED, PK_CHANGED, FK_ADDED, FK_REMOVED, COMMENT_CHANGED, SCHEMA_UNAVAILABLE.

### 5.4 Tenant y fuente de datos (S1)

- Tenant: ACTIVE ↔ SUSPENDED (solo administrador de plataforma). Suspendido ⇒ `403 tenant_access_denied`.
- DataSource: ACTIVE ↔ ARCHIVED (STEWARD). No se borran: el rol de aplicación no tiene `DELETE`.

---

## 6. API REST

Base `/api/v1`. **El tenant proviene siempre del token**, nunca de la ruta ni del cuerpo. Errores RFC 7807
con `code`. Paginación `?page` (≥0) y `?size` (1–200, defecto 50). Recursos mutables con `ETag` e `If-Match`.
Las propiedades JSON desconocidas se rechazan (`400`), lo que impide colar campos como `password`.
Cada respuesta incluye `X-Request-Id` con un identificador de correlación **generado por el servidor**; el valor
que envíe el cliente en esa cabecera se ignora (hallazgo I2).

### 6.1 Implementado en S1

| Método y ruta | Permiso | Respuestas |
|---|---|---|
| `POST /admin/tenants` | `platform:tenant:manage` | 201 + `Location` + `ETag` · 400 · 409 `duplicate_key` |
| `GET /admin/tenants` · `GET /admin/tenants/{id}` | `platform:tenant:manage` | 200 · 404 |
| `PATCH /admin/tenants/{id}` (`name`, `status`) | `platform:tenant:manage` | 200 · 412 · 428 |
| `POST /data-sources` | `semantic:source:manage` | 201 + `Location` + `ETag` · 400 · 409 |
| `GET /data-sources?status` | `semantic:catalog:read` | 200 paginado |
| `GET /data-sources/{id}` | `semantic:catalog:read` | 200 + `ETag` · 404 (también si es de otro tenant) |
| `PATCH /data-sources/{id}` (`name`, `description`, `status`) | `semantic:source:manage` | 200 · 412 `precondition_failed` · 428 `precondition_required` |
| `GET /audit-events?targetType&targetId` | `semantic:audit:read` | 200 paginado |
| `GET /actuator/health` | público (sin detalles) | 200 / 503 |

`key`, `engine` y `databaseName` de una fuente son inmutables (identifican la base para los snapshots).
En `PATCH`, un campo ausente no cambia; `description: ""` la elimina.

### 6.2 Etapas siguientes

| Etapa | Endpoints |
|---|---|
| S2 | `POST /data-sources/{id}/snapshots` · `GET …/snapshots[/{sid}]` · `GET …/objects[/{oid}]` · `GET …/changes` · `POST /changes/{cid}/acknowledge` |
| S3 | `GET /definitions…` · `/versions` · `/dependents` · `GET /catalog/terms` · `POST /proposals` · `PUT /proposals/{id}/draft` · `POST /proposals/{id}/{validate,submit,approve,reject,request-changes,withdraw}` |
| S4 | `POST /resolutions` (200 con RESOLVED/AMBIGUOUS/UNRESOLVABLE, persiste `semantic_plan`) · `GET /resolutions/{id}` · `POST /resolutions/{id}/replay` · `GET /catalog?asOfRevision` |
| S5 | `POST /data-sources/{id}/suggestion-runs` (202) · `GET /suggestion-runs/{id}` |

### 6.3 Códigos de error (`code`)

| HTTP | code |
|---|---|
| 400 | `validation_failed` (con `errors[{field,message}]`), `malformed_request` |
| 401 | `unauthorized` (con `WWW-Authenticate: Bearer`) |
| 403 | `forbidden`, `tenant_access_denied`; (S3) `self_approval_forbidden`, `non_human_approval_forbidden` |
| 404 | `not_found` |
| 405 / 415 / 406 | `method_not_allowed` / `unsupported_media_type` / `not_acceptable` |
| 409 | `duplicate_key`; (S3) `invalid_state_transition`, `stale_proposal`, `revision_mismatch` |
| 412 / 428 | `precondition_failed` / `precondition_required` |
| 413 / 422 | (S2) `payload_too_large` / `snapshot_mismatch`; (S3) `semantic_validation_failed` |
| 429 / 503 | (S5) `too_many_requests` / `ai_unavailable` |
| 500 | `internal_error` |

Las respuestas incluyen `correlationId` y nunca trazas, SQL, valores enviados ni detalles internos.

---

## 7. Resolución semántica (S4)

Entrada: `SemanticRequest` estructurado (Intelligence traduce el lenguaje natural). Salida: plan lógico
tipado; **Semantic no genera ni ejecuta SQL**.

1. **Términos → definiciones**: id/key exacta o `synonym_index` normalizado. Varios candidatos del mismo
   tipo sin `primary` ⇒ `AMBIGUOUS_TERM`. Nunca se adivina.
2. Solo versiones vigentes en `asOfRevision` (o actual), `health ≠ BROKEN`, `status ≠ RETIRED`.
3. **Grafo de entidades** construido exclusivamente con RELATIONSHIP aprobadas.
4. **Grano y fan-out (ajuste 3):**
   - Toda métrica declara su concepto base y su grano (`grainKey`). La medida debe pertenecer al concepto
     base (o a conceptos alcanzados por MANY_TO_ONE/ONE_TO_ONE, funcionalmente dependientes del grano).
   - Un camino hacia una dimensión es seguro solo si **todos** sus saltos van en sentido MANY_TO_ONE o
     ONE_TO_ONE desde el concepto base: el grano de la métrica se conserva.
   - Cualquier salto uno-a-muchos ⇒ `FANOUT_RISK` y la solicitud se rechaza, **sea cual sea la
     agregación**. COUNT_DISTINCT, MIN o MAX no se consideran seguros por sí mismos: aunque no dupliquen
     valores, agrupar o filtrar por el lado "muchos" cambia la semántica (pertenencia existencial, filas en
     varios grupos, totales no sumables) y los joins externos alteran el universo de filas.
   - En el futuro, una métrica podrá declarar explícitamente una semántica aprobada para un camino concreto
     (p. ej. "pedidos que contienen al menos una línea de la categoría X"); hasta entonces se bloquea.
   - Varios caminos mínimos ⇒ `AMBIGUOUS_PATH`, salvo `viaRelationship`/`role` explícito.
5. **Métricas con conceptos base distintos** ⇒ `metricGroups` independientes combinados por dimensiones
   compartidas (drill-across); nunca join directo entre hechos.
6. **Unidades (ajuste 9):** una métrica monetaria con moneda por columna solo se resuelve si la solicitud
   agrupa por la dimensión de moneda o filtra por igualdad a una única moneda; en otro caso
   `MIXED_CURRENCY` y la solicitud es UNRESOLVABLE. No hay conversión de divisas en el MVP; cuando exista,
   deberá ser explícita (tasa, fecha y fuente versionadas). Métricas de monedas fijas distintas no se
   combinan en una misma expresión.
7. Otras comprobaciones: granularidad permitida, `SEMI_ADDITIVE` con dimensión temporal ⇒ no soportado en el
   MVP, tipo de los valores de filtro, `allowedDimensions`.
8. **Reproducibilidad (ajuste 4):** cada resolución RESOLVED se persiste como `semantic_plan` inmutable con
   `catalogRevision`, `planHash` y la lista completa de **dependencias versionadas**: cada
   `DefinitionVersion` usada (métricas, reglas obligatorias, dimensiones, relaciones, conceptos) con su
   `contentHash`, y cada objeto físico con su clave natural, `attributes_hash` y snapshot vigente. El plan es
   autocontenido: Reports guarda el `planId` y puede recuperarlo (`GET /resolutions/{id}`) o reproducirlo
   (`replay`): misma solicitud + misma revisión ⇒ mismo `planHash`; si un objeto físico cambió desde entonces,
   la respuesta lo indica explícitamente.

---

## 8. Seguridad, auditoría y versionado

### 8.1 Autenticación y autorización (S1, ajuste 6)

- **OAuth2 Resource Server con JWT de cualquier proveedor OIDC**, configurado por propiedades estándar de
  Spring Boot. **`issuer-uri` es obligatorio** (hallazgo I4): sin él la aplicación no arranca, y el emisor
  (`iss`) se valida siempre mediante un validador adicional que Spring Boot aplica a cualquier decodificador.
  Modos soportados: `issuer-uri` con discovery OIDC (recomendado) o `issuer-uri` + `jwk-set-uri`. También se
  validan firma, caducidad y audiencia (`audiences`). Keycloak se incluye **solo** en `docker/` como opción de
  desarrollo.
- Claims (nombres configurables en `nexora.semantic.security.*`; decisión D2):

| Claim (defecto) | Contenido | Ausente o inválido |
|---|---|---|
| `sub` | identificador del principal (≤ 255, sin caracteres de control) | `401` |
| `principal_type` | exactamente `HUMAN`, `SERVICE` o `AI` (sensible a mayúsculas) | `401`: nunca se asume un tipo |
| `tenant` | **UUID del tenant** en forma canónica `8-4-4-4-12` (se admiten mayúsculas) | malformado ⇒ `401`; ausente ⇒ `401`, salvo tokens con rol `PLATFORM_ADMIN` |
| `roles` | lista de roles Nexora | sin permisos (`403` en cada endpoint) |

- Único caso sin tenant: los administradores de plataforma (`PLATFORM_ADMIN`) gestionan tenants y no pertenecen
  a ninguno. Su token se acepta sin claim `tenant`, pero cualquier endpoint de tenant responde
  `403 tenant_access_denied`.
- **Alta de tenants (D2):** primero se registra el tenant (`POST /api/v1/admin/tenants`), que devuelve su UUID;
  después ese UUID se configura en el proveedor OIDC como claim `tenant` de sus usuarios y clientes. La `key`
  se conserva como identificador legible, pero no se acepta en el token.

- Roles → permisos (las autoridades de Spring son los permisos, no los roles):

| Permiso | VIEWER | EDITOR | APPROVER | STEWARD | AUDITOR | REPORTS_CLIENT | AI_AGENT | PLATFORM_ADMIN |
|---|---|---|---|---|---|---|---|---|
| `semantic:catalog:read` | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | |
| `semantic:proposal:write` | | ✅ | ✅ | | | | ✅ (solo DRAFT AI) | |
| `semantic:proposal:approve` | | | ✅ | | | | | |
| `semantic:source:manage` | | | | ✅ | | | | |
| `semantic:audit:read` | | | | | ✅ | | | |
| `semantic:resolve` | | | | | | ✅ | | |
| `platform:tenant:manage` | | | | | | | | ✅ |

- Defensa en profundidad: un principal **AI** solo puede recibir `catalog:read` y `proposal:write`; un
  principal **SERVICE** o **AI** nunca recibe `proposal:approve`, sean cuales sean sus roles.
- El UUID del token se comprueba en cada petición contra la tabla `tenant`: inexistente o SUSPENDED ⇒
  `403 tenant_access_denied` (mismo mensaje en ambos casos). Recursos de otro tenant ⇒ `404`.

### 8.2 Aprobación humana (ajuste 7)

Invariantes **no configurables**, implementados en el dominio (`ApprovalPolicy`, S3):

1. Solo aprueba, rechaza o envía un principal `HUMAN` con `semantic:proposal:approve` / `proposal:write`.
2. El aprobador no puede ser el creador, quien envió ni autor de ninguna revisión de la propuesta.
3. **No existe aprobación automática**; ninguna política puede delegar la aprobación en IA ni en servicios.

Políticas empresariales configurables por tenant (solo pueden **añadir** restricciones, nunca relajar los
invariantes): número mínimo de aprobadores, restricción por tipo de definición o fuente. El MVP entrega el
punto de extensión y la política por defecto (1 aprobador humano distinto del autor).

### 8.3 Aislamiento multiempresa (ajuste 1)

1. Tenant del token, nunca del cliente.
2. Todos los métodos de repositorio exigen `TenantId` y filtran por `tenant_id`.
3. Claves foráneas compuestas `(tenant_id, id)` en todas las relaciones hijas.
4. Pruebas con dos tenants por cada endpoint y repositorio.
5. (S5, D8) Row-Level Security como capa adicional.

### 8.4 Contenido no confiable y datos de clientes

- Nombres, comentarios y evidencias son **datos**: longitud limitada, sin caracteres de control, nunca
  concatenados en SQL ni usados como instrucciones para un LLM.
- Semantic **no tiene conexión** con bases de clientes. No existen campos de credenciales (verificado por ArchUnit).
- Visibilidad de catálogo ≠ permiso de lectura de datos: el Motor de consultas revalida cada plan.

### 8.5 Auditoría (S1)

- `audit_event` se escribe en **la misma transacción** que el cambio: si la auditoría falla, la operación
  completa se revierte (verificado por `AuditAtomicityIntegrationTest`, hallazgo I5).
- Append-only: el rol de aplicación solo tiene `INSERT, SELECT`; triggers bloquean `UPDATE`, `DELETE` y `TRUNCATE`.
- Cada evento registra actor, tipo de actor, acción, objetivo, `correlationId` generado por el servidor (nunca
  el aportado por el cliente) y detalles sin secretos ni valores de negocio.
- Acciones S1: `TENANT_REGISTERED`, `TENANT_UPDATED`, `TENANT_SUSPENDED`, `TENANT_REACTIVATED`,
  `DATA_SOURCE_REGISTERED`, `DATA_SOURCE_UPDATED`, `DATA_SOURCE_ARCHIVED`, `DATA_SOURCE_REACTIVATED`.

### 8.6 Despliegue y migraciones (hallazgo I3, decisión D15)

**Problema:** si Flyway corre al arrancar, el proceso de la aplicación necesita credenciales de propietario del
esquema (DDL). Una vulnerabilidad en la aplicación expondría entonces la capacidad de alterar o destruir la
estructura de la base de datos.

**Decisión:** en producción, la aplicación y las migraciones son **procesos e identidades distintos**.

| Entorno | Migraciones | Identidad de la aplicación |
|---|---|---|
| Desarrollo y pruebas (perfil por defecto, `dev`, Testcontainers) | Flyway al arrancar, con la identidad propietaria (`spring.flyway.user`). Sin cambios | Rol DML |
| Producción (perfil `prod`) | **Job independiente** `SchemaMigrationMain`, ejecutado antes de desplegar cada versión | Solo el rol DML; `spring.flyway.enabled=false` |

**Identidades de producción** (`deploy/postgres/create-roles.sql`, sin contraseñas):

| Rol | Atributos | Privilegios | Lo usa |
|---|---|---|---|
| migrador (`nexora_semantic_migrator`) | LOGIN, sin SUPERUSER/CREATEDB/CREATEROLE/BYPASSRLS | `CONNECT`; propietario del esquema `semantic` (lo crea el administrador a su nombre, así no necesita `CREATE` sobre la base) | Solo el job de migraciones |
| aplicación (`nexora_semantic_app`) | LOGIN, sin atributos especiales | `CONNECT`; `USAGE` del esquema; `SELECT/INSERT` y `UPDATE` por columna según V1–V4; sin `DELETE`, `TRUNCATE`, `TRIGGER`, `CREATE` ni propiedad de objetos | Solo la aplicación |

**Job de migraciones:** `SchemaMigrationMain` va en el **mismo JAR** que la aplicación y se ejecuta sin contexto
Spring con `PropertiesLauncher`. Así aplica exactamente las migraciones y la versión de Flyway de esa versión de
la aplicación, sin herramientas ni imágenes adicionales. Su configuración replica `spring.flyway.*` (esquema,
ubicación, marcador `appRole`), y una prueba compara versión a versión y checksum a checksum el historial que
produce con el de desarrollo y pruebas.
- Configuración solo por variables de entorno: `SEMANTIC_MIGRATION_DB_URL` (sin credenciales en la URL),
  `SEMANTIC_MIGRATION_DB_USER`, `SEMANTIC_MIGRATION_DB_PASSWORD_FILE` (recomendado: secreto montado) o
  `SEMANTIC_MIGRATION_DB_PASSWORD`, y `SEMANTIC_DB_APP_USER` (validado como identificador; distinto del migrador).
- Comandos: `migrate` (por defecto) y `validate`. Los destructivos (`clean`, `repair`, `baseline`) no existen, y
  `cleanDisabled=true`.
- Códigos de salida: `0` éxito, `1` fallo de migración o validación, `2` configuración inválida. Nunca imprime
  secretos.

**Salvaguarda en el arranque de producción** (`nexora.semantic.database.enforce-least-privilege=true` en `prod`).
La aplicación no arranca si:
1. Flyway está habilitado o hay credenciales de propietario configuradas (`spring.flyway.password` o
   `spring.flyway.url`). Se comprueba antes de crear cualquier bean, así que Flyway nunca llega a ejecutarse.
2. Su rol puede alterar la estructura (`LeastPrivilegeVerifier`, incluyendo privilegios heredados): SUPERUSER,
   CREATEROLE, CREATEDB, BYPASSRLS, propiedad del esquema, tablas o funciones, `CREATE` en la base, en `semantic`
   o en `public`, `TRIGGER` o `TRUNCATE` en tablas.

**Procedimiento de despliegue:** (1) una sola vez, crear los roles con `create-roles.sql` y asignar contraseñas
desde el gestor de secretos; (2) en cada versión, ejecutar el job `migrate` con la identidad migradora; (3)
desplegar la aplicación con `SPRING_PROFILES_ACTIVE=prod` y solo las credenciales del rol de aplicación. El
detalle operativo está en el README.

### 8.7 Versionado

- `definition_version` inmutable, `versionNo` creciente, `contentHash`.
- Revisión de catálogo por fuente; `asOfRevision` reconstruye el catálogo vigente en cualquier revisión.
- Planes persistidos con dependencias versionadas (§7.8).
- Bloqueo optimista con `row_version` + ETag en todos los agregados mutables.
- `specVersion` en los specs para migraciones (upcasters).

---

## 9. Integraciones

### 9.1 Discovery (S2)

- **Contrato observado** (Discovery 0.1.0-SNAPSHOT, respuesta 200 de `POST /api/discovery`): `database`,
  `engine`, `productVersion`, `schemas[].tables[]` (columns, primaryKey, foreignKeys), `views[]`,
  `routines[]`. Se descartan `definition` y `defaultValue`. Lectura tolerante.
- **Contrato PROVISIONAL (propuesto por Semantic, no existe en Discovery):** sobre de importación

```json
{ "contractVersion": "discovery-api/0.1", "discoveredAt": "2026-10-19T08:00:00Z",
  "requestedSchemas": ["sales", "inventory"], "payload": { "...respuesta 200 de Discovery..." : "" } }
```

- Puertos: `DiscoveredMetadataReader` (MVP: payload → modelo propio `DiscoveredDatabase`);
  `DiscoveryClientPort` (futuro, solo con referencia a credenciales externa; Semantic nunca recibe contraseñas).
- Peticiones al equipo de Discovery (no bloqueantes): versionar la ruta, devolver esquemas solicitados y
  omitidos, exponer constraints `UNIQUE`, añadir autenticación.

### 9.2 Qwen (S5)

`InterpretationSuggestionPort.suggest(SuggestionContext) → SuggestionResult`.

- Contexto: tokens opacos (`o17`) en lugar de UUID, nombres, tipos, PK/FK y comentarios marcados como datos no
  confiables. Sin tenant, SQL, filas de datos ni credenciales.
- Resultado: sugerencias tipadas con confianza, razonamiento y evidencias por token, más modelo/versión/prompt.
- Salvaguardas en Semantic: salida JSON con esquema; tokens desconocidos ⇒ descarte; mismo `SpecValidator`
  que las personas; propuestas solo en **DRAFT** con `origin=AI`; el modelo no tiene herramientas.
- La interpretación de lenguaje natural pertenece a Nexora Intelligence.

---

## 10. Decisiones

| ID | Decisión | Estado | Justificación |
|---|---|---|---|
| D1 | Metadatos de Discovery por **importación del JSON** con sobre provisional; Semantic no almacena ni transmite credenciales | **Aprobada explícitamente** | Semantic nunca ve contraseñas; Discovery no tiene autenticación ni estado. |
| D2 | OAuth2/OIDC configurable; Keycloak solo para desarrollo; claim `tenant` con el **UUID** del tenant; `principal_type` obligatorio; emisor obligatorio; primero se registra el tenant y después se configura en el proveedor | **Aprobada explícitamente** — implementada. Pendiente: proveedor de producción | Independencia del proveedor; un token nunca obtiene tipo ni tenant por defecto. |
| D3 | Aprobación humana obligatoria; separación autor/aprobador; políticas empresariales que solo añaden restricciones; nunca aprobación automática ni por IA | **Aprobada** (ajuste 7). Pendiente: primera política configurable a implementar | Gobierno de definiciones que alimentan cifras de negocio. |
| D4 | Definiciones con ámbito **por fuente de datos** en el MVP | **Aprobada** (aprobación general) | Simplicidad; conceptos transversales cuando haya casos reales. |
| D5 | Referencias entre definiciones por identidad + revisión de catálogo; **planes persistidos con todas las versiones y hashes de dependencias** | **Aprobada con ajuste 4** | Reproducibilidad histórica completa sin fijar versiones en cada definición. |
| D6 | `JdbcClient` + Flyway; FK compuestas por tenant | **Aprobada** (ajuste 8) | SQL explícito, filtro de tenant visible, sin ORM. |
| D7 | `spring-tx` (`@Transactional`) permitido en `application`; ninguna otra dependencia de Spring | **Aprobada** (aprobación general) — implementada en S1 | Fronteras transaccionales en los casos de uso, controladas por ArchUnit. |
| D8 | Row-Level Security en S5 | **Aprobada** (aprobación general) — reevaluar en S5 | En el MVP: filtro en aplicación + FK compuestas + pruebas. |
| D9 | No almacenar SQL de vistas/rutinas | **Aprobada** (aprobación general) | Minimización de datos sensibles. |
| D10 | Mantener temporalmente Spring Boot **3.5.16** y **migrar a Spring Boot 4.1.1** en una tarea separada, que requiere autorización propia | **Aprobada e implementada** (Spring Boot 4.1.1, Java 21) | Verificado: la nota oficial de 3.5.16 la declara la última versión OSS de 3.5.x (soporte OSS hasta el 30-jun-2026; comercial hasta 2032). 4.1.x tiene soporte OSS hasta el 31-jul-2027 y es compatible con Java 21; 4.0.x termina el 31-dic-2026. Discovery usa 3.3.5 (sin soporte). **Implementación (§13, CHANGELOG):** starters modulares de Boot 4, Jackson 3 (`tools.jackson`), Spring Framework 7.0.9, Spring Security 7.1.1, Flyway 12.4.0, Testcontainers 2.0.5, JUnit 6.0.3. Sin cambios en reglas de negocio, contratos REST, V1–V4 ni esquema. |
| D11 | Sin métricas derivadas en el MVP (solo `SIMPLE` con expresiones intra-fila) | **Aprobada** (ajuste 10) | Prioridad MVP de Reports. |
| D12 | Alta de tenants por `/api/v1/admin/tenants` con `platform:tenant:manage` | **Aprobada** (aprobación general) — implementada en S1 | Necesaria para operar y probar el aislamiento; sin autoaprovisionamiento desde claims. |
| D13 | **Bloquear** agregaciones que mezclen monedas sin separación o conversión explícita | **Aprobada** (ajuste 9) | Evita cifras monetarias incorrectas. |
| D14 | Zona horaria: valor por tenant, sobrescribible por dimensión | **Pendiente** (necesaria en S3) | Afecta a la granularidad temporal de dimensiones. |
| D15 | En producción, migraciones con un **job independiente** (`SchemaMigrationMain`, mismo JAR, identidad migradora propia) y aplicación con perfil `prod`: Flyway deshabilitado y verificación de mínimo privilegio al arrancar (§8.6) | **Aprobada** (requisitos de I3). Mecanismo concreto (JAR + `PropertiesLauncher`) **pendiente de confirmación** | Las credenciales DDL nunca llegan al proceso de la aplicación. Mismo artefacto ⇒ mismas migraciones y versión de Flyway. Alternativa descartada por ahora: CLI o imagen oficial de Flyway (otra herramienta y otra versión que mantener sincronizadas). Tras D10 se verificó con Spring Boot 4.1.1: `org.springframework.boot.loader.launch.PropertiesLauncher` sigue en el JAR y el job funciona desde el artefacto empaquetado (Flyway 12.4.0). |

---

## 11. Riesgos

| ID | Riesgo | Mitigación |
|---|---|---|
| R1 | Identificadores sensibles a mayúsculas (H7) | Comparación exacta; avisar a Discovery. |
| R2 | Snapshot parcial o pérdida de privilegios interpretados como borrado | Diff por alcance, `SCHEMA_UNAVAILABLE`, importación todo-o-nada. |
| R3 | Renombrados rompen definiciones | BROKEN visible; reasignación manual. |
| R4 | Cardinalidad mal declarada ⇒ duplicación | Aserción del aprobador; rechazo de todo salto uno-a-muchos. |
| R5 | Vistas sin PK/FK | `grainKey` obligatorio; aviso "grano declarado". |
| R6 | Evolución del JSONB de specs | `specVersion` + upcasters. |
| R7 | Inyección de instrucciones / alucinaciones del LLM | Tokens opacos, validación estricta, solo DRAFT, corpus de ataques. |
| R8 | Esquemas grandes | Límite de payload, inserciones por lotes. |
| R9 | Publicaciones concurrentes | Bloqueo por fuente en `catalog_state`. |
| R10 | Payload importado falsificado | Sin ejecución en Semantic; revalidación en el Motor de consultas; auditoría del importador. |
| R11 | Visibilidad de catálogo confundida con permiso de datos | Autorización de datos en el Motor de consultas. |
| R12 | Spring Boot 3.5 sin soporte OSS (D10) | **Mitigado**: migrado a 4.1.1 (soporte OSS hasta el 31-jul-2027). Planificar la siguiente actualización antes de esa fecha. |
| R13 | Cambios silenciosos de configuración y serialización entre versiones mayores (encontrados en D10: claves `server.error.*` ignoradas y `type` de RFC 7807 omitido) | Verificar en cada actualización mayor con `spring-boot-properties-migrator` y pruebas de contrato de las respuestas (añadidas). |

---

## 12. MVP frente a futuro

| MVP (S1–S4 + puerto IA en S5) | Futuro |
|---|---|
| Tenants, fuentes, auditoría, seguridad | Llamada directa a Discovery con referencia a credenciales |
| Importación de snapshots, referencias estables, cambios, salud | Sugerencia asistida de renombrados |
| CONCEPT, DIMENSION, METRIC `SIMPLE`, RELATIONSHIP, RULE, sinónimos | Métricas derivadas y semiaditivas; conceptos multi-fuente |
| Propuestas, aprobación humana con separación de funciones | Políticas multi-aprobador, comentarios, notificaciones |
| Resolución con grano, fan-out, monedas y ambigüedad; planes reproducibles | Semántica existencial aprobada, preagregación, conversión de divisas |
| Puerto IA + stub | Adaptador Qwen en producción y evaluación de precisión |
| Auditoría append-only | Cadena de hashes, SIEM, particionado; RLS (S5) |

---

## 13. Plan de implementación

### S1 — Fundaciones ✅ (implementada)

Proyecto Maven (Java 21; Spring Boot 3.5.16 inicialmente, 4.1.1 desde D10), paquetes y reglas ArchUnit, Flyway (`tenant`, `data_source`,
`audit_event`, grants y triggers), seguridad JWT con permisos y `PrincipalType`, resolución y validación del
tenant, RFC 7807, `X-Request-Id`, endpoints de tenants (admin), fuentes y auditoría, Testcontainers,
Docker Compose de desarrollo (PostgreSQL + Keycloak).

Criterios de aceptación: `mvn verify` en verde; ArchUnit falla si `domain` usa Spring o si `api` e
`infrastructure` se acoplan; matriz rol × endpoint probada; acceso cruzado entre tenants ⇒ 404; tenant
suspendido o desconocido ⇒ 403; `audit_event` rechaza UPDATE/DELETE (grants y trigger); sin campos de
credenciales; campos desconocidos ⇒ 400; errores sin trazas.

Resultado inicial (19 oct 2026): 169 pruebas en verde. Hallazgo: en Keycloak 25+ el claim `sub` lo aporta el
scope `basic`; un realm con scopes propios necesita el mapper `oidc-sub-mapper`.

**Correcciones tras la auditoría de S1** (19 oct 2026):

| Hallazgo | Estado | Implementación |
|---|---|---|
| B1 — tenant por UUID en el JWT | ✅ | `JwtPrincipalConverter`, `AuthenticatedPrincipal`, `TenantService.resolveActiveTenant(TenantId)`, realm de desarrollo |
| B2 — `principal_type` obligatorio | ✅ | `JwtPrincipalConverter.principalType` (valor exacto; ausente ⇒ 401) |
| I1 — columnas inmutables | ✅ | `V4__protect_immutable_columns.sql` (grants por columna + triggers) |
| I2 — correlación generada en el servidor | ✅ | `CorrelationIdFilter` |
| I4 — emisor obligatorio | ✅ | `SecurityConfig.mandatoryIssuerValidator` |
| I5 — atomicidad de la auditoría | ✅ | `AuditAtomicityIntegrationTest` |
| I6 — prueba real del emisor | ✅ | Proveedor OIDC local de prueba (`TestOidcProvider`): discovery + JWKS, como en producción |
| I3 — Flyway fuera de la aplicación en producción | ✅ | `SchemaMigrationMain`/`SchemaMigrator`, `application-prod.yml`, `LeastPrivilegeConfiguration` + `LeastPrivilegeVerifier`, `deploy/postgres/create-roles.sql` (§8.6, D15). Sin cambios en V1–V4 ni migraciones nuevas |
| I7 — lectura tolerante del JSON de Discovery | Pendiente (S2) | La capa anticorrupción de S2 usará su propio lector |

Resultado tras las correcciones: `mvn clean verify` con 223 pruebas en verde. Verificación manual contra
Keycloak 26.4: alta del tenant → configuración del UUID en el proveedor → acceso, aislamiento entre tenants y
`X-Request-Id` del cliente ignorado.

Resultado tras I3: `mvn clean verify` con 278 pruebas en verde. Verificación manual sobre un PostgreSQL limpio:
`create-roles.sql` con psql → job `migrate`/`validate` desde el JAR → aplicación `prod` en marcha sin ejecutar
Flyway; arranque rechazado con la identidad migradora o con credenciales de propietario en el entorno.

**Migración D10 a Spring Boot 4.1.1** (19 oct 2026). Basada en la guía oficial de migración a 4.0 y en las notas
de 4.1:

| Área | Cambio |
|---|---|
| Starters | `spring-boot-starter-web` → `-webmvc`; `-oauth2-resource-server` → `-security-oauth2-resource-server`; `flyway-core` → `spring-boot-starter-flyway` (+ `flyway-database-postgresql`); pruebas con `spring-boot-starter-webmvc-test` y `-security-oauth2-resource-server-test` |
| Jackson 3 | `com.fasterxml.jackson.*` → `tools.jackson.*`; `JsonProcessingException` → `JacksonException` (no comprobada); `asText()` → `asString()` en pruebas |
| Paquetes de Boot | `OAuth2ResourceServerProperties`, `FlywayMigrationInitializer`, `@AutoConfigureMockMvc` |
| Spring Framework 7 | `org.springframework.lang.@NonNull/@Nullable` → JSpecify; `HttpStatus.PAYLOAD_TOO_LARGE` → `CONTENT_TOO_LARGE` (el código de la API sigue siendo `payload_too_large`) |
| Testcontainers 2 | `testcontainers-postgresql` / `-junit-jupiter`; `org.testcontainers.postgresql.PostgreSQLContainer` (no genérico) |
| Configuración | `server.error.*` → `spring.web.error.*`. Boot 4 ignoraba las claves antiguas; los valores efectivos coincidían con los predeterminados, así que no hubo exposición |
| RFC 7807 | Spring Framework 7 no inicializa `type` a `about:blank` y el campo desaparecía de las respuestas; `Problems` lo fija explícitamente y hay pruebas de regresión |

Resultado: `mvn clean verify` con 278 pruebas en verde; informe de `spring-boot-properties-migrator` limpio
(retirado después). Verificación manual con el JAR 4.1.1: job `migrate`/`validate` sobre un PostgreSQL limpio con
`create-roles.sql`, perfil `prod` sin Flyway y con rechazos de arranque, y perfil `dev` contra Keycloak real
(alta de tenants, JWT, permisos, aislamiento, ETags, auditoría). Diferencia no contractual: Jackson 3 ordena
alfabéticamente las propiedades JSON.

### S2 — Modelo físico e ingesta de Discovery

ACL del contrato Discovery 0.1 + sobre provisional, `DiscoveredMetadataReader`, normalización a
`physical_object`, `TypeCategoryMapper` PostgreSQL, `ChangeDetector` por alcance, `schema_change`,
endpoints de snapshots/objetos/cambios, límites de tamaño, descarte de `definition`.

Criterios: importar el ejemplo de `API.md` y una captura del esquema de pruebas de Discovery; reimportar
idéntico ⇒ `DUPLICATE` sin cambios; columna eliminada ⇒ `OBJECT_REMOVED` y MISSING; reaparición ⇒ mismo UUID;
**snapshot parcial no marca objetos fuera de alcance**; esquema solicitado ausente ⇒ `SCHEMA_UNAVAILABLE`
sin marcar hijos; payload inválido ⇒ ningún cambio persistido; `Orders` ≠ `orders`; comentario malicioso
inerte y truncado; 413/422; ningún `definition` persistido; FK compuestas entre snapshot, objeto y fuente.

### S3 — Catálogo y flujo de propuestas

Specs tipados y expresiones, `SpecValidator`, `Proposal` con estados (incluida la vuelta a DRAFT al editar en
revisión), revisiones y evidencias, `ApprovalPolicy` (invariantes + punto de extensión de políticas),
publicación transaccional con `catalog_state`, dependencias, sinónimos, ETags, DEPRECATE/RETIRE.

Criterios: matriz completa de transiciones; **edición en IN_REVIEW ⇒ DRAFT, envío anulado y nuevo submit
requerido**; autoaprobación y aprobación por quien editó ⇒ 403; principal AI o SERVICE con rol APPROVER ⇒
403; una política configurada no puede relajar invariantes; revisión distinta ⇒ 409; base desactualizada ⇒
409; referencias rotas ⇒ 422; `definition_version` inmutable; auditoría por acción; escenario "ventas"
manual completo.

### S4 — Resolución semántica, reproducibilidad e impacto

`SemanticRequest`/`ResolvedPlan`, `TermResolver`, `EntityGraph`, `JoinPathFinder`, `GrainAnalyzer`,
`FanoutAnalyzer`, `UnitAnalyzer`, persistencia de `semantic_plan` con dependencias, `replay`,
`asOfRevision`, `ChangeImpactService` y salud.

Criterios: estrella y copo de nieve resueltos; rol ambiguo ⇒ `AMBIGUOUS_PATH`; **cualquier salto
uno-a-muchos ⇒ `FANOUT_RISK` también con COUNT_DISTINCT, MIN y MAX**; medida fuera del grano base ⇒
rechazo; bases distintas ⇒ varios `metricGroups`; **moneda por columna sin agrupar ni filtrar ⇒
`MIXED_CURRENCY`**; "ventas mensuales 2026" igual al JSON de referencia; término ambiguo ⇒ AMBIGUOUS;
columna eliminada ⇒ BROKEN ⇒ UNRESOLVABLE; **plan persistido contiene todas las versiones y hashes**;
`replay` tras publicar nuevas versiones reproduce el mismo `planHash`; cambios físicos posteriores se
señalan.

### S5 — Integración IA y endurecimiento

`InterpretationSuggestionPort`, constructor de contexto con tokens, validador de salida, `suggestion_run`
asíncrono, adaptador Qwen/Ollama tras flag, límites por tenant, RLS (D8), observabilidad.

Criterios: referencias alucinadas descartadas y contadas; corpus de inyección nunca produce más que
propuestas DRAFT; la IA no puede enviar ni aprobar; timeout ⇒ run FAILED sin propuestas parciales; IA
desactivada ⇒ 503; con RLS una consulta sin filtro no devuelve filas de otro tenant.

### Estrategia de pruebas (transversal)

Unitarias de dominio sin Spring (≥ 90 % de líneas en `domain`), aplicación con dobles en memoria,
integración con Testcontainers PostgreSQL + Flyway usando el rol de aplicación real, API con MockMvc y JWT
firmados reales, contrato con fixtures de Discovery, seguridad (inyección, IDs de otro tenant, payloads
grandes, identificadores hostiles), ArchUnit y aceptación de extremo a extremo.
