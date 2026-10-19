# Nexora Semantic Engine

Transforma los metadatos técnicos de **Nexora Discovery** en conocimiento empresarial validado, versionado y
reutilizable por **Nexora Reports**. Arquitectura, decisiones y plan de etapas: [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

**Estado:** etapa S1 (fundaciones) sobre Spring Boot 4.1.1 y Java 21 — tenants, fuentes de datos, seguridad
OAuth2/JWT por permisos, aislamiento multiempresa, auditoría append-only, errores RFC 7807 y migraciones de
producción en un job independiente. Todavía no hay importación de Discovery, catálogo, propuestas, resolución ni
integración con IA. Estado detallado: [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md) · Historial:
[docs/CHANGELOG.md](docs/CHANGELOG.md).

Semantic **nunca** se conecta a bases de clientes, ejecuta SQL empresarial ni almacena credenciales.

## Requisitos

- Java 21, Maven 3.9+ (Spring Boot 4.1.1 y el resto de versiones los gestiona Maven; ver
  [docs/PROJECT_STATUS.md](docs/PROJECT_STATUS.md))
- Docker (pruebas de integración con Testcontainers y entorno de desarrollo)

## Compilar y probar

```bash
mvn verify
```

Incluye pruebas unitarias, reglas de arquitectura (ArchUnit) e integración contra PostgreSQL 17 real
(Testcontainers), con el rol de base de datos restringido de la aplicación y JWT firmados reales validados por
discovery OIDC contra un proveedor local de prueba, igual que en producción.

> En equipos Windows cuyo antivirus o proxy intercepta TLS, Maven puede fallar con `PKIX path building failed`.
> Use el almacén de certificados de Windows solo en su sesión:
> `MAVEN_OPTS="-Djavax.net.ssl.trustStoreType=Windows-ROOT"`.

## Entorno de desarrollo

```bash
cp docker/.env.example docker/.env        # cambie todos los valores
docker compose -f docker/docker-compose.yml --env-file docker/.env up -d
mvn package -DskipTests
SPRING_PROFILES_ACTIVE=dev \
SEMANTIC_DB_APP_PASSWORD=<valor de .env> SEMANTIC_DB_OWNER_PASSWORD=<valor de .env> \
java -jar target/nexora-semantic-0.1.0-SNAPSHOT.jar
```

- PostgreSQL en `127.0.0.1:5433` con dos roles: propietario del esquema (Flyway) y rol de aplicación (solo DML).
- **Keycloak solo para desarrollo** en `127.0.0.1:8180`, realm `nexora`, con usuarios `platform.admin`,
  `steward.demo`, `viewer.demo`, `auditor.demo` (grupo `tenant-demo`), `steward.other` (grupo `tenant-other`) y el
  cliente de servicio `nexora-reports-dev`. Contraseñas y secreto: `KC_DEV_USER_PASSWORD` / `KC_REPORTS_CLIENT_SECRET`.

**Alta de tenants (decisión D2: primero el tenant, después el proveedor):**

1. Con un token de `platform.admin`, registre los tenants:
   `POST /api/v1/admin/tenants {"key":"demo","name":"Demo"}` (y `other`). Cada respuesta incluye su `id` (UUID).
2. Copie los UUID en `docker/.env` (`KC_TENANT_DEMO_ID`, `KC_TENANT_OTHER_ID`) y recree Keycloak:
   `docker compose -f docker/docker-compose.yml --env-file docker/.env up -d --force-recreate keycloak`.
3. A partir de ese momento los tokens de los grupos llevan `tenant=<UUID>`. Hasta entonces llevan el UUID
   todo-ceros y los endpoints de tenant responden `403 tenant_access_denied`.

```bash
TOKEN=$(curl -s -d grant_type=password -d client_id=nexora-semantic-dev \
  -d username=steward.demo -d password=<KC_DEV_USER_PASSWORD> \
  http://localhost:8180/realms/nexora/protocol/openid-connect/token | jq -r .access_token)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/data-sources
```

## Configuración

| Variable | Uso |
|---|---|
| `SEMANTIC_DB_URL` | JDBC URL (por defecto `jdbc:postgresql://localhost:5433/nexora_semantic?currentSchema=semantic`) |
| `SEMANTIC_DB_APP_USER` / `SEMANTIC_DB_APP_PASSWORD` | Rol de aplicación (DML) |
| `SEMANTIC_DB_OWNER_USER` / `SEMANTIC_DB_OWNER_PASSWORD` | **Solo desarrollo y pruebas:** propietario del esquema, usado por Flyway al arrancar. Nunca en producción (la aplicación se niega a arrancar) |
| `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` | Emisor OIDC. **Obligatorio**: sin él la aplicación no arranca, y el claim `iss` se valida siempre. Opcionalmente, `..._JWK_SET_URI` además del emisor |
| `SEMANTIC_OIDC_AUDIENCE` | Audiencia exigida en los tokens (por defecto `nexora-semantic`) |
| `NEXORA_SEMANTIC_SECURITY_TENANTCLAIM` | Claim con el UUID del tenant (por defecto `tenant`; admite rutas `a.b`) |
| `NEXORA_SEMANTIC_SECURITY_PRINCIPALTYPECLAIM` | Claim con el tipo de principal (por defecto `principal_type`) |
| `NEXORA_SEMANTIC_SECURITY_ROLESCLAIM` | Claim con los roles Nexora (por defecto `roles`, p. ej. `realm_access.roles` en Keycloak) |

Requisitos del token de acceso (si no se cumplen, `401`):

- `iss` igual al emisor configurado, audiencia configurada, firma válida y sin caducar;
- `sub`;
- `principal_type` con el valor exacto `HUMAN`, `SERVICE` o `AI`;
- `tenant` con el UUID canónico del tenant. Solo los tokens con rol `PLATFORM_ADMIN` pueden omitirlo.

En Keycloak 25+, `sub` lo añade el scope `basic` o un mapper `oidc-sub-mapper` (ver el realm de desarrollo).
El identificador de correlación (`X-Request-Id`) lo genera siempre el servidor; el que envíe el cliente se ignora.

## Despliegue en producción

En producción la aplicación **nunca ejecuta migraciones** ni recibe credenciales con permisos DDL. Las migraciones
las aplica un job independiente, incluido en el mismo JAR, con su propia identidad de base de datos.
Decisión y detalles: [docs/ARCHITECTURE.md §8.6](docs/ARCHITECTURE.md) (D15).

### 1. Crear las identidades (una sola vez, como administrador de PostgreSQL)

```bash
psql -v ON_ERROR_STOP=1 -v db=nexora_semantic \
     -v migrator=nexora_semantic_migrator -v app=nexora_semantic_app \
     -d nexora_semantic -f deploy/postgres/create-roles.sql
```

El script no contiene contraseñas. Asígnelas después desde su gestor de secretos (p. ej. `\password` en psql).
Resultado: `nexora_semantic_migrator`, propietario del esquema `semantic`, usado solo por el job; y
`nexora_semantic_app`, con `CONNECT` y los privilegios DML que conceden las migraciones, usado solo por la aplicación.

### 2. Migrar (en cada versión, antes de desplegar la aplicación)

```bash
export SEMANTIC_MIGRATION_DB_URL=jdbc:postgresql://db.internal:5432/nexora_semantic   # sin credenciales
export SEMANTIC_MIGRATION_DB_USER=nexora_semantic_migrator
export SEMANTIC_MIGRATION_DB_PASSWORD_FILE=/run/secrets/semantic-migrator-password    # o ..._PASSWORD
export SEMANTIC_DB_APP_USER=nexora_semantic_app

java -cp nexora-semantic-<versión>.jar \
     -Dloader.main=com.nexora.semantic.infrastructure.migration.SchemaMigrationMain \
     org.springframework.boot.loader.launch.PropertiesLauncher migrate   # o: validate
```

Códigos de salida: `0` éxito, `1` fallo de migración o validación, `2` configuración inválida. Solo existen
`migrate` y `validate`; las operaciones destructivas de Flyway (`clean`, `repair`, `baseline`) no están
disponibles. En Kubernetes o Docker, ejecútelo como Job o contenedor de un solo uso, con el secreto montado como
fichero; la aplicación no debe recibir estas variables.

### 3. Desplegar la aplicación

```bash
export SPRING_PROFILES_ACTIVE=prod
export SEMANTIC_DB_URL="jdbc:postgresql://db.internal:5432/nexora_semantic?currentSchema=semantic"
export SEMANTIC_DB_APP_USER=nexora_semantic_app
export SEMANTIC_DB_APP_PASSWORD=<desde el gestor de secretos>
export SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI=https://idp.example.com/realms/nexora
java -jar nexora-semantic-<versión>.jar
```

Con el perfil `prod`, Flyway está deshabilitado y la aplicación se niega a arrancar si:
- Flyway se habilita o hay credenciales de propietario (`SEMANTIC_DB_OWNER_PASSWORD`, `spring.flyway.*`) configuradas;
- su rol de base de datos puede alterar la estructura: SUPERUSER, CREATEDB, CREATEROLE, BYPASSRLS, propiedad del
  esquema o de sus objetos, `CREATE`, `TRIGGER` o `TRUNCATE`.

## API (S1)

| Método y ruta | Permiso |
|---|---|
| `POST/GET /api/v1/admin/tenants`, `GET/PATCH /api/v1/admin/tenants/{id}` | `platform:tenant:manage` |
| `POST /api/v1/data-sources`, `PATCH /api/v1/data-sources/{id}` | `semantic:source:manage` |
| `GET /api/v1/data-sources`, `GET /api/v1/data-sources/{id}` | `semantic:catalog:read` |
| `GET /api/v1/audit-events` | `semantic:audit:read` |
| `GET /actuator/health` | público |

Los `PATCH` exigen `If-Match` con el `ETag` recibido (`428` si falta, `412` si no coincide). Detalle completo de
contratos y códigos de error en [docs/ARCHITECTURE.md §6](docs/ARCHITECTURE.md).
