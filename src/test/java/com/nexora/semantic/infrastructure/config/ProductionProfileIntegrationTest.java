package com.nexora.semantic.infrastructure.config;

import tools.jackson.databind.ObjectMapper;
import com.nexora.semantic.NexoraSemanticApplication;
import com.nexora.semantic.infrastructure.migration.SchemaMigrator;
import com.nexora.semantic.infrastructure.persistence.LeastPrivilegeVerifier;
import com.nexora.semantic.support.TestDatabases;
import com.nexora.semantic.support.TestJwt;
import com.nexora.semantic.support.TestOidcProvider;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationInitializer;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit finding I3: the {@code prod} profile against a database migrated beforehand by the separate migration job.
 * The application connects only with the application role, never runs Flyway and verifies its least privilege.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("prod")
class ProductionProfileIntegrationTest {

    private static final String URL = TestDatabases.createProductionLikeDatabase("semantic_prod_profile_it");

    static {
        SchemaMigrator.fromEnvironment(TestDatabases.migrationEnvironment(URL)).migrate();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> URL + "?currentSchema=semantic");
        registry.add("spring.datasource.username", () -> TestDatabases.APP_ROLE);
        registry.add("spring.datasource.password", () -> TestDatabases.APP_ROLE_PASSWORD);
        // Robust against a developer environment exporting SEMANTIC_DB_OWNER_PASSWORD.
        registry.add("spring.flyway.password", () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TestOidcProvider::issuer);
    }

    @Autowired
    private ApplicationContext context;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    private static long historyRows() {
        return TestDatabases.asSuperuser(URL).sql("SELECT count(*) FROM flyway_schema_history WHERE version IS NOT NULL")
                .query(Long.class).single();
    }

    @Test
    void flywayDoesNotRunDuringStartup() {
        assertThat(context.getBeanNamesForType(Flyway.class)).isEmpty();
        assertThat(context.getBeanNamesForType(FlywayMigrationInitializer.class)).isEmpty();
        assertThat(historyRows()).isEqualTo(5); // V1..V5, all applied beforehand by the migration job
        assertThat(TestDatabases.asSuperuser(URL).sql("SELECT DISTINCT installed_by FROM flyway_schema_history")
                .query(String.class).list()).containsExactly(TestDatabases.MIGRATOR);
    }

    @Test
    void connectsOnlyWithTheApplicationRoleAndVerifiedItsLeastPrivilege() {
        assertThat(jdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo(TestDatabases.APP_ROLE);
        assertThat(context.getBean(LeastPrivilegeVerifier.class).violations()).isEmpty();
    }

    @Test
    void servesRequestsWithOnlyTheApplicationRole() throws Exception {
        String created = mvc.perform(post("/api/v1/admin/tenants")
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.platformAdmin().bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("key", "prod-tenant", "name", "Prod"))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String tenantId = objectMapper.readTree(created).get("id").asString();
        String steward = TestJwt.principal("HUMAN", tenantId, "STEWARD").bearer();

        mvc.perform(post("/api/v1/data-sources").header(HttpHeaders.AUTHORIZATION, steward)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("key", "erp", "name", "ERP", "engine", "postgresql", "databaseName", "erp"))))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/data-sources").header(HttpHeaders.AUTHORIZATION, steward))
                .andExpect(status().isOk());
        assertThat(historyRows()).isEqualTo(5);
    }

    // --- The production profile refuses unsafe configurations at startup ---

    private static void startProduction(String user, String password, String... extraArgs) {
        String[] base = {
                "--server.port=0",
                "--spring.datasource.url=" + URL + "?currentSchema=semantic",
                "--spring.datasource.username=" + user,
                "--spring.datasource.password=" + password,
                "--spring.flyway.password=",
                "--spring.security.oauth2.resourceserver.jwt.issuer-uri=" + TestOidcProvider.issuer()};
        String[] args = new String[base.length + extraArgs.length];
        System.arraycopy(base, 0, args, 0, base.length);
        System.arraycopy(extraArgs, 0, args, base.length, extraArgs.length);
        new SpringApplicationBuilder(NexoraSemanticApplication.class).profiles("prod").run(args).close();
    }

    @Test
    void refusesToStartWithTheMigrationIdentity() {
        assertThatThrownBy(() -> startProduction(TestDatabases.MIGRATOR, TestDatabases.MIGRATOR_PASSWORD))
                .rootCause()
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("least privilege")
                .hasMessageContaining("role owns (or inherits ownership of) schema semantic");
    }

    @Test
    void refusesToStartWhenFlywayIsEnabled() {
        long before = historyRows();
        assertThatThrownBy(() -> startProduction(TestDatabases.APP_ROLE, TestDatabases.APP_ROLE_PASSWORD,
                "--spring.flyway.enabled=true"))
                // Raised by a BeanFactoryPostProcessor, before any bean (including Flyway) is created.
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires spring.flyway.enabled=false");
        assertThat(historyRows()).isEqualTo(before);
    }

    @Test
    void refusesToStartWhenOwnerCredentialsAreProvided() {
        assertThatThrownBy(() -> startProduction(TestDatabases.APP_ROLE, TestDatabases.APP_ROLE_PASSWORD,
                "--spring.flyway.password=owner-secret"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("credentials must not be configured")
                .message().doesNotContain("owner-secret");
    }
}
