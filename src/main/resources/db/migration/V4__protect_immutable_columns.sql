-- Audit finding I1: protect immutable columns of tenant and data_source.
-- Two independent layers:
--   1. Column-level privileges: the application role may only UPDATE the mutable columns.
--   2. Triggers: any role, including the owner, is rejected when an immutable column changes value.
-- In particular data_source.tenant_id can never change, so a data source cannot move to another tenant.

REVOKE UPDATE ON tenant FROM ${appRole};
GRANT UPDATE (name, status, updated_at, updated_by, row_version) ON tenant TO ${appRole};

REVOKE UPDATE ON data_source FROM ${appRole};
GRANT UPDATE (name, description, status, updated_at, updated_by, row_version) ON data_source TO ${appRole};

CREATE FUNCTION reject_immutable_change() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'immutable columns of % cannot be changed', TG_TABLE_NAME USING ERRCODE = 'insufficient_privilege';
END;
$$;

COMMENT ON FUNCTION reject_immutable_change() IS 'Trigger function rejecting changes to immutable columns.';

CREATE TRIGGER tenant_immutable_columns
    BEFORE UPDATE ON tenant
    FOR EACH ROW
    WHEN (OLD.id IS DISTINCT FROM NEW.id
       OR OLD.key IS DISTINCT FROM NEW.key
       OR OLD.created_at IS DISTINCT FROM NEW.created_at
       OR OLD.created_by IS DISTINCT FROM NEW.created_by)
    EXECUTE FUNCTION reject_immutable_change();

CREATE TRIGGER data_source_immutable_columns
    BEFORE UPDATE ON data_source
    FOR EACH ROW
    WHEN (OLD.id IS DISTINCT FROM NEW.id
       OR OLD.tenant_id IS DISTINCT FROM NEW.tenant_id
       OR OLD.key IS DISTINCT FROM NEW.key
       OR OLD.engine IS DISTINCT FROM NEW.engine
       OR OLD.database_name IS DISTINCT FROM NEW.database_name
       OR OLD.created_at IS DISTINCT FROM NEW.created_at
       OR OLD.created_by IS DISTINCT FROM NEW.created_by)
    EXECUTE FUNCTION reject_immutable_change();
