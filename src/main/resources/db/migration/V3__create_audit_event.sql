-- Append-only audit trail. Protected twice: the application role only has SELECT/INSERT, and triggers reject
-- UPDATE, DELETE and TRUNCATE from any role, including the owner.

CREATE TABLE audit_event (
    id             uuid        PRIMARY KEY,
    tenant_id      uuid        NOT NULL,
    occurred_at    timestamptz NOT NULL,
    actor_id       text        NOT NULL,
    actor_type     text        NOT NULL,
    action         text        NOT NULL,
    target_type    text        NOT NULL,
    target_id      uuid        NOT NULL,
    correlation_id text,
    details        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT audit_event_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenant (id),
    CONSTRAINT audit_event_actor_id_ck CHECK (length(actor_id) BETWEEN 1 AND 255),
    CONSTRAINT audit_event_actor_type_ck CHECK (actor_type IN ('HUMAN', 'SERVICE', 'AI')),
    CONSTRAINT audit_event_action_ck CHECK (action ~ '^[A-Z][A-Z_]{2,63}$'),
    CONSTRAINT audit_event_target_type_ck CHECK (target_type ~ '^[A-Z][A-Z_]{2,63}$'),
    CONSTRAINT audit_event_correlation_ck CHECK (correlation_id IS NULL OR length(correlation_id) BETWEEN 1 AND 64),
    CONSTRAINT audit_event_details_ck CHECK (jsonb_typeof(details) = 'object')
);

CREATE INDEX audit_event_target_idx ON audit_event (tenant_id, target_type, target_id, occurred_at DESC);
CREATE INDEX audit_event_time_idx ON audit_event (tenant_id, occurred_at DESC);

COMMENT ON TABLE audit_event IS 'Append-only record of decisions and modifications.';

CREATE FUNCTION reject_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'table % is append-only', TG_TABLE_NAME USING ERRCODE = 'insufficient_privilege';
END;
$$;

COMMENT ON FUNCTION reject_mutation() IS 'Trigger function for append-only and immutable tables.';

CREATE TRIGGER audit_event_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TRIGGER audit_event_no_truncate
    BEFORE TRUNCATE ON audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION reject_mutation();

GRANT SELECT, INSERT ON audit_event TO ${appRole};
