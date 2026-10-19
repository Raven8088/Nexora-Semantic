-- Nexora Semantic Engine - S1
-- Runs as the schema owner. ${appRole} is the application role (DML only); it must already exist.

GRANT USAGE ON SCHEMA semantic TO ${appRole};

CREATE TABLE tenant (
    id          uuid        PRIMARY KEY,
    key         text        NOT NULL,
    name        text        NOT NULL,
    status      text        NOT NULL,
    created_at  timestamptz NOT NULL,
    created_by  text        NOT NULL,
    updated_at  timestamptz NOT NULL,
    updated_by  text        NOT NULL,
    row_version bigint      NOT NULL DEFAULT 0,
    CONSTRAINT tenant_key_uk UNIQUE (key),
    CONSTRAINT tenant_key_format_ck CHECK (key ~ '^[a-z0-9][a-z0-9-]{1,62}$'),
    CONSTRAINT tenant_name_ck CHECK (length(name) BETWEEN 1 AND 200),
    CONSTRAINT tenant_status_ck CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT tenant_actor_ck CHECK (length(created_by) BETWEEN 1 AND 255 AND length(updated_by) BETWEEN 1 AND 255),
    CONSTRAINT tenant_row_version_ck CHECK (row_version >= 0)
);

COMMENT ON TABLE tenant IS 'Companies. Root of tenant isolation; tenants are suspended, never deleted.';

-- No DELETE: tenants are suspended, never removed.
GRANT SELECT, INSERT, UPDATE ON tenant TO ${appRole};
