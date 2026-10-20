package com.nexora.semantic.infrastructure.migration;

import com.nexora.semantic.infrastructure.persistence.LeastPrivilegeVerifier;
import com.nexora.semantic.support.IntegrationTest;
import com.nexora.semantic.support.TestDatabases;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Audit finding I3: the standalone migration job against a database laid out like production
 * ({@code deploy/postgres/create-roles.sql}) with a non-superuser migration identity, and the resulting privileges
 * of the application role.
 */
class SchemaMigrationIntegrationTest extends IntegrationTest {

    private static String url;
    private static String firstRunOutput;

    @BeforeAll
    static void migrateProductionLikeDatabase() {
        url = TestDatabases.createProductionLikeDatabase("semantic_migration_it");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exit = SchemaMigrationMain.run(new String[]{"migrate"}, TestDatabases.migrationEnvironment(url),
                new PrintStream(out, true, StandardCharsets.UTF_8), System.err);
        assertThat(exit).isEqualTo(SchemaMigrationMain.OK);
        firstRunOutput = out.toString(StandardCharsets.UTF_8);
    }

    private static int run(Map<String, String> env, String command, ByteArrayOutputStream out,
                           ByteArrayOutputStream err) {
        return SchemaMigrationMain.run(new String[]{command}, env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private static List<String> history(JdbcClient jdbc) {
        return jdbc.sql("""
                        SELECT version || ':' || checksum || ':' || success FROM semantic.flyway_schema_history
                         WHERE version IS NOT NULL ORDER BY installed_rank""")
                .query(String.class).list();
    }

    @Test
    void appliesAllMigrationsWithTheMigrationIdentity() {
        assertThat(firstRunOutput).contains("Applied 5 migration(s)").contains("current version: 5");
        JdbcClient admin = TestDatabases.asSuperuser(url);
        assertThat(admin.sql("SELECT DISTINCT installed_by FROM flyway_schema_history").query(String.class).list())
                .containsExactly(TestDatabases.MIGRATOR);
        assertThat(admin.sql("""
                        SELECT DISTINCT pg_get_userbyid(c.relowner) FROM pg_class c
                          JOIN pg_namespace n ON n.oid = c.relnamespace
                         WHERE n.nspname = 'semantic' AND c.relkind = 'r'""").query(String.class).list())
                .containsExactly(TestDatabases.MIGRATOR);
        assertThat(admin.sql("SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname = :r")
                .param("r", TestDatabases.MIGRATOR).query(Boolean.class).single()).isFalse();
    }

    @Test
    void producesTheSameMigrationsAsDevelopmentAndTests() {
        List<String> production = history(TestDatabases.asSuperuser(url));
        List<String> springManaged = history(ownerJdbc());
        assertThat(production).hasSize(5).isEqualTo(springManaged);
    }

    @Test
    void preservesSecurityObjects() {
        List<String> triggers = TestDatabases.asSuperuser(url).sql("""
                        SELECT tgname FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
                          JOIN pg_namespace n ON n.oid = c.relnamespace
                         WHERE n.nspname = 'semantic' AND NOT t.tgisinternal ORDER BY tgname""")
                .query(String.class).list();
        assertThat(triggers).containsExactly("audit_event_no_truncate", "audit_event_no_update_delete",
                "data_source_immutable_columns", "metadata_snapshot_no_truncate", "metadata_snapshot_no_update_delete",
                "physical_object_immutable_columns", "tenant_immutable_columns");
    }

    @Test
    void rerunIsIdempotentAndValidatePasses() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThat(run(TestDatabases.migrationEnvironment(url), "migrate", out, new ByteArrayOutputStream()))
                .isEqualTo(SchemaMigrationMain.OK);
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Applied 0 migration(s)");

        ByteArrayOutputStream validateOut = new ByteArrayOutputStream();
        assertThat(run(TestDatabases.migrationEnvironment(url), "validate", validateOut, new ByteArrayOutputStream()))
                .isEqualTo(SchemaMigrationMain.OK);
        assertThat(validateOut.toString(StandardCharsets.UTF_8)).contains("is valid; current version: 5");
    }

    @Test
    void passwordFileIsSupported(@TempDir Path dir) throws Exception {
        Path secret = Files.writeString(dir.resolve("migrator-password"), TestDatabases.MIGRATOR_PASSWORD + "\n");
        Map<String, String> env = new HashMap<>(TestDatabases.migrationEnvironment(url));
        env.remove(SchemaMigrator.PASSWORD);
        env.put(SchemaMigrator.PASSWORD_FILE, secret.toString());
        assertThat(run(env, "validate", new ByteArrayOutputStream(), new ByteArrayOutputStream()))
                .isEqualTo(SchemaMigrationMain.OK);
    }

    @Test
    void wrongCredentialsFailWithoutLeakingThem() {
        Map<String, String> env = new HashMap<>(TestDatabases.migrationEnvironment(url));
        env.put(SchemaMigrator.PASSWORD, "wrong-password-4711");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        assertThat(run(env, "migrate", out, err)).isEqualTo(SchemaMigrationMain.FAILED);
        assertThat(err.toString(StandardCharsets.UTF_8) + out.toString(StandardCharsets.UTF_8))
                .doesNotContain("wrong-password-4711");
    }

    @Test
    void theApplicationRoleCannotBeUsedAsMigrationIdentity() {
        Map<String, String> env = new HashMap<>(TestDatabases.migrationEnvironment(url));
        env.put(SchemaMigrator.USER, TestDatabases.APP_ROLE);
        env.put(SchemaMigrator.PASSWORD, TestDatabases.APP_ROLE_PASSWORD);
        assertThat(run(env, "migrate", new ByteArrayOutputStream(), new ByteArrayOutputStream()))
                .isEqualTo(SchemaMigrationMain.INVALID_CONFIGURATION);
    }

    // --- Privileges of the application role in the production layout ---

    @ParameterizedTest
    @ValueSource(strings = {
            "CREATE TABLE semantic.intruder (id int)",
            "CREATE TABLE public.intruder (id int)",
            "CREATE SCHEMA intruder",
            "ALTER TABLE semantic.data_source ADD COLUMN host text",
            "ALTER TABLE semantic.tenant DISABLE TRIGGER tenant_immutable_columns",
            "DROP TABLE semantic.audit_event",
            "DROP TRIGGER audit_event_no_update_delete ON semantic.audit_event",
            "CREATE INDEX intruder_idx ON semantic.tenant (name)",
            "CREATE FUNCTION semantic.intruder() RETURNS int LANGUAGE sql AS 'SELECT 1'",
            "CREATE OR REPLACE FUNCTION semantic.reject_mutation() RETURNS trigger LANGUAGE plpgsql AS 'BEGIN RETURN NEW; END'",
            "CREATE TRIGGER intruder BEFORE INSERT ON semantic.tenant FOR EACH ROW EXECUTE FUNCTION semantic.reject_mutation()",
            "CREATE VIEW semantic.intruder AS SELECT 1",
            "TRUNCATE semantic.tenant",
            "COMMENT ON TABLE semantic.tenant IS 'changed'"})
    void applicationRoleCannotExecuteDdl(String statement) {
        assertThatThrownBy(() -> TestDatabases.asApplication(url).sql(statement).update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().message().containsAnyOf("permission denied", "must be owner");
    }

    @Test
    void applicationRoleCanStillPerformItsDmlOperations() {
        JdbcClient app = TestDatabases.asApplication(url);
        UUID id = UUID.randomUUID();
        app.sql("""
                        INSERT INTO tenant (id, key, name, status, created_at, created_by, updated_at, updated_by)
                        VALUES (:id, :key, 'Prod layout', 'ACTIVE', now(), 'it', now(), 'it')""")
                .param("id", id).param("key", "prod-" + id.toString().substring(0, 8)).update();
        assertThat(app.sql("UPDATE tenant SET name = 'Renamed', row_version = row_version + 1 WHERE id = :id")
                .param("id", id).update()).isEqualTo(1);
        assertThat(app.sql("SELECT name FROM tenant WHERE id = :id").param("id", id).query(String.class).single())
                .isEqualTo("Renamed");
    }

    // --- LeastPrivilegeVerifier against real roles ---

    @Test
    void verifierAcceptsTheApplicationRole() {
        assertThat(new LeastPrivilegeVerifier(TestDatabases.asApplication(url), "semantic").violations()).isEmpty();
        assertThat(new LeastPrivilegeVerifier(appJdbc, "semantic").violations())
                .as("application role of the development/test database").isEmpty();
    }

    @Test
    void verifierRejectsTheMigrationIdentityAndSuperusers() {
        assertThat(new LeastPrivilegeVerifier(TestDatabases.asMigrator(url), "semantic").violations())
                .contains("role owns (or inherits ownership of) schema semantic",
                        "role can CREATE objects in schema semantic",
                        "role owns (or inherits ownership of) tables in schema semantic",
                        "role owns (or inherits ownership of) functions in schema semantic");
        assertThat(new LeastPrivilegeVerifier(TestDatabases.asSuperuser(url), "semantic").violations())
                .contains("role is SUPERUSER");
        assertThatThrownBy(() -> new LeastPrivilegeVerifier(TestDatabases.asMigrator(url), "semantic").verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("least privilege");
    }

    @Test
    void verifierReportsAMissingSchema() {
        assertThat(new LeastPrivilegeVerifier(TestDatabases.asApplication(url), "not_migrated").violations())
                .containsExactly("schema not_migrated does not exist (have the migrations been applied?)");
    }
}
