# Changelog — Nexora Semantic Engine

Historial cronológico (de más antiguo a más reciente). Todos los bloques se realizaron el **19 oct 2026** sobre
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
