# Nexora Semantic Engine — Estado del proyecto

> **Fuente de verdad del estado actual.** Arquitectura y decisiones: [ARCHITECTURE.md](ARCHITECTURE.md).
> Historial: [CHANGELOG.md](CHANGELOG.md).

| Campo | Valor |
|---|---|
| Última actualización | 20 oct 2026 |
| Versión del artefacto | `0.1.0-SNAPSHOT` |
| Etapa actual | **S4.2** — planificación lógica segura con planes inmutables: implementada y probada; pendiente de revisión |
| Próxima tarea autorizada | **Ninguna.** Cualquier etapa posterior (Reports, Intelligence, estrategias avanzadas) requiere autorización expresa |
| Objetivo global | Prueba de extremo a extremo para Wellnode: Discovery → Semantic → interpretación (Qwen/Ollama) → consulta SQL segura → Excel |

Leyenda: **Implementado** = código presente · **Probado** = cubierto por pruebas automáticas que pasan ·
**Verificado manualmente** = comprobado además fuera de las pruebas · **Pendiente** = no iniciado o incompleto ·
**Bloqueado** = no puede avanzar sin una decisión o autorización.

## Regla de mantenimiento

Al terminar **cada** bloque de desarrollo futuro se actualizan, en el mismo bloque:

1. este archivo (estado, pruebas, problemas abiertos, decisiones, próxima tarea autorizada);
2. [CHANGELOG.md](CHANGELOG.md), con una entrada cronológica que incluya el resultado real de `mvn clean verify`;
3. [ARCHITECTURE.md](ARCHITECTURE.md), si cambia alguna decisión o el diseño.

No se registran fechas, cifras ni resultados que no se hayan comprobado.

## Etapas

| Etapa | Estado | Notas |
|---|---|---|
| S0 — Diseño arquitectónico | **Aprobada** con 10 ajustes | `ARCHITECTURE.md` v1.0 |
| S1 — Fundaciones | **Completada y aceptada** | Correcciones B1, B2 e I1–I6; Spring Boot 4.1.1 (D10) |
| S2 Basic — Importación de metadatos de Discovery | **Completada y aceptada** | I7 resuelto; respuestas reales de Discovery |
| S3 Basic — Catálogo semántico mínimo | **Completada y aceptada** | D14 resuelta (mínima); D19–D21 |
| S3.1 — Catálogo de relaciones semánticas | **Implementado y probado**; S4.1 se autorizó sobre esta base | V7; D22–D27 |
| S2 completo / S3 completo | **Pendiente / bloqueado** (sin autorización) | Rutinas, `schema_change`; reglas, sinónimos, propuestas y versiones |
| S4.1 — Resolución semántica | **Implementada y probada**; S4.2 se autorizó sobre esta base | Sin migración; D28–D32 |
| S4.2 — Planificación lógica segura | **Implementada y probada**; pendiente de revisión | V8; D33–D40 |
| Posteriores (Reports, Intelligence, drill-across, unidades) | **Bloqueadas** | Requieren autorización expresa |
| S5 — IA (Qwen) y endurecimiento | **Bloqueada** | Requiere autorización expresa para la IA |

## Funcionalidades

