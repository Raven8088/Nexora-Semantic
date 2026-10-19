-- Data sources. Deliberately no connection columns: no host, user or password is ever stored.

CREATE TABLE data_source (
    id            uuid        PRIMARY KEY,
    tenant_id     uuid        NOT NULL,
    key           text        NOT NULL,
    name          text        NOT NULL,
    engine        text        NOT NULL,
    database_name text        NOT NULL,
    description   text,
    status        text        NOT NULL,
    created_at    timestamptz NOT NULL,
    created_by    text        NOT NULL,
    updated_at    timestamptz NOT NULL,
    updated_by    text        NOT NULL,
    row_version   bigint      NOT NULL DEFAULT 0,
    CONSTRAINT data_source_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT data_source_key_uk UNIQUE (tenant_id, key),
    -- Target of the composite (tenant_id, data_source_id) foreign keys of tenant-scoped child tables (S2+),
    -- which makes cross-tenant references impossible at the database level.
    CONSTRAINT data_source_tenant_id_uk UNIQUE (tenant_id, id),
    CONSTRAINT data_source_key_format_ck CHECK (key ~ '^[a-z0-9][a-z0-9_-]{1,62}$'),
    CONSTRAINT data_source_name_ck CHECK (length(name) BETWEEN 1 AND 200),
    CONSTRAINT data_source_engine_ck CHECK (engine ~ '^[a-z0-9_-]{1,32}$'),
    CONSTRAINT data_source_database_name_ck CHECK (length(database_name) BETWEEN 1 AND 128),
    CONSTRAINT data_source_description_ck CHECK (description IS NULL OR length(description) BETWEEN 1 AND 1000),
    CONSTRAINT data_source_status_ck CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    CONSTRAINT data_source_actor_ck CHECK (length(created_by) BETWEEN 1 AND 255 AND length(updated_by) BETWEEN 1 AND 255),
    CONSTRAINT data_source_row_version_ck CHECK (row_version >= 0)
);

COMMENT ON TABLE data_source IS 'Customer databases interpreted by Semantic. Holds no connection data.';

-- No DELETE: data sources are archived, never removed.
GRANT SELECT, INSERT, UPDATE ON data_source TO ${appRole};
