# Nexora Semantic Engine — Estado del proyecto

> **Fuente de verdad del estado actual.** Arquitectura y decisiones: [ARCHITECTURE.md](ARCHITECTURE.md).
> Historial: [CHANGELOG.md](CHANGELOG.md).

| Campo | Valor |
|---|---|
| Última actualización | 19 oct 2026 |
| Versión del artefacto | `0.1.0-SNAPSHOT` |
| Etapa actual | **S2 Basic** — importación persistente de metadatos de Discovery: implementada y verificada; pendiente de revisión |
| Próxima tarea autorizada | **Ninguna.** El resto de S2 y S3 requieren autorización expresa |

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
| S1 — Fundaciones | **Completada y aceptada** | Incluye las correcciones B1, B2 e I1–I6 y la migración D10 (Spring Boot 4.1.1) |
| S2 Basic — Importación de metadatos de Discovery | **Implementada y probada**; pendiente de revisión | I7 resuelto. Validada con respuestas reales del JAR de Discovery sobre PostgreSQL |
| S2 completo | **Pendiente / bloqueado** (sin autorización) | Rutinas, `schema_change` y reconocimiento de cambios, `TypeCategoryMapper` |
| S3 — Catálogo y propuestas | **Bloqueada** | Requiere autorización y la decisión D14 |
| S4 — Resolución semántica | **Bloqueada** | Requiere S3 |
| S5 — IA (Qwen) y endurecimiento | **Bloqueada** | Requiere S4 y autorización expresa para la IA |

## Funcionalidades

| Funcionalidad | Implementado | Probado | Verificado manualmente |
|---|---|---|---|
| Tenants: alta, consulta y suspensión (`/api/v1/admin/tenants`) | ✅ | ✅ | ✅ Keycloak 26.4 |
| Fuentes de datos: alta, listado, consulta, modificación con ETag/If-Match, archivado | ✅ | ✅ | ✅ |
| Consulta de auditoría (`/api/v1/audit-events`) | ✅ | ✅ | ✅ |
| OAuth2/JWT: `issuer-uri` obligatorio, `tenant` = UUID, `principal_type` obligatorio | ✅ | ✅ | ✅ |
| Aislamiento multiempresa (tenant del token, filtrado y FK compuestas, 404 entre tenants) | ✅ | ✅ | ✅ |
| Auditoría append-only en la misma transacción | ✅ | ✅ | ✅ |
| Errores RFC 7807 (`code`, `correlationId`, `type`); `X-Request-Id` generado por el servidor | ✅ | ✅ | ✅ |
| Migraciones de producción en un job independiente; perfil `prod` de mínimo privilegio | ✅ | ✅ | ✅ |
| **S2 Basic:** importación del sobre `discovery-api/0.1` con la respuesta de Discovery (`POST …/snapshots`) | ✅ | ✅ (fixtures reales de Discovery) | ✅ fixtures capturados del JAR de Discovery |
| **S2 Basic:** esquemas, tablas, vistas, vistas materializadas, columnas, PK y FK con ids estables | ✅ | ✅ | — |
| **S2 Basic:** conciliación por alcance (ACTIVE / MISSING / UNAVAILABLE, reaparición con el mismo id, sin borrados) | ✅ | ✅ | — |
| **S2 Basic:** historial de importaciones y consulta de objetos con filtros | ✅ | ✅ | — |
| **S2 Basic:** lectura tolerante del payload (I7) con sobre estricto | ✅ | ✅ | — |

## Tecnologías y versiones

| Componente | Versión |
|---|---|
| Java | 21 (compilado y probado con JDK 21.0.6) |
| Spring Boot | 4.1.1 |
| Spring Framework / Spring Security | 7.0.9 / 7.1.1 |
| Jackson | 3.1.5 (`tools.jackson`); `jackson-annotations` 2.21 |
| Flyway | 12.4.0 (+ `flyway-database-postgresql`) |
| Driver PostgreSQL | 42.7.13 |
| PostgreSQL (pruebas y desarrollo) | 17.6 |
| JUnit / Mockito / Testcontainers / ArchUnit | 6.0.3 / 5.23.0 / 2.0.5 / 1.4.2 |
| Keycloak (solo desarrollo) | 26.4.0 |
| Nexora Discovery usado para los fixtures | JAR 0.1.0-SNAPSHOT (Spring Boot 3.3.5), solo lectura |

## Base de datos

| Migración | Contenido | Estado |
|---|---|---|
| V1 `create_tenant` | Tabla `tenant` | Aplicada y probada |
| V2 `create_data_source` | Tabla `data_source`, `UNIQUE (tenant_id, id)` | Aplicada y probada |
| V3 `create_audit_event` | `audit_event` append-only | Aplicada y probada |
| V4 `protect_immutable_columns` | `UPDATE` por columna + triggers de columnas inmutables | Aplicada y probada |
| **V5 `create_physical_metadata`** | `metadata_snapshot` (append-only) y `physical_object` (ids estables, FK compuestas `(tenant_id, data_source_id, …)`, inmutabilidad, sin DELETE) | Aplicada y probada (Flyway al arrancar y job independiente) |

