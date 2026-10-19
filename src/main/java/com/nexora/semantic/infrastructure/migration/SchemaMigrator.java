package com.nexora.semantic.infrastructure.migration;

import org.flywaydb.core.Flyway;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Builds the Flyway instance used to migrate production databases outside the application (audit finding I3).
 * Settings mirror {@code spring.flyway.*} in {@code application.yml} (schema, location, {@code appRole}
 * placeholder), so development, tests and production apply identical migrations; a test compares the resulting
 * schema histories.
 * <p>
 * Configuration comes only from environment variables, never from files in the artifact:
 * <ul>
 *   <li>{@value #URL}: JDBC URL ({@code jdbc:postgresql:...}) without credentials;</li>
 *   <li>{@value #USER}: migration identity, owner of the {@code semantic} schema;</li>
 *   <li>{@value #PASSWORD_FILE} (recommended, e.g. a mounted secret) or {@value #PASSWORD};</li>
 *   <li>{@value #APP_ROLE}: application role granted DML privileges by the migrations; must differ from the
 *       migration identity.</li>
 * </ul>
 * The password is only held in local variables and handed to Flyway; it is never stored in fields or logged.
 */
public final class SchemaMigrator {

    public static final String URL = "SEMANTIC_MIGRATION_DB_URL";
    public static final String USER = "SEMANTIC_MIGRATION_DB_USER";
    public static final String PASSWORD = "SEMANTIC_MIGRATION_DB_PASSWORD";
    public static final String PASSWORD_FILE = "SEMANTIC_MIGRATION_DB_PASSWORD_FILE";
    public static final String APP_ROLE = "SEMANTIC_DB_APP_USER";

    public static final String SCHEMA = "semantic";
    public static final String LOCATION = "classpath:db/migration";
    public static final String APP_ROLE_PLACEHOLDER = "appRole";

    /** Unquoted PostgreSQL identifier: the placeholder is inserted into GRANT statements, so it is validated. */
    private static final Pattern ROLE_NAME = Pattern.compile("[a-z_][a-z0-9_]{0,62}");

    private SchemaMigrator() {
    }

    /** @throws MigrationConfigurationException if a variable is missing or invalid */
    public static Flyway fromEnvironment(Map<String, String> env) {
        String url = required(env, URL);
        if (!url.startsWith("jdbc:postgresql:")) {
            throw new MigrationConfigurationException(URL + " must be a PostgreSQL JDBC URL (jdbc:postgresql:...)");
        }
        if (url.toLowerCase(Locale.ROOT).contains("password=")) {
            throw new MigrationConfigurationException(
                    URL + " must not contain a password; use " + PASSWORD_FILE + " or " + PASSWORD);
        }
        String user = required(env, USER);
        String appRole = required(env, APP_ROLE);
        if (!ROLE_NAME.matcher(appRole).matches()) {
            throw new MigrationConfigurationException(APP_ROLE + " must be a lowercase PostgreSQL role name");
        }
        if (user.equals(appRole)) {
            throw new MigrationConfigurationException(
                    USER + " and " + APP_ROLE + " must be different roles: the application must not own the schema");
        }
        return create(url, user, password(env), appRole);
    }

    static Flyway create(String url, String user, String password, String appRole) {
        return Flyway.configure()
                .dataSource(url, user, password)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .locations(LOCATION)
                .failOnMissingLocations(true)
                .placeholders(Map.of(APP_ROLE_PLACEHOLDER, appRole))
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .load();
    }

    private static String password(Map<String, String> env) {
        String inline = env.get(PASSWORD);
        String file = env.get(PASSWORD_FILE);
        boolean hasInline = inline != null && !inline.isEmpty();
        boolean hasFile = file != null && !file.isBlank();
        if (hasInline == hasFile) {
            throw new MigrationConfigurationException("Set exactly one of " + PASSWORD_FILE + " or " + PASSWORD);
        }
        if (hasInline) {
            return inline;
        }
        try {
            String content = Files.readString(Path.of(file.strip()), StandardCharsets.UTF_8);
            String secret = content.endsWith("\r\n") ? content.substring(0, content.length() - 2)
                    : content.endsWith("\n") ? content.substring(0, content.length() - 1) : content;
            if (secret.isEmpty()) {
                throw new MigrationConfigurationException(PASSWORD_FILE + " points to an empty file");
            }
            return secret;
        } catch (IOException e) {
            throw new MigrationConfigurationException(PASSWORD_FILE + " cannot be read");
        }
    }

    private static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new MigrationConfigurationException(name + " is required");
        }
        return value.strip();
    }
}
