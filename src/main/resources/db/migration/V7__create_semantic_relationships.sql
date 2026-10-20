-- S3.1: catalog of semantic relationships between physical relations (tables or views) of one data source.
-- A relationship joins a source and a target object through one or more ordered column pairs. References are typed
-- columns with composite foreign keys that include tenant_id AND data_source_id: a relationship can never reference
-- an object of another tenant or another data source. There is no free-form expression or SQL.
-- Objects, pairs, origin, foreign key evidence and the replaced relationship are immutable; corrections are new
-- relationships that replace old ones. Nothing is deleted: the application role has no DELETE.

CREATE TABLE semantic_relationship (
    id                    uuid        PRIMARY KEY,
    tenant_id             uuid        NOT NULL,
    data_source_id        uuid        NOT NULL,
    name                  text        NOT NULL,
    description           text,
    origin                text        NOT NULL,
    source_object_id      uuid        NOT NULL,
    target_object_id      uuid        NOT NULL,
    foreign_key_object_id uuid,
    pair_count            smallint    NOT NULL,
    signature             text        NOT NULL,
    declared_cardinality  text        NOT NULL,
    allow_inner_join      boolean     NOT NULL,
    allow_left_join       boolean     NOT NULL,
    replaces_id           uuid,
    status                text        NOT NULL,
    created_by            text        NOT NULL,
    created_by_type       text        NOT NULL,
    created_at            timestamptz NOT NULL,
    updated_by            text        NOT NULL,
    updated_by_type       text        NOT NULL,
    updated_at            timestamptz NOT NULL,
    approved_by           text,
    approved_at           timestamptz,
    inactive_reason       text,
    row_version           bigint      NOT NULL DEFAULT 0,
    CONSTRAINT semantic_relationship_data_source_fk FOREIGN KEY (tenant_id, data_source_id)
        REFERENCES data_source (tenant_id, id),
    CONSTRAINT semantic_relationship_scope_uk UNIQUE (tenant_id, data_source_id, id),
    CONSTRAINT semantic_relationship_source_fk FOREIGN KEY (tenant_id, data_source_id, source_object_id)
        REFERENCES physical_object (tenant_id, data_source_id, id),
    CONSTRAINT semantic_relationship_target_fk FOREIGN KEY (tenant_id, data_source_id, target_object_id)
        REFERENCES physical_object (tenant_id, data_source_id, id),
    CONSTRAINT semantic_relationship_foreign_key_fk FOREIGN KEY (tenant_id, data_source_id, foreign_key_object_id)
        REFERENCES physical_object (tenant_id, data_source_id, id),
    CONSTRAINT semantic_relationship_replaces_fk FOREIGN KEY (tenant_id, data_source_id, replaces_id)
        REFERENCES semantic_relationship (tenant_id, data_source_id, id),
    CONSTRAINT semantic_relationship_name_ck CHECK (length(name) BETWEEN 1 AND 200),
    CONSTRAINT semantic_relationship_description_ck CHECK (description IS NULL
        OR length(description) BETWEEN 1 AND 2000),
    CONSTRAINT semantic_relationship_origin_ck CHECK (origin IN ('DECLARED_FK', 'SEMANTIC')),
    CONSTRAINT semantic_relationship_evidence_ck CHECK ((origin = 'DECLARED_FK') = (foreign_key_object_id IS NOT NULL)),
    CONSTRAINT semantic_relationship_pair_count_ck CHECK (pair_count BETWEEN 1 AND 16),
    CONSTRAINT semantic_relationship_signature_ck CHECK (signature ~ '^[0-9a-f]{64}$'),
    CONSTRAINT semantic_relationship_cardinality_ck CHECK (declared_cardinality IN
        ('ONE_TO_ONE', 'ONE_TO_MANY', 'MANY_TO_ONE', 'MANY_TO_MANY')),
    CONSTRAINT semantic_relationship_join_ck CHECK (allow_inner_join OR allow_left_join),
    CONSTRAINT semantic_relationship_not_self_ck CHECK (replaces_id IS NULL OR replaces_id <> id),
    CONSTRAINT semantic_relationship_status_ck CHECK (status IN ('DRAFT', 'APPROVED', 'INACTIVE')),
    CONSTRAINT semantic_relationship_actor_type_ck CHECK (created_by_type IN ('HUMAN', 'SERVICE', 'AI')
        AND updated_by_type IN ('HUMAN', 'SERVICE', 'AI')),
    CONSTRAINT semantic_relationship_actor_ck CHECK (length(created_by) BETWEEN 1 AND 255
        AND length(updated_by) BETWEEN 1 AND 255),
    CONSTRAINT semantic_relationship_approval_ck CHECK (
        (status <> 'APPROVED' OR (approved_by IS NOT NULL AND approved_at IS NOT NULL))
        AND (status <> 'DRAFT' OR (approved_by IS NULL AND approved_at IS NULL))),
    CONSTRAINT semantic_relationship_inactive_ck CHECK ((status = 'INACTIVE') = (inactive_reason IS NOT NULL)),
    CONSTRAINT semantic_relationship_reason_ck CHECK (inactive_reason IS NULL
        OR length(inactive_reason) BETWEEN 1 AND 500),
    CONSTRAINT semantic_relationship_row_version_ck CHECK (row_version >= 0)
);