| Funcionalidad | Implementado | Probado | Verificado manualmente |
|---|---|---|---|
| Tenants, fuentes de datos y consulta de auditoría | ✅ | ✅ | ✅ Keycloak 26.4 |
| OAuth2/JWT (`issuer-uri` obligatorio, `tenant` UUID, `principal_type` obligatorio) y aislamiento multiempresa | ✅ | ✅ | ✅ |
| Auditoría append-only en la misma transacción; errores RFC 7807 | ✅ | ✅ | ✅ |
| Job de migraciones de producción; perfil `prod` de mínimo privilegio | ✅ | ✅ | ✅ |
| Importación de metadatos de Discovery (esquemas, tablas, vistas, columnas, PK, FK), conciliación sin borrados | ✅ | ✅ | ✅ (fixtures reales) |
| **S3:** CONCEPT sobre tabla, vista o vista materializada | ✅ | ✅ | — |
| **S3:** METRIC COUNT (filas o columna), SUM y AVG (solo numéricas) | ✅ | ✅ | — |
| **S3:** DIMENSION CATEGORICAL y TEMPORAL (granularidades DAY…YEAR; zona IANA para `timestamptz`, `UTC` por defecto) | ✅ | ✅ | — |
| **S3:** referencias por id persistente de S2, validadas por tenant y fuente (aplicación y FK compuestas) | ✅ | ✅ | — |
| **S3:** ciclo DRAFT → APPROVED → INACTIVE; edición ⇒ DRAFT; aprobación humana con separación autor/aprobador | ✅ | ✅ | — |
| **S3:** invalidación automática de definiciones aprobadas al importar (`MISSING` o tipo incompatible), sin reactivación automática | ✅ | ✅ | — |
| **S3:** `usable` e `issues` evaluados contra los metadatos actuales en cada respuesta | ✅ | ✅ | — |
| **S3.1:** relaciones entre tablas o vistas de la misma fuente, con uno o varios pares ordenados de columnas (también entre esquemas), sin presuponer nombres, `id`, PK ni FK | ✅ | ✅ | — |
| **S3.1:** creación desde FK por clave natural exacta (DRAFT, evidencia de FK; no resolubles ⇒ 422 con motivo) y relaciones SEMANTIC | ✅ | ✅ | — |
| **S3.1:** validación física (existencia, lado, `MISSING`, tipos compatibles, pares repetidos o contradictorios, equivalentes) | ✅ | ✅ | — |
| **S3.1:** pares inmutables; edición de campos descriptivos con `If-Match` ⇒ DRAFT; sustitución controlada con `replacesId` | ✅ | ✅ | — |
| **S3.1:** cardinalidad declarada separada de la evidencia (PK exacta, FK, riesgo de multiplicación de filas) | ✅ | ✅ | — |
| **S3.1:** invalidación al importar (`MISSING`, columna, tipo, pérdida de FK), idempotente y sin reactivación | ✅ | ✅ | — |
| **S3.1:** candidatas deterministas bajo demanda (FK, nombres, tipos, comentarios), nunca guardadas | ✅ | ✅ | — |
| **S3.1:** contrato tipado `RelationshipContract` para S4 (`GET …/contract`) | ✅ | ✅ | — |
| **S4.1:** `POST …/semantic-resolutions`: métricas, dimensiones y filtros por id persistente; solo definiciones APPROVED con vinculación física válida | ✅ | ✅ | — |
| **S4.1:** filtros tipados (`EQ`, `NE`, `GT`, `GTE`, `LT`, `LTE`, `IN`, `IS_NULL`, `IS_NOT_NULL`) antes o después de la agregación, validados por tipo | ✅ | ✅ | — |
| **S4.1:** diagnósticos tipados (422) sin revelar recursos ajenos; relaciones preferidas validadas sin elegir ruta | ✅ | ✅ | — |
| **S4.1:** resultado inmutable con dependencias físicas, versiones, snapshot y huella; una sola instantánea `REPEATABLE READ` | ✅ | ✅ | — |
| **S4.2:** `POST …/logical-plans`: plan lógico tipado y determinista (objeto base, grano, JOIN con pares exactos, filtros antes y después de agregar, operaciones, evidencia, huella) | ✅ | ✅ | — |
| **S4.2:** rutas solo por relaciones aprobadas y utilizables; ambigüedad sin elección automática; relaciones preferidas | ✅ | ✅ | — |
| **S4.2:** JOIN solo hacia PK exacta; rechazo de fan-out, unicidad no demostrada, granos incompatibles, LEFT invertido o seguido de INNER y filtros inseguros | ✅ | ✅ | — |
| **S4.2:** planes inmutables y autocontenidos (V8), auditoría de creación, consulta histórica y vigencia derivada (`CURRENT` / `CURRENT_WITH_CHANGES` / `STALE`) | ✅ | ✅ | — |

## Tecnologías y versiones

| Componente | Versión |
|---|---|
| Java | 21 (JDK 21.0.6) |
| Spring Boot / Framework / Security | 4.1.1 / 7.0.9 / 7.1.1 |
| Jackson | 3.1.5 (`tools.jackson`) |
| Flyway | 12.4.0 (+ `flyway-database-postgresql`) |
| PostgreSQL (pruebas y desarrollo) / driver | 17.6 / 42.7.13 |
| JUnit / Mockito / Testcontainers / ArchUnit | 6.0.3 / 5.23.0 / 2.0.5 / 1.4.2 |
| Keycloak (solo desarrollo; Wellnode lo usa como proveedor OIDC) | 26.4.0 |

## Base de datos

| Migración | Contenido | Estado |
|---|---|---|
| V1–V4 | `tenant`, `data_source`, `audit_event`, columnas inmutables | Aplicadas y probadas |
| V5 `create_physical_metadata` | `metadata_snapshot`, `physical_object` | Aplicada y probada |
| V6 `create_semantic_catalog` | `semantic_definition`: referencias tipadas con FK compuestas `(tenant_id, data_source_id, …)` a objetos físicos y conceptos; checks de forma y ciclo de vida; inmutabilidad; sin DELETE | Aplicada y probada |
| V7 `create_semantic_relationships` | `semantic_relationship` (extremos, FK de respaldo y sustitución con FK compuestas; firma de equivalencia con índice único parcial sobre APPROVED; checks; columnas inmutables por trigger; UPDATE solo por columnas) y `semantic_relationship_pair` (append-only: trigger + solo SELECT/INSERT) | Aplicada y probada (Flyway al arrancar, job independiente y perfil `prod`) |
| **V8 `create_logical_plans`** | `logical_plan` (documento jsonb inmutable, solicitud, huellas, snapshot, autor) y `logical_plan_dependency` (definiciones, relaciones y objetos físicos con su revisión; FK compuestas; una referencia tipada por fila). Ambas append-only (triggers + solo SELECT/INSERT) | Aplicada y probada (Flyway al arrancar, job independiente y perfil `prod`) |

