-- Nexora Semantic Engine: production database identities (audit finding I3).
-- Run ONCE as a PostgreSQL administrator, connected to the target database, with psql:
--
--   psql -v ON_ERROR_STOP=1 -v db=nexora_semantic \
--        -v migrator=nexora_semantic_migrator -v app=nexora_semantic_app \
--        -d nexora_semantic -f deploy/postgres/create-roles.sql
--
-- This file contains NO passwords. Set them afterwards from your secret manager, for example with
-- "\password nexora_semantic_migrator" in psql, or use another authentication method (certificates, IAM).
--
-- Resulting identities:
--   migrator  LOGIN, owner of schema "semantic". Used ONLY by the migration job (SchemaMigrationMain).
--             No CREATE on the database, no superuser/createrole/createdb.
--   app       LOGIN, CONNECT only. Every table privilege is granted by the migrations (DML only, column-level
--             UPDATE, no DELETE/TRUNCATE/TRIGGER, no ownership). Used ONLY by the application.

CREATE ROLE :"migrator" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOINHERIT;
CREATE ROLE :"app" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOINHERIT;

REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO :"migrator";
GRANT CONNECT ON DATABASE :"db" TO :"app";

-- No identity may create objects in "public" (default since PostgreSQL 15; explicit for older versions).
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

-- The administrator creates the schema on behalf of the migrator, so the migrator needs no database-level
-- CREATE privilege. Flyway creates its history table and all objects inside this schema.
CREATE SCHEMA semantic AUTHORIZATION :"migrator";
