package com.nexora.semantic.infrastructure.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static com.nexora.semantic.infrastructure.migration.SchemaMigrationMain.INVALID_CONFIGURATION;
import static org.assertj.core.api.Assertions.assertThat;

/** Configuration validation of the standalone migration job; no database is contacted. */
class SchemaMigrationMainTest {

    private static final String SECRET = "Sup3r-Secret-Value";

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private static Map<String, String> validEnv() {
        Map<String, String> env = new HashMap<>();
        env.put(SchemaMigrator.URL, "jdbc:postgresql://db.internal:5432/nexora_semantic");
        env.put(SchemaMigrator.USER, "nexora_semantic_migrator");
        env.put(SchemaMigrator.PASSWORD, SECRET);
        env.put(SchemaMigrator.APP_ROLE, "nexora_semantic_app");
        return env;
    }

    private int run(Map<String, String> env, String... args) {
        return SchemaMigrationMain.run(args, env, new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));
    }

    private String errors() {
        return err.toString(StandardCharsets.UTF_8);
    }

    @ParameterizedTest
    @ValueSource(strings = {SchemaMigrator.URL, SchemaMigrator.USER, SchemaMigrator.APP_ROLE})
    void missingRequiredVariableIsAConfigurationError(String variable) {
        Map<String, String> env = validEnv();
        env.remove(variable);
        assertThat(run(env)).isEqualTo(INVALID_CONFIGURATION);
        assertThat(errors()).contains(variable + " is required").doesNotContain(SECRET);
    }

    @Test
    void passwordIsRequired() {
        Map<String, String> env = validEnv();
        env.remove(SchemaMigrator.PASSWORD);
        assertThat(run(env)).isEqualTo(INVALID_CONFIGURATION);
        assertThat(errors()).contains("Set exactly one of");
    }

    @Test
    void passwordAndPasswordFileAreMutuallyExclusive(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("pw"), SECRET);
        Map<String, String> env = validEnv();
        env.put(SchemaMigrator.PASSWORD_FILE, file.toString());
        assertThat(run(env)).isEqualTo(INVALID_CONFIGURATION);
        assertThat(errors()).contains("Set exactly one of").doesNotContain(SECRET);
    }

    @Test
    void unreadableOrEmptyPasswordFileIsRejected(@TempDir Path dir) throws Exception {
        Map<String, String> env = validEnv();
        env.remove(SchemaMigrator.PASSWORD);
        env.put(SchemaMigrator.PASSWORD_FILE, dir.resolve("missing").toString());
        assertThat(run(env)).isEqualTo(INVALID_CONFIGURATION);
        assertThat(errors()).contains("cannot be read");

        env.put(SchemaMigrator.PASSWORD_FILE, Files.writeString(dir.resolve("empty"), "\n").toString());
        assertThat(run(env)).isEqualTo(INVALID_CONFIGURATION);
        assertThat(errors()).contains("empty file");
    }

    @Test
    void migrationIdentityMustDifferFromApplicationRole() {
        Map<String, String> env = validEnv();
        env.put(SchemaMigrator.USER, "nexora_semantic_app");
        assertThat(run(env)).isEqualTo(INVALID_CONFIGURATION);
        assertThat(errors()).contains("must be different roles");
    }

    @ParameterizedTest
    @ValueSource(strings = {"App", "app; DROP TABLE tenant", "app\"x", "1app", "app-role", ""})
    void applicationRoleMustBeASafeIdentifier(String role) {
        Map<String, String> env = validEnv();
        env.put(SchemaMigrator.APP_ROLE, role);
        assertThat(run(env)).isEqualTo(INVALID_CONFIGURATION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"jdbc:mysql://db/x", "postgresql://db/x",
            "jdbc:postgresql://db/x?user=u&password=" + SECRET})
    void urlMustBePostgresWithoutEmbeddedPassword(String url) {
        Map<String, String> env = validEnv();
        env.put(SchemaMigrator.URL, url);
        assertThat(run(env)).isEqualTo(INVALID_CONFIGURATION);
        assertThat(errors()).doesNotContain(SECRET);
    }

    @ParameterizedTest
    @ValueSource(strings = {"clean", "repair", "baseline", "drop"})
    void onlyMigrateAndValidateAreSupported(String command) {
        assertThat(run(validEnv(), command)).isEqualTo(INVALID_CONFIGURATION);
        assertThat(errors()).contains("Usage");
    }

    @Test
    void extraArgumentsAreRejected() {
        assertThat(run(validEnv(), "migrate", "--force")).isEqualTo(INVALID_CONFIGURATION);
    }
}
