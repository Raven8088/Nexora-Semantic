#!/usr/bin/env bash
# Development database bootstrap (runs once, on an empty volume).
# Creates the schema owner used by Flyway and the DML-only application role. Flyway creates the "semantic"
# schema as the owner and grants the application role only the privileges it needs.
set -euo pipefail

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v db="$POSTGRES_DB" \
  -v owner="$SEMANTIC_DB_OWNER_USER" -v owner_password="$SEMANTIC_DB_OWNER_PASSWORD" \
  -v app="$SEMANTIC_DB_APP_USER" -v app_password="$SEMANTIC_DB_APP_PASSWORD" <<'SQL'
CREATE ROLE :"owner" LOGIN PASSWORD :'owner_password';
CREATE ROLE :"app" LOGIN PASSWORD :'app_password';
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE :"db" TO :"owner";
GRANT CONNECT ON DATABASE :"db" TO :"app";
SQL