-- Never two equivalent (same columns joined, any direction or pair order) APPROVED relationships: they would make
-- join paths ambiguous. The application additionally rejects equivalent DRAFTs, except a declared replacement.
CREATE UNIQUE INDEX semantic_relationship_approved_signature_uk
    ON semantic_relationship (tenant_id, data_source_id, signature) WHERE status = 'APPROVED';
CREATE INDEX semantic_relationship_status_idx ON semantic_relationship (tenant_id, data_source_id, status);
CREATE INDEX semantic_relationship_signature_idx ON semantic_relationship (tenant_id, data_source_id, signature);
CREATE INDEX semantic_relationship_source_idx ON semantic_relationship (tenant_id, data_source_id, source_object_id);
CREATE INDEX semantic_relationship_target_idx ON semantic_relationship (tenant_id, data_source_id, target_object_id);

COMMENT ON TABLE semantic_relationship IS 'Semantic relationships between physical tables or views (S3.1).';

-- Identity, endpoints, pairs digest, origin, evidence and authorship never change, for any role (same function as V4).
CREATE TRIGGER semantic_relationship_immutable_columns
    BEFORE UPDATE ON semantic_relationship
    FOR EACH ROW
    WHEN (OLD.id IS DISTINCT FROM NEW.id
       OR OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
       OR OLD.data_source_id IS DISTINCT FROM NEW.data_source_id
       OR OLD.origin IS DISTINCT FROM NEW.origin
       OR OLD.source_object_id IS DISTINCT FROM NEW.source_object_id
       OR OLD.target_object_id IS DISTINCT FROM NEW.target_object_id
       OR OLD.foreign_key_object_id IS DISTINCT FROM NEW.foreign_key_object_id
       OR OLD.pair_count IS DISTINCT FROM NEW.pair_count
       OR OLD.signature IS DISTINCT FROM NEW.signature
       OR OLD.replaces_id IS DISTINCT FROM NEW.replaces_id
       OR OLD.created_by IS DISTINCT FROM NEW.created_by
       OR OLD.created_by_type IS DISTINCT FROM NEW.created_by_type
       OR OLD.created_at IS DISTINCT FROM NEW.created_at)
    EXECUTE FUNCTION reject_immutable_change();

GRANT SELECT, INSERT ON semantic_relationship TO ${appRole};
GRANT UPDATE (name, description, declared_cardinality, allow_inner_join, allow_left_join, status, updated_by,
              updated_by_type, updated_at, approved_by, approved_at, inactive_reason, row_version)
    ON semantic_relationship TO ${appRole};

-- Ordered column pairs: source_column = target_column. Append-only (no UPDATE, DELETE or TRUNCATE for any role).
CREATE TABLE semantic_relationship_pair (
    tenant_id        uuid     NOT NULL,
    data_source_id   uuid     NOT NULL,
    relationship_id  uuid     NOT NULL,
    ordinal          smallint NOT NULL,
    source_column_id uuid     NOT NULL,
    target_column_id uuid     NOT NULL,
    CONSTRAINT semantic_relationship_pair_pk PRIMARY KEY (relationship_id, ordinal),
    CONSTRAINT semantic_relationship_pair_relationship_fk FOREIGN KEY (tenant_id, data_source_id, relationship_id)
        REFERENCES semantic_relationship (tenant_id, data_source_id, id),
    CONSTRAINT semantic_relationship_pair_source_fk FOREIGN KEY (tenant_id, data_source_id, source_column_id)
        REFERENCES physical_object (tenant_id, data_source_id, id),
    CONSTRAINT semantic_relationship_pair_target_fk FOREIGN KEY (tenant_id, data_source_id, target_column_id)
        REFERENCES physical_object (tenant_id, data_source_id, id),
    CONSTRAINT semantic_relationship_pair_columns_uk UNIQUE (relationship_id, source_column_id, target_column_id),
    CONSTRAINT semantic_relationship_pair_ordinal_ck CHECK (ordinal BETWEEN 0 AND 15)
);

CREATE INDEX semantic_relationship_pair_scope_idx
    ON semantic_relationship_pair (tenant_id, data_source_id, relationship_id);

COMMENT ON TABLE semantic_relationship_pair IS 'Immutable ordered column pairs of semantic relationships (S3.1).';

CREATE TRIGGER semantic_relationship_pair_no_update_delete
    BEFORE UPDATE OR DELETE ON semantic_relationship_pair
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER semantic_relationship_pair_no_truncate
    BEFORE TRUNCATE ON semantic_relationship_pair
    FOR EACH STATEMENT EXECUTE FUNCTION reject_mutation();

GRANT SELECT, INSERT ON semantic_relationship_pair TO ${appRole};
