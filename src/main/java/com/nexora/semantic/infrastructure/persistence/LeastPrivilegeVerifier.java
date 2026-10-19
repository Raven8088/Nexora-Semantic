package com.nexora.semantic.infrastructure.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Verifies that the application's database connection has no capability to change database structure (audit
 * finding I3). It checks the connected role, including privileges inherited from roles it belongs to:
 * <ul>
 *   <li>no SUPERUSER, CREATEROLE, CREATEDB or BYPASSRLS attribute;</li>
 *   <li>not owner (nor member of the owner) of the schema or of any table or function in it;</li>
 *   <li>no CREATE privilege on the database, the schema or {@code public};</li>
 *   <li>no TRIGGER or TRUNCATE privilege on any table of the schema.</li>
 * </ul>
 * Temporary tables (the TEMP database privilege) are not structural changes and are not checked.
 */
public class LeastPrivilegeVerifier {

    private static final String ROLE_CHECKS = """
            SELECT rolsuper, rolcreaterole, rolcreatedb, rolbypassrls,
                   has_database_privilege(current_user, current_database(), 'CREATE') AS db_create,
                   has_schema_privilege(current_user, 'public', 'CREATE') AS public_create
              FROM pg_roles WHERE rolname = current_user""";

    private static final String SCHEMA_CHECKS = """
            SELECT pg_has_role(current_user, n.nspowner, 'USAGE') AS schema_owner,
                   has_schema_privilege(current_user, n.oid, 'CREATE') AS schema_create,
                   EXISTS (SELECT 1 FROM pg_class c
                            WHERE c.relnamespace = n.oid
                              AND pg_has_role(current_user, c.relowner, 'USAGE')) AS owns_relations,
                   EXISTS (SELECT 1 FROM pg_proc p
                            WHERE p.pronamespace = n.oid
                              AND pg_has_role(current_user, p.proowner, 'USAGE')) AS owns_functions,
                   EXISTS (SELECT 1 FROM pg_class c
                            WHERE c.relnamespace = n.oid AND c.relkind IN ('r', 'p')
                              AND (has_table_privilege(current_user, c.oid, 'TRIGGER')
                                   OR has_table_privilege(current_user, c.oid, 'TRUNCATE'))) AS table_ddl
              FROM pg_namespace n WHERE n.nspname = :schema""";

    private final JdbcClient jdbc;
    private final String schema;

    public LeastPrivilegeVerifier(JdbcClient jdbc, String schema) {
        this.jdbc = jdbc;
        this.schema = schema;
    }

    /** Human-readable violations; empty when the connection is least-privileged. */
    public List<String> violations() {
        List<String> violations = new ArrayList<>();
        Map<String, Object> role = jdbc.sql(ROLE_CHECKS).query().singleRow();
        addIf(violations, role, "rolsuper", "role is SUPERUSER");
        addIf(violations, role, "rolcreaterole", "role has CREATEROLE");
        addIf(violations, role, "rolcreatedb", "role has CREATEDB");
        addIf(violations, role, "rolbypassrls", "role has BYPASSRLS");
        addIf(violations, role, "db_create", "role can CREATE schemas in the database");
        addIf(violations, role, "public_create", "role can CREATE objects in schema public");

        List<Map<String, Object>> rows = jdbc.sql(SCHEMA_CHECKS).param("schema", schema).query().listOfRows();
        if (rows.isEmpty()) {
            violations.add("schema " + schema + " does not exist (have the migrations been applied?)");
            return violations;
        }
        Map<String, Object> checks = rows.getFirst();
        addIf(violations, checks, "schema_owner", "role owns (or inherits ownership of) schema " + schema);
        addIf(violations, checks, "schema_create", "role can CREATE objects in schema " + schema);
        addIf(violations, checks, "owns_relations", "role owns (or inherits ownership of) tables in schema " + schema);
        addIf(violations, checks, "owns_functions", "role owns (or inherits ownership of) functions in schema " + schema);
        addIf(violations, checks, "table_ddl", "role has TRIGGER or TRUNCATE privileges in schema " + schema);
        return violations;
    }

    /** @throws IllegalStateException listing every violation */
    public void verify() {
        List<String> violations = violations();
        if (!violations.isEmpty()) {
            throw new IllegalStateException("The application database role must not be able to change the database "
                    + "structure (least privilege): " + String.join("; ", violations));
        }
    }

    private static void addIf(List<String> violations, Map<String, Object> row, String column, String message) {
        if (Boolean.TRUE.equals(row.get(column))) {
            violations.add(message);
        }
    }
}