Las migraciones aplicadas no se modifican: cada cambio será una migración nueva.

## Seguridad y aislamiento

- **Importación:**
  - requiere `semantic:source:manage` (STEWARD; un principal AI nunca lo recibe);
  - la consulta requiere `semantic:catalog:read`;
  - una fuente de otro tenant responde 404 en todas las rutas.
- **Credenciales:** nunca se almacenan; los campos desconocidos del payload (p. ej. `connection`) se ignoran y
  la prueba lo verifica en la base de datos. El sobre es estricto.
- **Comentarios de la base del cliente:** se tratan como texto no confiable; se eliminan los caracteres de
  control y se truncan.
- **Límite de tamaño:** documento de 16 MiB como máximo, rechazado con 413 antes de cargarlo en memoria.
- **Base de datos:**
  - FK compuestas que impiden referencias entre tenants o entre fuentes;
  - triggers de inmutabilidad y append-only;
  - el rol de aplicación sigue siendo de mínimo privilegio (verificado).

## Pruebas y última verificación

| Tipo | Pruebas |
|---|---|
| Unitarias | 176 |
| Arquitectura (ArchUnit) | 9 |
| Integración (PostgreSQL 17.6, OIDC local, MockMvc, respuestas reales de Discovery) | 158 |
| **Total** | **343** |

Pruebas añadidas en S2 Basic (65):

| Clase | Pruebas |
|---|---|
| `MetadataReconcilerTest` | 13 |
| `NaturalKeyTest` | 3 |
| `JacksonDiscoveredMetadataReaderTest` | 18 |
| `MetadataImportApiIntegrationTest` | 14 |
| `PhysicalMetadataDatabaseIntegrationTest` | 15 |
| `MetadataImportLimitIntegrationTest` | 1 |
| `AuditAtomicityIntegrationTest` | +1 |

**Última verificación** (19 oct 2026): `mvn clean verify` ⇒ **BUILD SUCCESS, 343 pruebas, 0 fallos, 0 errores,
0 omitidas**.

## Problemas abiertos

| ID | Problema | Severidad | Estado |
|---|---|---|---|
| I7 | Lectura tolerante del JSON de Discovery | Importante | **Resuelto** en S2 Basic |
| P6 | El sobre `discovery-api/0.1` es un contrato propuesto por Semantic; Discovery no lo conoce (D16) | Importante | Pendiente de coordinación con Discovery |
| P7 | No hay una prueba automatizada que llame en vivo a Discovery; se usan sus respuestas reales capturadas (sin modificar) | Menor | Aceptado para S2 Basic |
| P8 | Rendimiento con esquemas grandes (miles de tablas) sin medir; escritura por lotes implementada | Medio | Pendiente |
| P9 | La conciliación carga en memoria todos los objetos de la fuente | Medio | Aceptable para la prueba de concepto; revisar con P8 |
| O1–O16 | Hallazgos opcionales de la auditoría de S1 (O5, límite de tamaño, resuelto para la importación) | Opcional | Pendiente |
| P1 | El perfil `prod` no comprueba al arrancar que el esquema esté en la última versión | Opcional | Pendiente de decisión |
| P2 | En este equipo Maven necesita `MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` | Entorno | Documentado |
| P3 | Aviso de Mockito por carga dinámica del agente Java | Menor | Pendiente |
| P5 | Nexora Discovery sigue en Spring Boot 3.3.5, sin soporte | Fuera de este repositorio | Informado |

## Decisiones pendientes

| ID | Decisión | Necesaria para |
|---|---|---|
| D2 | Proveedor OIDC de producción | Despliegue |
| D3 | Primera política empresarial de aprobación configurable | S3 |
| D8 | Reevaluar Row-Level Security | S5 |
| D14 | Zona horaria de las dimensiones temporales | S3 |
| D15 | Confirmar el mecanismo del job de migraciones | Despliegue |
| D16 | Confirmar con Discovery el sobre `discovery-api/0.1` (o que Discovery devuelva alcance y fecha) | S2 completo |
| P1 | Comprobación de la versión del esquema al arrancar en `prod` | Despliegue (opcional) |

## Funcionalidades expresamente no implementadas

- **Resto de S2:** importación de rutinas; registro `schema_change` con reconocimiento por un steward;
  `TypeCategoryMapper`; llamada directa de Semantic a Discovery (`DiscoveryClientPort`).
- **Etapas futuras:** catálogo semántico, propuestas y aprobaciones (S3); resolución semántica y planes (S4).
- **Integraciones excluidas:** Qwen, Ollama o cualquier LLM (S5, requiere autorización); generación de reportes,
  Nexora Predict y Nexora Correct; cualquier modificación de Nexora Discovery (solo lectura).
- **Datos de clientes:** ejecución de SQL empresarial o conexión a bases de clientes; almacenamiento de
  credenciales de bases de clientes.
- **Despliegue y operación:** Dockerfile, Kubernetes y CI; Row-Level Security; CORS y límite de peticiones.
