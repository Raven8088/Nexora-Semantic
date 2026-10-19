package com.nexora.semantic.infrastructure.config;

import com.nexora.semantic.infrastructure.migration.SchemaMigrator;
import com.nexora.semantic.infrastructure.persistence.LeastPrivilegeVerifier;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.util.StringUtils;

/**
 * Production safeguard (audit finding I3), enabled by {@value #PROPERTY}{@code =true} in the {@code prod} profile.
 * The application refuses to start when:
 * <ol>
 *   <li>Flyway is enabled or migration (schema owner) credentials are configured: checked before any bean is
 *       created, so Flyway can never run first. Production migrations run only as a separate job
 *       ({@code SchemaMigrationMain});</li>
 *   <li>its database role can change the database structure ({@link LeastPrivilegeVerifier}).</li>
 * </ol>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = LeastPrivilegeConfiguration.PROPERTY, havingValue = "true")
public class LeastPrivilegeConfiguration {

    public static final String PROPERTY = "nexora.semantic.database.enforce-least-privilege";

    @Bean
    static MigrationCapabilityCheck migrationCapabilityCheck() {
        return new MigrationCapabilityCheck();
    }

    @Bean
    LeastPrivilegeVerifier leastPrivilegeVerifier(JdbcClient jdbcClient) {
        LeastPrivilegeVerifier verifier = new LeastPrivilegeVerifier(jdbcClient, SchemaMigrator.SCHEMA);
        verifier.verify();
        return verifier;
    }

    static void requireNoMigrationCapability(Environment environment) {
        if (environment.getProperty("spring.flyway.enabled", Boolean.class, Boolean.TRUE)) {
            throw new IllegalStateException(PROPERTY + " requires spring.flyway.enabled=false: production "
                    + "migrations run as a separate job, never during application startup");
        }
        if (StringUtils.hasText(environment.getProperty("spring.flyway.password"))
                || StringUtils.hasText(environment.getProperty("spring.flyway.url"))) {
            throw new IllegalStateException("Migration (schema owner) credentials must not be configured for the "
                    + "application when " + PROPERTY + " is enabled");
        }
    }

    /** Runs before bean instantiation, hence before Flyway's auto-configured migration could start. */
    static final class MigrationCapabilityCheck implements BeanFactoryPostProcessor, EnvironmentAware {

        private Environment environment;

        @Override
        public void setEnvironment(Environment environment) {
            this.environment = environment;
        }

        @Override
        public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
            requireNoMigrationCapability(environment);
        }
    }
}
