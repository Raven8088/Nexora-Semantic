# Nexora Semantic Engine — Estado del proyecto

> **Fuente de verdad del estado actual.** Arquitectura y decisiones: [ARCHITECTURE.md](ARCHITECTURE.md).
> Historial: [CHANGELOG.md](CHANGELOG.md).

| Campo | Valor |
|---|---|
| Última actualización | 19 oct 2026 |
| Versión del artefacto | `0.1.0-SNAPSHOT` |
| Etapa actual | **S1 — Fundaciones**: implementada y verificada; aceptación formal pendiente de revisión |
| Próxima tarea autorizada | **Ninguna.** S2 requiere autorización expresa |

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
| S1 — Fundaciones | **Implementada, probada y verificada manualmente** | Incluye las correcciones de auditoría B1, B2 e I1–I6 y la migración D10. Aceptación formal pendiente |
| S2 — Modelo físico e ingesta de Discovery | **Bloqueada** (sin autorización) | Requiere I7 (lector tolerante del JSON de Discovery) |
| S3 — Catálogo y propuestas | **Bloqueada** | Requiere S2 y la decisión D14 |
| S4 — Resolución semántica | **Bloqueada** | Requiere S3 |
| S5 — IA (Qwen) y endurecimiento | **Bloqueada** | Requiere S4 y autorización expresa para la IA |

## Funcionalidades de S1

| Funcionalidad | Implementado | Probado | Verificado manualmente |
|---|---|---|---|
| Alta, consulta y suspensión de tenants (`/api/v1/admin/tenants`) | ✅ | ✅ | ✅ Keycloak 26.4 |
| Fuentes de datos: alta, listado paginado, consulta, modificación con ETag/If-Match, archivado | ✅ | ✅ | ✅ |
| Consulta de auditoría (`/api/v1/audit-events`) | ✅ | ✅ | ✅ |
| OAuth2 Resource Server con JWT; `issuer-uri` obligatorio y emisor validado | ✅ | ✅ (proveedor OIDC local) | ✅ Keycloak |
| Claims: `tenant` = UUID; `principal_type` obligatorio y exacto; roles → permisos | ✅ | ✅ | ✅ |
| Aislamiento multiempresa (tenant del token, filtrado por `tenant_id`, 404 entre tenants) | ✅ | ✅ | ✅ |
| Auditoría append-only en la misma transacción (rollback si falla) | ✅ | ✅ | ✅ |
| Errores RFC 7807 con `code`, `correlationId` y `type: about:blank` | ✅ | ✅ | ✅ |
| `X-Request-Id` generado siempre por el servidor | ✅ | ✅ | ✅ |
| Columnas inmutables protegidas (V4: grants por columna + triggers) | ✅ | ✅ | — |
| Migraciones de producción en un job independiente (`SchemaMigrationMain`) | ✅ | ✅ | ✅ desde el JAR 4.1.1 |
| Perfil `prod`: Flyway deshabilitado y verificación de mínimo privilegio al arrancar | ✅ | ✅ | ✅ |
| Entorno de desarrollo (PostgreSQL + Keycloak solo para desarrollo) | ✅ | — | ✅ |

## Tecnologías y versiones

Versiones resueltas por Maven con Spring Boot 4.1.1 (verificadas con `dependency:list` y en el JAR empaquetado).

| Componente | Versión |
|---|---|
| Java | 21 (compilado y probado con JDK 21.0.6) |
| Spring Boot | **4.1.1** (antes 3.5.16; decisión D10) |
| Spring Framework | 7.0.9 |
| Spring Security | 7.1.1 |
| Jackson | 3.1.5 (`tools.jackson`); `jackson-annotations` 2.21 |
| Flyway | 12.4.0 (+ `flyway-database-postgresql`) |
| Driver PostgreSQL | 42.7.13 |
| PostgreSQL (pruebas y desarrollo) | 17.6 (imagen `postgres:17.6`) |
| Nimbus JOSE + JWT | 10.9.1 |
| JUnit | 6.0.3 |
| Mockito | 5.23.0 |
| Testcontainers | 2.0.5 |
| ArchUnit | 1.4.2 |
| Keycloak (solo desarrollo) | 26.4.0 |
| Maven | 3.9.9 (entorno de verificación) |

## Base de datos

| Migración | Contenido | Estado |
|---|---|---|
| V1 `create_tenant` | Tabla `tenant`, grants al rol de aplicación | Aplicada y probada |
| V2 `create_data_source` | Tabla `data_source`, `UNIQUE (tenant_id, id)` para FK compuestas futuras | Aplicada y probada |
| V3 `create_audit_event` | `audit_event` append-only (grants + triggers anti UPDATE/DELETE/TRUNCATE) | Aplicada y probada |
| V4 `protect_immutable_columns` | `UPDATE` por columna + triggers de columnas inmutables | Aplicada y probada |

