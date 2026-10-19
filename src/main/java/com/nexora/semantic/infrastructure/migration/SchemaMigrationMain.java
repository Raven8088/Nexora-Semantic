package com.nexora.semantic.infrastructure.migration;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.output.MigrateResult;
import org.flywaydb.core.api.output.ValidateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.PrintStream;
import java.util.Map;

/**
 * Standalone production migration job (audit finding I3). Runs without a Spring context, with its own database
 * identity, from the same artifact as the application:
 * <pre>
 * java -cp nexora-semantic.jar \
 *      -Dloader.main=com.nexora.semantic.infrastructure.migration.SchemaMigrationMain \
 *      org.springframework.boot.loader.launch.PropertiesLauncher [migrate|validate]
 * </pre>
 * Configuration: see {@link SchemaMigrator}. Exit codes: {@value #OK} success, {@value #FAILED} migration or
 * validation failure, {@value #INVALID_CONFIGURATION} invalid configuration or arguments.
 */
public final class SchemaMigrationMain {

    public static final int OK = 0;
    public static final int FAILED = 1;
    public static final int INVALID_CONFIGURATION = 2;

    private SchemaMigrationMain() {
    }

    public static void main(String[] args) {
        quietLibraryLogging();
        System.exit(run(args, System.getenv(), System.out, System.err));
    }

    /** Testable entry point. Never prints secrets: messages come from validated settings or Flyway. */
    static int run(String[] args, Map<String, String> env, PrintStream out, PrintStream err) {
        String command = args.length == 0 ? "migrate" : args[0];
        if (args.length > 1 || !(command.equals("migrate") || command.equals("validate"))) {
            err.println("Usage: SchemaMigrationMain [migrate|validate]");
            return INVALID_CONFIGURATION;
        }
        Flyway flyway;
        try {
            flyway = SchemaMigrator.fromEnvironment(env);
        } catch (MigrationConfigurationException e) {
            err.println("Invalid migration configuration: " + e.getMessage());
            return INVALID_CONFIGURATION;
        }
        try {
            return command.equals("migrate") ? migrate(flyway, out) : validate(flyway, out, err);
        } catch (FlywayException e) {
            err.println("Schema " + command + " failed: " + e.getMessage());
            return FAILED;
        }
    }

    private static int migrate(Flyway flyway, PrintStream out) {
        MigrateResult result = flyway.migrate();
        out.println("Applied " + result.migrationsExecuted + " migration(s) to schema " + SchemaMigrator.SCHEMA
                + "; current version: " + currentVersion(flyway));
        return OK;
    }

    private static String currentVersion(Flyway flyway) {
        var current = flyway.info().current();
        return current == null ? "none" : current.getVersion().getVersion();
    }

    private static int validate(Flyway flyway, PrintStream out, PrintStream err) {
        ValidateResult result = flyway.validateWithResult();
        if (!result.validationSuccessful) {
            err.println("Schema validation failed: " + result.getAllErrorMessages());
            return FAILED;
        }
        out.println("Schema " + SchemaMigrator.SCHEMA + " is valid; current version: " + currentVersion(flyway));
        return OK;
    }

    /** Outside Spring Boot, Logback defaults to DEBUG; keep the job output to INFO. */
    private static void quietLibraryLogging() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            context.getLogger(Logger.ROOT_LOGGER_NAME).setLevel(Level.INFO);
        }
    }
}
