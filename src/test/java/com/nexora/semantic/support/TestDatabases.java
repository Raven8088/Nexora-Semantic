package com.nexora.semantic.support;

import com.nexora.semantic.infrastructure.migration.SchemaMigrator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;

/**
 * Creates additional databases in the shared test PostgreSQL container laid out like production, following
 * {@code deploy/postgres/create-roles.sql}: a non-superuser migration identity owning schema {@code semantic}, and
 * the application role with CONNECT only. Test-only credentials.
 */
public final class TestDatabases {

    public static final String APP_ROLE = IntegrationTest.APP_ROLE;
    public static final String APP_ROLE_PASSWORD = "app-test-password";
    public static final String MIGRATOR = "nexora_semantic_migrator_it";
    public static final String MIGRATOR_PASSWORD = "migrator-test-password";

    private TestDatabases() {
    }

    /** Creates database {@code name} with the production layout and returns its JDBC URL (no schema migrated). */
    public static synchronized String createProductionLikeDatabase(String name) {
        JdbcClient admin = connect(IntegrationTest.POSTGRES.getJdbcUrl(), superuser(), superuserPassword());
        admin.sql("""
                DO $$ BEGIN
                  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'nexora_semantic_migrator_it') THEN
                    CREATE ROLE nexora_semantic_migrator_it LOGIN PASSWORD 'migrator-test-password'
                      NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS NOINHERIT;
                  END IF;
                END $$""").update();
        admin.sql("CREATE DATABASE " + name).update();

        String url = jdbcUrl(name);
        JdbcClient database = connect(url, superuser(), superuserPassword());
        database.sql("REVOKE ALL ON DATABASE " + name + " FROM PUBLIC").update();
        database.sql("GRANT CONNECT ON DATABASE " + name + " TO " + MIGRATOR).update();
        database.sql("GRANT CONNECT ON DATABASE " + name + " TO " + APP_ROLE).update();
        database.sql("REVOKE CREATE ON SCHEMA public FROM PUBLIC").update();
        database.sql("CREATE SCHEMA semantic AUTHORIZATION " + MIGRATOR).update();
        return url;
    }

    public static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + IntegrationTest.POSTGRES.getHost() + ":"
                + IntegrationTest.POSTGRES.getMappedPort(5432) + "/" + database;
    }

    /** Environment for {@code SchemaMigrationMain} using the migration identity. */
    public static Map<String, String> migrationEnvironment(String url) {
        return Map.of(SchemaMigrator.URL, url,
                SchemaMigrator.USER, MIGRATOR,
                SchemaMigrator.PASSWORD, MIGRATOR_PASSWORD,
                SchemaMigrator.APP_ROLE, APP_ROLE);
    }

    public static JdbcClient connect(String url, String user, String password) {
        return JdbcClient.create(new DriverManagerDataSource(url, user, password));
    }

    public static JdbcClient asApplication(String url) {
        return connect(url + "?currentSchema=semantic", APP_ROLE, APP_ROLE_PASSWORD);
    }

    public static JdbcClient asMigrator(String url) {
        return connect(url + "?currentSchema=semantic", MIGRATOR, MIGRATOR_PASSWORD);
    }

    public static JdbcClient asSuperuser(String url) {
        return connect(url + "?currentSchema=semantic", superuser(), superuserPassword());
    }

    private static String superuser() {
        return IntegrationTest.POSTGRES.getUsername();
    }

    private static String superuserPassword() {
        return IntegrationTest.POSTGRES.getPassword();
    }
}
