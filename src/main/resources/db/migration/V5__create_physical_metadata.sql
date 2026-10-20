-- S2 Basic: metadata imported from Nexora Discovery.
-- Isolation: every foreign key includes tenant_id AND data_source_id, so a snapshot or object can never reference
-- rows of another tenant or of another data source, even through a programming error.
-- Nothing is ever deleted: the application role has no DELETE; snapshots are append-only; objects only change
-- their mutable columns.

-- The composite foreign keys to data_source use data_source_tenant_id_uk (tenant_id, id), created in V2.

CREATE TABLE metadata_snapshot (
    id                  uuid        PRIMARY KEY,
    tenant_id           uuid        NOT NULL,
    data_source_id      uuid        NOT NULL,
    contract_version    text        NOT NULL,
    engine              text        NOT NULL,
    database_name       text        NOT NULL,
    product_version     text,
    discovered_at       timestamptz,
    requested_schemas   jsonb       NOT NULL,
    content_sha256      text        NOT NULL,
    outcome             text        NOT NULL,
    observed_count      integer     NOT NULL,
    added_count         integer     NOT NULL,
    updated_count       integer     NOT NULL,
    unchanged_count     integer     NOT NULL,
    missing_count       integer     NOT NULL,
    reappeared_count    integer     NOT NULL,
    unavailable_schemas jsonb       NOT NULL,
    imported_by         text        NOT NULL,
    imported_at         timestamptz NOT NULL,
    CONSTRAINT metadata_snapshot_data_source_fk FOREIGN KEY (tenant_id, data_source_id)
        REFERENCES data_source (tenant_id, id),
    CONSTRAINT metadata_snapshot_scope_uk UNIQUE (tenant_id, data_source_id, id),
    CONSTRAINT metadata_snapshot_contract_ck CHECK (length(contract_version) BETWEEN 1 AND 64),
    CONSTRAINT metadata_snapshot_engine_ck CHECK (engine ~ '^[a-z0-9_-]{1,32}$'),
    CONSTRAINT metadata_snapshot_database_ck CHECK (length(database_name) BETWEEN 1 AND 128),
    CONSTRAINT metadata_snapshot_product_ck CHECK (product_version IS NULL OR length(product_version) <= 255),
    CONSTRAINT metadata_snapshot_requested_ck CHECK (jsonb_typeof(requested_schemas) = 'array'),
    CONSTRAINT metadata_snapshot_unavailable_ck CHECK (jsonb_typeof(unavailable_schemas) = 'array'),
    CONSTRAINT metadata_snapshot_sha_ck CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT metadata_snapshot_outcome_ck CHECK (outcome IN ('APPLIED', 'UNCHANGED')),
    CONSTRAINT metadata_snapshot_counts_ck CHECK (observed_count >= 0 AND added_count >= 0 AND updated_count >= 0
        AND unchanged_count >= 0 AND missing_count >= 0 AND reappeared_count >= 0),
    CONSTRAINT metadata_snapshot_actor_ck CHECK (length(imported_by) BETWEEN 1 AND 255)
);

CREATE INDEX metadata_snapshot_history_idx ON metadata_snapshot (tenant_id, data_source_id, imported_at DESC);

COMMENT ON TABLE metadata_snapshot IS 'Append-only history of Discovery metadata imports (S2).';

CREATE TRIGGER metadata_snapshot_no_update_delete
    BEFORE UPDATE OR DELETE ON metadata_snapshot
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER metadata_snapshot_no_truncate
    BEFORE TRUNCATE ON metadata_snapshot
    FOR EACH STATEMENT EXECUTE FUNCTION reject_mutation();

GRANT SELECT, INSERT ON metadata_snapshot TO ${appRole};

