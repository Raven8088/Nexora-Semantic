-- S3 Basic: semantic catalog (concepts, metrics, dimensions) of a data source.
-- References are typed columns with composite foreign keys that include tenant_id AND data_source_id: a definition
-- can never reference a physical object or a concept of another tenant or another data source.
-- There is no free-form expression or SQL: only controlled aggregations and dimension types.
-- Nothing is deleted: the application role has no DELETE; identity columns are immutable.

CREATE TABLE semantic_definition (
    id                 uuid        PRIMARY KEY,
    tenant_id          uuid        NOT NULL,
    data_source_id     uuid        NOT NULL,
    kind               text        NOT NULL,
    key                text        NOT NULL,
    name               text        NOT NULL,
    description        text,
    status             text        NOT NULL,
    origin             text        NOT NULL,
    relation_object_id uuid,
    column_object_id   uuid,
    concept_id         uuid,
    aggregation        text,
    dimension_type     text,
    granularities      jsonb,
    time_zone          text,
    created_by         text        NOT NULL,
    created_by_type    text        NOT NULL,
    created_at         timestamptz NOT NULL,
    updated_by         text        NOT NULL,
    updated_by_type    text        NOT NULL,
    updated_at         timestamptz NOT NULL,
    approved_by        text,
    approved_at        timestamptz,
    inactive_reason    text,
    row_version        bigint      NOT NULL DEFAULT 0,
    CONSTRAINT semantic_definition_data_source_fk FOREIGN KEY (tenant_id, data_source_id)
        REFERENCES data_source (tenant_id, id),
    CONSTRAINT semantic_definition_scope_uk UNIQUE (tenant_id, data_source_id, id),
    CONSTRAINT semantic_definition_key_uk UNIQUE (tenant_id, data_source_id, kind, key),
    CONSTRAINT semantic_definition_relation_fk FOREIGN KEY (tenant_id, data_source_id, relation_object_id)
        REFERENCES physical_object (tenant_id, data_source_id, id),
    CONSTRAINT semantic_definition_column_fk FOREIGN KEY (tenant_id, data_source_id, column_object_id)
        REFERENCES physical_object (tenant_id, data_source_id, id),
    CONSTRAINT semantic_definition_concept_fk FOREIGN KEY (tenant_id, data_source_id, concept_id)
        REFERENCES semantic_definition (tenant_id, data_source_id, id),
    CONSTRAINT semantic_definition_kind_ck CHECK (kind IN ('CONCEPT', 'METRIC', 'DIMENSION')),
    CONSTRAINT semantic_definition_key_format_ck CHECK (key ~ '^[a-z0-9][a-z0-9_-]{1,62}$'),
    CONSTRAINT semantic_definition_name_ck CHECK (length(name) BETWEEN 1 AND 200),
    CONSTRAINT semantic_definition_description_ck CHECK (description IS NULL OR length(description) BETWEEN 1 AND 2000),
    CONSTRAINT semantic_definition_status_ck CHECK (status IN ('DRAFT', 'APPROVED', 'INACTIVE')),
    CONSTRAINT semantic_definition_origin_ck CHECK (origin IN ('MANUAL', 'AI')),
    CONSTRAINT semantic_definition_actor_type_ck CHECK (created_by_type IN ('HUMAN', 'SERVICE', 'AI')
        AND updated_by_type IN ('HUMAN', 'SERVICE', 'AI')),
    CONSTRAINT semantic_definition_actor_ck CHECK (length(created_by) BETWEEN 1 AND 255
        AND length(updated_by) BETWEEN 1 AND 255),
    CONSTRAINT semantic_definition_not_self_ck CHECK (concept_id IS NULL OR concept_id <> id),
    CONSTRAINT semantic_definition_shape_ck CHECK (
        (kind = 'CONCEPT' AND relation_object_id IS NOT NULL AND column_object_id IS NULL AND concept_id IS NULL
            AND aggregation IS NULL AND dimension_type IS NULL AND granularities IS NULL AND time_zone IS NULL)
        OR (kind = 'METRIC' AND relation_object_id IS NULL AND concept_id IS NOT NULL
            AND aggregation IN ('COUNT', 'SUM', 'AVG')
            AND (column_object_id IS NOT NULL OR aggregation = 'COUNT')
            AND dimension_type IS NULL AND granularities IS NULL AND time_zone IS NULL)
        OR (kind = 'DIMENSION' AND relation_object_id IS NULL AND concept_id IS NOT NULL
            AND column_object_id IS NOT NULL AND aggregation IS NULL
            AND dimension_type IN ('CATEGORICAL', 'TEMPORAL')
            AND (dimension_type = 'TEMPORAL' OR (granularities IS NULL AND time_zone IS NULL)))),
    CONSTRAINT semantic_definition_granularities_ck CHECK (granularities IS NULL
        OR (jsonb_typeof(granularities) = 'array' AND jsonb_array_length(granularities) BETWEEN 1 AND 5)),
    CONSTRAINT semantic_definition_time_zone_ck CHECK (time_zone IS NULL OR length(time_zone) BETWEEN 1 AND 64),
    CONSTRAINT semantic_definition_approval_ck CHECK (
        (status <> 'APPROVED' OR (approved_by IS NOT NULL AND approved_at IS NOT NULL))
        AND (status <> 'DRAFT' OR (approved_by IS NULL AND approved_at IS NULL))),
    CONSTRAINT semantic_definition_inactive_ck CHECK ((status = 'INACTIVE') = (inactive_reason IS NOT NULL)),
    CONSTRAINT semantic_definition_reason_ck CHECK (inactive_reason IS NULL OR length(inactive_reason) BETWEEN 1 AND 500),
    CONSTRAINT semantic_definition_row_version_ck CHECK (row_version >= 0)
);

CREATE INDEX semantic_definition_status_idx ON semantic_definition (tenant_id, data_source_id, status);
CREATE INDEX semantic_definition_concept_idx ON semantic_definition (tenant_id, data_source_id, concept_id);
CREATE INDEX semantic_definition_relation_idx ON semantic_definition (tenant_id, data_source_id, relation_object_id);
CREATE INDEX semantic_definition_column_idx ON semantic_definition (tenant_id, data_source_id, column_object_id);

COMMENT ON TABLE semantic_definition IS 'Semantic catalog: concepts, metrics and dimensions (S3 Basic).';

-- Identity and authorship never change, for any role (same function as V4).
CREATE TRIGGER semantic_definition_immutable_columns
    BEFORE UPDATE ON semantic_definition
    FOR EACH ROW
    WHEN (OLD.id IS DISTINCT FROM NEW.id
       OR OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
       OR OLD.data_source_id IS DISTINCT FROM NEW.data_source_id
       OR OLD.kind IS DISTINCT FROM NEW.kind
       OR OLD.key IS DISTINCT FROM NEW.key
       OR OLD.origin IS DISTINCT FROM NEW.origin
       OR OLD.created_by IS DISTINCT FROM NEW.created_by
       OR OLD.created_by_type IS DISTINCT FROM NEW.created_by_type
       OR OLD.created_at IS DISTINCT FROM NEW.created_at)
    EXECUTE FUNCTION reject_immutable_change();

GRANT SELECT, INSERT ON semantic_definition TO ${appRole};
GRANT UPDATE (name, description, status, relation_object_id, column_object_id, concept_id, aggregation,
              dimension_type, granularities, time_zone, updated_by, updated_by_type, updated_at, approved_by,
              approved_at, inactive_reason, row_version) ON semantic_definition TO ${appRole};