Las migraciones aplicadas no se modifican: cada cambio será una migración nueva.
Identidades: propietario o migrador (DDL, solo Flyway o el job) y aplicación (solo DML, sin DELETE, TRUNCATE,
TRIGGER, CREATE ni propiedad de objetos).

## Seguridad y aislamiento

- **Tokens:** se rechazan con 401 si el emisor, la firma, la caducidad, la audiencia, `sub` o `principal_type`
  no son válidos, o si `tenant` está malformado o ausente (salvo `PLATFORM_ADMIN`).
- **Tenants:** inexistente o suspendido ⇒ 403 `tenant_access_denied`; recurso de otro tenant ⇒ 404.
- **Principales no humanos:** AI o SERVICE nunca reciben permiso de aprobación; AI solo puede leer y proponer.
- **Credenciales:** no hay campos de credenciales (regla ArchUnit y comprobación de columnas) y las propiedades
  JSON desconocidas se rechazan.
- **Producción:** la aplicación no tiene credenciales DDL y se niega a arrancar si Flyway está activo, si hay
  credenciales de propietario o si su rol puede alterar la estructura.

## Pruebas y última verificación

| Tipo | Pruebas |
|---|---|
| Unitarias (dominio, aplicación, seguridad, web, configuración del job) | 142 |
| Arquitectura (ArchUnit) | 9 |
| Integración (PostgreSQL 17.6 con Testcontainers, OIDC local, MockMvc) | 127 |
| **Total** | **278** |

**Última verificación** (19 oct 2026, Spring Boot 4.1.1): `mvn clean verify` ⇒ **BUILD SUCCESS, 278 pruebas,
0 fallos, 0 errores, 0 omitidas**. Verificación manual con el JAR empaquetado:
- job de migración (`migrate`, `validate`, repetición idempotente, comando no permitido ⇒ código 2);
- perfil `prod` (sin Flyway, conexión solo como `nexora_semantic_app`, tres rechazos de arranque);
- perfil `dev` contra Keycloak 26.4 (alta de tenants, JWT, permisos, aislamiento, ETags, auditoría y errores RFC 7807).

## Problemas abiertos

| ID | Problema | Severidad | Estado |
|---|---|---|---|
| I7 | El rechazo global de propiedades JSON desconocidas chocará con la lectura tolerante del JSON de Discovery | Importante | Pendiente (se aborda en S2) |
| O1–O16 | Hallazgos opcionales de la auditoría de S1 (p. ej. O1: 500 en lugar de 401 en un caso inalcanzable; O5: límite de tamaño del cuerpo; O11: sin medición de cobertura; O14: CORS y límite de peticiones) | Opcional | Pendiente |
| P1 | El perfil `prod` no comprueba al arrancar que el esquema esté en la última versión (necesitaría `SELECT` sobre `flyway_schema_history` mediante una migración nueva) | Opcional | Pendiente de decisión |
| P2 | En este equipo Maven necesita `MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=Windows-ROOT` (algo intercepta TLS); no se añade al proyecto | Entorno | Documentado en README |
| P3 | Aviso de Mockito por carga dinámica del agente Java en las pruebas | Menor | Pendiente |
| P4 | Jackson 3 ordena alfabéticamente las propiedades JSON (no es contractual) | Informativo | Aceptado |
| P5 | Nexora Discovery sigue en Spring Boot 3.3.5, sin soporte | Fuera de este repositorio | Informado |

## Decisiones pendientes

| ID | Decisión | Necesaria para |
|---|---|---|
| D2 | Proveedor OIDC de producción (Keycloak solo para desarrollo) | Despliegue |
| D3 | Primera política empresarial de aprobación configurable | S3 |
| D8 | Reevaluar Row-Level Security | S5 |
| D14 | Zona horaria de las dimensiones temporales | S3 |
| D15 | Confirmar el mecanismo del job (JAR + `PropertiesLauncher`) frente a la CLI o imagen de Flyway | Despliegue |
| P1 | Comprobación de la versión del esquema al arrancar en `prod` | Despliegue (opcional) |

## Funcionalidades expresamente no implementadas

- **Etapas futuras:** importación de Discovery, modelo físico, snapshots y detección de cambios (S2); catálogo
  semántico, propuestas y aprobaciones (S3); resolución semántica y planes (S4).
- **Integraciones excluidas:** integración con Qwen u Ollama o cualquier LLM (S5, requiere autorización); Nexora
  Predict y Nexora Correct; cualquier modificación de Nexora Discovery (solo lectura).
- **Datos de clientes:** ejecución de SQL empresarial o conexión a bases de datos de clientes (fuera del alcance
  de Semantic); almacenamiento o transmisión de credenciales de bases de datos de clientes.
- **Despliegue y operación:** Dockerfile, manifiestos de Kubernetes y pipeline de CI; Row-Level Security (D8);
  CORS, límites de peticiones y medición de cobertura.
