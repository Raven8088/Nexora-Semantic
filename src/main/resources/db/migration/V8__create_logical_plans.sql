-- S4.2: persisted logical plans. A plan is an immutable, self-contained document (definitions, relationships,
-- column pairs, evidence and physical revisions are copied into it), plus typed dependency rows that let the
-- validity check compare it with the current catalog. Both tables are append-only for every role.
-- Composite foreign keys (tenant_id, data_source_id, ...) keep a plan and everything it references inside one
-- tenant and one data source. No business data, credentials or SQL are stored.

CREATE TABLE logical_plan (
    id                     uuid        PRIMARY KEY,
    tenant_id              uuid        NOT NULL,
    data_source_id         uuid        NOT NULL,
    format_version         text        NOT NULL,
    fingerprint            text        NOT NULL,
    resolution_fingerprint text        NOT NULL,
    metadata_snapshot_id   uuid,
    request                jsonb       NOT NULL,
    plan                   jsonb       NOT NULL,
    created_by             text        NOT NULL,
    created_by_type        text        NOT NULL,
    created_at             timestamptz NOT NULL,
    CONSTRAINT logical_plan_data_source_fk FOREIGN KEY (tenant_id, data_source_id)
        REFERENCES data_source (tenant_id, id),
    CONSTRAINT logical_plan_scope_uk UNIQUE (tenant_id, data_source_id, id),
    CONSTRAINT logical_plan_snapshot_fk FOREIGN KEY (tenant_id, data_source_id, metadata_snapshot_id)
        REFERENCES metadata_snapshot (tenant_id, data_source_id, id),
    CONSTRAINT logical_plan_format_ck CHECK (format_version ~ '^nexora-logical-plan/[0-9]{1,4}$'),
    CONSTRAINT logical_plan_fingerprint_ck CHECK (fingerprint ~ '^[0-9a-f]{64}$'
        AND resolution_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT logical_plan_document_ck CHECK (jsonb_typeof(plan) = 'object' AND jsonb_typeof(request) = 'object'),
    CONSTRAINT logical_plan_actor_ck CHECK (length(created_by) BETWEEN 1 AND 255
        AND created_by_type IN ('HUMAN', 'SERVICE', 'AI'))
);

CREATE INDEX logical_plan_scope_idx ON logical_plan (tenant_id, data_source_id, created_at);

COMMENT ON TABLE logical_plan IS 'Immutable logical plans (S4.2): no SQL, no business data.';

CREATE TRIGGER logical_plan_no_update_delete
    BEFORE UPDATE OR DELETE ON logical_plan
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER logical_plan_no_truncate
    BEFORE TRUNCATE ON logical_plan
    FOR EACH STATEMENT EXECUTE FUNCTION reject_mutation();

GRANT SELECT, INSERT ON logical_plan TO ${appRole};

-- What a plan depends on, with the revision observed. Exactly one typed reference per row.
CREATE TABLE logical_plan_dependency (
    tenant_id       uuid   NOT NULL,
    data_source_id  uuid   NOT NULL,
    plan_id         uuid   NOT NULL,
    kind            text   NOT NULL,
    reference_id    uuid   NOT NULL,
    definition_id   uuid,
    relationship_id uuid,
    object_id       uuid,
    revision        bigint,
    fingerprint     text,
    data_type       text,
    navigation      text,
    CONSTRAINT logical_plan_dependency_pk PRIMARY KEY (plan_id, kind, reference_id),
    CONSTRAINT logical_plan_dependency_plan_fk FOREIGN KEY (tenant_id, data_source_id, plan_id)
        REFERENCES logical_plan (tenant_id, data_source_id, id),
    CONSTRAINT logical_plan_dependency_definition_fk FOREIGN KEY (tenant_id, data_source_id, definition_id)
        REFERENCES semantic_definition (tenant_id, data_source_id, id),
    CONSTRAINT logical_plan_dependency_relationship_fk FOREIGN KEY (tenant_id, data_source_id, relationship_id)
        REFERENCES semantic_relationship (tenant_id, data_source_id, id),
    CONSTRAINT logical_plan_dependency_object_fk FOREIGN KEY (tenant_id, data_source_id, object_id)
        REFERENCES physical_object (tenant_id, data_source_id, id),
    CONSTRAINT logical_plan_dependency_shape_ck CHECK (
        (kind = 'DEFINITION' AND definition_id = reference_id AND relationship_id IS NULL AND object_id IS NULL
            AND revision IS NOT NULL AND navigation IS NULL)
        OR (kind = 'RELATIONSHIP' AND relationship_id = reference_id AND definition_id IS NULL AND object_id IS NULL
            AND revision IS NOT NULL AND navigation IN ('FORWARD', 'REVERSE'))
        OR (kind = 'PHYSICAL_OBJECT' AND object_id = reference_id AND definition_id IS NULL
            AND relationship_id IS NULL AND revision IS NULL AND fingerprint ~ '^[0-9a-f]{64}$'
            AND navigation IS NULL))
);

CREATE INDEX logical_plan_dependency_scope_idx ON logical_plan_dependency (tenant_id, data_source_id, plan_id);

COMMENT ON TABLE logical_plan_dependency IS 'Revisions each logical plan depends on (S4.2 validity check).';

CREATE TRIGGER logical_plan_dependency_no_update_delete
    BEFORE UPDATE OR DELETE ON logical_plan_dependency
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER logical_plan_dependency_no_truncate
    BEFORE TRUNCATE ON logical_plan_dependency
    FOR EACH STATEMENT EXECUTE FUNCTION reject_mutation();

GRANT SELECT, INSERT ON logical_plan_dependency TO ${appRole};