Las migraciones aplicadas no se modifican: cada cambio será una migración nueva.

## Seguridad y aislamiento

- **Permisos del catálogo:**

  | Acción | Permiso | Roles |
  |---|---|---|
  | Crear y editar | `semantic:proposal:write` | EDITOR, APPROVER, AI_AGENT |
  | Aprobar y desactivar | `semantic:proposal:approve` | APPROVER (solo HUMAN) |
  | Leer | `semantic:catalog:read` | todos los roles de tenant |

  Las relaciones de S3.1 usan exactamente los mismos permisos (también para consultar candidatas y contratos).
  La resolución semántica (S4.1) usa el permiso existente `semantic:resolve` (solo `REPORTS_CLIENT`; nunca para
  principales AI); es de solo lectura y no amplía ningún rol.
  Los planes lógicos (S4.2) usan el mismo permiso para crear, leer y comprobar vigencia; crear un plan se audita
  (`LOGICAL_PLAN_CREATED`, sin valores de filtros).

- **Invariantes en el dominio** (D3, componente `ApprovalRules` compartido por definiciones y relaciones), independientes de los roles: aprueba un HUMAN distinto del autor y del
  último editor; un agente de IA nunca aprueba (403).
- **Referencias:** una referencia a un objeto o concepto de otro tenant u otra fuente da «no encontrado» (422) en
  la aplicación, y la base de datos lo impide con FK compuestas.
- **Sin SQL ni expresiones libres:** solo agregaciones y tipos de dimensión controlados, y las propiedades JSON
  desconocidas se rechazan.
- **Sin Keycloak en el dominio:** no hay dependencias de Keycloak en el dominio ni en la aplicación; los
  principales vienen de claims OIDC configurables.

## Pruebas y última verificación

| Tipo | Pruebas |
|---|---|
| Unitarias | 256 |
| Arquitectura (ArchUnit) | 9 |
| Integración (PostgreSQL 17.6, OIDC local, MockMvc, respuestas reales y sintéticas de Discovery) | 245 |
| **Total** | **510** |

Pruebas añadidas en S4.2 (35): `LogicalPlannerTest` (20, dominio puro) y `LogicalPlanApiIntegrationTest` (15,
PostgreSQL + API + JWT). Se actualizaron los recuentos de migraciones y triggers (7 → 8) en `SchemaMigrationIntegrationTest`
y `ProductionProfileIntegrationTest`; ninguna aserción anterior se eliminó ni se debilitó.

Pruebas añadidas en S4.1 (27): `SemanticResolverTest` (14, dominio puro) y `SemanticResolutionApiIntegrationTest`
(13, PostgreSQL + API + JWT). Ninguna prueba anterior se modificó.

Pruebas añadidas en S3.1 (54):

| Clase | Pruebas |
|---|---|
| `SemanticRelationshipTest` | 7 |
| `RelationshipValidatorTest` | 10 |
| `RelationshipCandidateFinderTest` | 5 |
| `SemanticRelationshipApiIntegrationTest` | 19 |
| `SemanticRelationshipDatabaseIntegrationTest` | 13 |

Además, `SchemaMigrationIntegrationTest` espera ahora 7 migraciones y los 3 triggers de V7, y
`ProductionProfileIntegrationTest` crea, aprueba y consulta el contrato de una relación desde FK en el perfil
`prod`. Los payloads sintéticos (`SyntheticCatalog`) añaden los ejemplos obligatorios que la base ERP capturada no
contiene (`measurements`/`equipment`, `invoices`/`clients`, `transactions`/`documents`).

Pruebas añadidas en S3 Basic (51):

| Clase | Pruebas |
|---|---|
| `SemanticDefinitionTest` | 14 |
| `DefinitionValidatorTest` | 10 |
| `SemanticCatalogApiIntegrationTest` | 14 |
| `SemanticCatalogDatabaseIntegrationTest` | 12 |
| `AuditAtomicityIntegrationTest` | +1 |

`ProductionProfileIntegrationTest` se amplió con importación y aprobación en el perfil `prod`.

**Última verificación** (20 oct 2026, 13:18): `mvn clean verify` ⇒ **BUILD SUCCESS, 510 pruebas, 0 fallos,
0 errores, 0 omitidas** (punto de partida de S4.2: 475).

