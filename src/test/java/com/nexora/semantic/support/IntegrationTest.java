package com.nexora.semantic.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.nexora.semantic.application.tenant.TenantService;
import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.PrincipalType;
import com.nexora.semantic.domain.tenant.Tenant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

/**
 * Base of integration tests: one PostgreSQL container per JVM, migrated by Flyway as the owner (container
 * superuser) while the application connects with the restricted {@code nexora_semantic_app} role, exactly as in
 * production. Tests isolate themselves by creating their own tenants with random keys; nothing is deleted
 * (the application role has no DELETE privilege).
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTest {

    protected static final String APP_ROLE = "nexora_semantic_app";
    private static final String APP_PASSWORD = "app-test-password";

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17.6")
            .withDatabaseName("nexora_semantic")
            .withInitScript("testcontainers/init-roles.sql");

    protected static final PrincipalRef PLATFORM_ADMIN = new PrincipalRef("platform-admin-test", PrincipalType.HUMAN);

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", IntegrationTest::applicationJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_ROLE);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.flyway.placeholders.appRole", () -> APP_ROLE);
        // Production mode: issuer discovery + JWKS from the local test OIDC provider.
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TestOidcProvider::issuer);
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected TenantService tenantService;

    /** JdbcClient of the application role (the same restricted connection the application uses). */
    @Autowired
    protected JdbcClient appJdbc;

    protected static String applicationJdbcUrl() {
        String url = POSTGRES.getJdbcUrl();
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=semantic";
    }

    /** JdbcClient connected as the schema owner (container superuser), for checks the app role cannot perform. */
    protected static JdbcClient ownerJdbc() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(applicationJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        return JdbcClient.create(dataSource);
    }

    protected Tenant newTenant() {
        return tenantService.register(unique("t"), "Test tenant", PLATFORM_ADMIN, null);
    }

    protected static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    protected String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }
}
