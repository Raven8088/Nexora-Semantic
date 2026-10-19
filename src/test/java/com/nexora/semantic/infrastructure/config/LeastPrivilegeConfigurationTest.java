package com.nexora.semantic.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LeastPrivilegeConfigurationTest {

    @Test
    void flywayMustBeExplicitlyDisabled() {
        assertThatThrownBy(() -> LeastPrivilegeConfiguration.requireNoMigrationCapability(new MockEnvironment()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.flyway.enabled=false");
        assertThatThrownBy(() -> LeastPrivilegeConfiguration.requireNoMigrationCapability(
                new MockEnvironment().withProperty("spring.flyway.enabled", "true")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void migrationCredentialsMustNotBeConfigured() {
        assertThatThrownBy(() -> LeastPrivilegeConfiguration.requireNoMigrationCapability(new MockEnvironment()
                .withProperty("spring.flyway.enabled", "false")
                .withProperty("spring.flyway.password", "owner-secret")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credentials must not be configured")
                .message().doesNotContain("owner-secret");
        assertThatThrownBy(() -> LeastPrivilegeConfiguration.requireNoMigrationCapability(new MockEnvironment()
                .withProperty("spring.flyway.enabled", "false")
                .withProperty("spring.flyway.url", "jdbc:postgresql://db/x")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void disabledFlywayWithoutCredentialsIsAccepted() {
        assertThatCode(() -> LeastPrivilegeConfiguration.requireNoMigrationCapability(new MockEnvironment()
                .withProperty("spring.flyway.enabled", "false")
                .withProperty("spring.flyway.user", "nexora_semantic_owner")
                .withProperty("spring.flyway.password", "")))
                .doesNotThrowAnyException();
    }
}