## Problemas abiertos

| ID | Problema | Severidad | Estado |
|---|---|---|---|
| R15 | Sin versiones inmutables (D19), un plan de S4 no es reproducible con exactitud tras revisar una definición | Importante para S4 | Pendiente de decisión en S4 |
| R16 | La separación de funciones exige dos usuarios (editor y aprobador) en el Keycloak de Wellnode | Operativo | Pendiente de configuración |
| P10 | Las vistas sin PK no tienen grano declarado; S4 lo necesitará para evitar duplicados por joins | Medio | Diferido (S3 completo / S4) |
| P11 | La evaluación de `usable` e `issues` hace unas pocas consultas por página; no está medida con catálogos grandes | Menor | Pendiente |
| R17 | Una relación aprobada con unicidad `DECLARED_ONLY` (p. ej. `equipment.dbid`) puede multiplicar filas si la declaración es errónea; S4 deberá respetar `rowMultiplicationRisk` | Importante para S4 | Documentado en el contrato |
| R20 | Mitigado en S4.2: cada plan guarda copias inmutables de las definiciones y relaciones usadas; el catálogo sigue sin versiones históricas fuera de los planes | Medio | Mitigado |
| R21 | Solo `REPORTS_CLIENT` (cuentas de servicio) puede resolver; en Keycloak de Wellnode hace falta un cliente con ese rol | Operativo | Pendiente de configuración |
| R22 | Resuelto en S4.2: el planificador valida grano, fan-out y filtros antes de persistir un plan | — | Resuelto |
| R23 | La política de S4.2 rechaza casos legítimos (drill-across, rol múltiple de una tabla, vistas sin PK, unicidad por UNIQUE no informada) | Medio | Documentado |
| R24 | Un INNER JOIN autorizado excluye filas sin coincidencia (explícito en `rowPreservation`) | Menor | Documentado |
| R25 | Sin metadatos de unidades ni monedas: no se detectan mezclas de monedas | Importante para Reports | Pendiente de decisión |
| R18 | Discovery no informa de UNIQUE: claves de negocio únicas aparecen como `UNKNOWN` | Medio | Pendiente (contrato de Discovery) |
| P12 | Las heurísticas de candidatas asumen nombres en inglés (singular ingenuo) y `TypeCategory` solo cubre PostgreSQL | Menor | Documentado |
| P6 | El sobre `discovery-api/0.1` lo propone Semantic; Discovery no lo conoce (D16) | Importante | Pendiente |
| P8 / P9 | Rendimiento de la importación con esquemas grandes; conciliación en memoria | Medio | Pendiente |
| O1–O16 | Hallazgos opcionales de la auditoría de S1 | Opcional | Pendiente |
| P1 | El perfil `prod` no comprueba al arrancar la versión del esquema | Opcional | Pendiente de decisión |
| P2 | En este equipo Maven necesita `MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` | Entorno | Documentado |
| P3 | Aviso de Mockito por carga dinámica del agente Java | Menor | Pendiente |

## Decisiones pendientes

| ID | Decisión | Necesaria para |
|---|---|---|
| D2 | Proveedor OIDC de producción (Wellnode: Keycloak) | Despliegue |
| D3 | Primera política empresarial de aprobación configurable | S3 completo |
| D8 | Reevaluar Row-Level Security | S5 |
| D13 | Unidades y monedas en métricas (bloquear mezcla de monedas) | S4 |
| D15 | Confirmar el mecanismo del job de migraciones | Despliegue |
| D16 | Confirmar con Discovery el sobre `discovery-api/0.1` | S2 completo |
| R15 | Cómo garantizar la reproducibilidad de los planes sin versiones inmutables | S4 Basic |

## Funcionalidades expresamente no implementadas

- **Catálogo (S3 completo):** reglas o filtros de negocio, sinónimos, métricas derivadas, propuestas, revisiones,
  versiones inmutables, revisión de catálogo, políticas de aprobación configurables, unidades y monedas.
- **Relaciones (fuera de S3.1):** relaciones entre conceptos (decisión D22: se enlazan en S4 a través de los
  objetos físicos), relaciones entre fuentes distintas, análisis de datos para verificar unicidad o cardinalidad,
  motor de inferencia de relaciones, LLM o embeddings en las sugerencias, resolución de rutas o planes de JOIN.
- **S2 completo:** rutinas, `schema_change` y su reconocimiento, llamada directa a Discovery.
- **Posteriores a S4.2:** drill-across, preagregaciones, unidades y monedas, generación de SQL o ejecución de consultas (Semantic nunca ejecuta
  SQL empresarial), integración con Qwen/Ollama, generación de Excel, Nexora Predict y Nexora Correct.
- **Otros:** cualquier modificación de Nexora Discovery; Dockerfile, Kubernetes y CI; Row-Level Security.