CREATE TABLE physical_object (
    id                        uuid        PRIMARY KEY,
    tenant_id                 uuid        NOT NULL,
    data_source_id            uuid        NOT NULL,
    object_type               text        NOT NULL,
    parent_id                 uuid,
    schema_name               text        NOT NULL,
    object_name               text,
    child_name                text,
    natural_key               text        NOT NULL,
    attributes                jsonb       NOT NULL,
    comment                   text,
    attributes_sha256         text        NOT NULL,
    status                    text        NOT NULL,
    first_seen_snapshot_id    uuid        NOT NULL,
    last_seen_snapshot_id     uuid        NOT NULL,
    missing_since_snapshot_id uuid,
    created_at                timestamptz NOT NULL,
    updated_at                timestamptz NOT NULL,
    CONSTRAINT physical_object_data_source_fk FOREIGN KEY (tenant_id, data_source_id)
        REFERENCES data_source (tenant_id, id),
    CONSTRAINT physical_object_scope_uk UNIQUE (tenant_id, data_source_id, id),
    CONSTRAINT physical_object_natural_key_uk UNIQUE (tenant_id, data_source_id, object_type, natural_key),
    CONSTRAINT physical_object_parent_fk FOREIGN KEY (tenant_id, data_source_id, parent_id)
        REFERENCES physical_object (tenant_id, data_source_id, id),
    CONSTRAINT physical_object_first_seen_fk FOREIGN KEY (tenant_id, data_source_id, first_seen_snapshot_id)
        REFERENCES metadata_snapshot (tenant_id, data_source_id, id),
    CONSTRAINT physical_object_last_seen_fk FOREIGN KEY (tenant_id, data_source_id, last_seen_snapshot_id)
        REFERENCES metadata_snapshot (tenant_id, data_source_id, id),
    CONSTRAINT physical_object_missing_since_fk FOREIGN KEY (tenant_id, data_source_id, missing_since_snapshot_id)
        REFERENCES metadata_snapshot (tenant_id, data_source_id, id),
    CONSTRAINT physical_object_type_ck CHECK (object_type IN
        ('SCHEMA', 'TABLE', 'VIEW', 'MATERIALIZED_VIEW', 'COLUMN', 'FOREIGN_KEY')),
    CONSTRAINT physical_object_shape_ck CHECK (
        (object_type = 'SCHEMA' AND parent_id IS NULL AND object_name IS NULL AND child_name IS NULL)
        OR (object_type IN ('TABLE', 'VIEW', 'MATERIALIZED_VIEW')
            AND parent_id IS NOT NULL AND object_name IS NOT NULL AND child_name IS NULL)
        OR (object_type IN ('COLUMN', 'FOREIGN_KEY')
            AND parent_id IS NOT NULL AND object_name IS NOT NULL AND child_name IS NOT NULL)),
    CONSTRAINT physical_object_status_ck CHECK (status IN ('ACTIVE', 'MISSING', 'UNAVAILABLE')),
    CONSTRAINT physical_object_unavailable_ck CHECK (status <> 'UNAVAILABLE' OR object_type = 'SCHEMA'),
    CONSTRAINT physical_object_missing_ck CHECK ((status = 'MISSING') = (missing_since_snapshot_id IS NOT NULL)),
    CONSTRAINT physical_object_names_ck CHECK (length(schema_name) BETWEEN 1 AND 128
        AND (object_name IS NULL OR length(object_name) BETWEEN 1 AND 128)
        AND (child_name IS NULL OR length(child_name) BETWEEN 1 AND 300)),
    CONSTRAINT physical_object_comment_ck CHECK (comment IS NULL OR length(comment) <= 2000),
    CONSTRAINT physical_object_attributes_ck CHECK (jsonb_typeof(attributes) = 'object'),
    CONSTRAINT physical_object_sha_ck CHECK (attributes_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX physical_object_parent_idx ON physical_object (tenant_id, data_source_id, parent_id);
CREATE INDEX physical_object_type_status_idx ON physical_object (tenant_id, data_source_id, object_type, status);

COMMENT ON TABLE physical_object IS 'Discovered database objects with stable ids; never deleted (S2).';
COMMENT ON COLUMN physical_object.comment IS 'Comment from the customer database: untrusted data.';

-- Identity columns never change, for any role (same function as V4).
CREATE TRIGGER physical_object_immutable_columns
    BEFORE UPDATE ON physical_object
    FOR EACH ROW
    WHEN (OLD.id IS DISTINCT FROM NEW.id
       OR OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
       OR OLD.data_source_id IS DISTINCT FROM NEW.data_source_id
       OR OLD.object_type IS DISTINCT FROM NEW.object_type
       OR OLD.natural_key IS DISTINCT FROM NEW.natural_key
       OR OLD.schema_name IS DISTINCT FROM NEW.schema_name
       OR OLD.object_name IS DISTINCT FROM NEW.object_name
       OR OLD.child_name IS DISTINCT FROM NEW.child_name
       OR OLD.first_seen_snapshot_id IS DISTINCT FROM NEW.first_seen_snapshot_id
       OR OLD.created_at IS DISTINCT FROM NEW.created_at)
    EXECUTE FUNCTION reject_immutable_change();

GRANT SELECT, INSERT ON physical_object TO ${appRole};
GRANT UPDATE (parent_id, attributes, comment, attributes_sha256, status, last_seen_snapshot_id,
              missing_since_snapshot_id, updated_at) ON physical_object TO ${appRole};
