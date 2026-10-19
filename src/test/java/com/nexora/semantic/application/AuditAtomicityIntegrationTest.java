package com.nexora.semantic.application;

import tools.jackson.databind.JsonNode;
import com.nexora.semantic.application.port.AuditLog;
import com.nexora.semantic.domain.tenant.Tenant;
import com.nexora.semantic.domain.tenant.TenantStatus;
import com.nexora.semantic.support.IntegrationTest;
import com.nexora.semantic.support.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Audit finding I5: the audit event is written in the same transaction as the change, so when auditing fails the
 * whole operation is rolled back and nothing unaudited is persisted. The real JDBC audit log is wrapped in a spy
 * that is made to fail after the test data is prepared.
 */
class AuditAtomicityIntegrationTest extends IntegrationTest {

    private static final String SECRET_FAILURE = "audit store unavailable 7f3a";

    @MockitoSpyBean
    private AuditLog auditLog;

    private Tenant tenant;
    private String steward;

    @BeforeEach
    void setUp() {
        tenant = newTenant();
        steward = TestJwt.human(tenant, "STEWARD").bearer();
    }

    private void failAuditing() {
        doThrow(new IllegalStateException(SECRET_FAILURE)).when(auditLog).append(any());
    }

    private long count(String sql, Map<String, Object> params) {
        return appJdbc.sql(sql).params(params).query(Long.class).single();
    }

    @Test
    void dataSourceRegistrationIsRolledBackWhenAuditFails() throws Exception {
        failAuditing();

        String response = mvc.perform(post("/api/v1/data-sources").header(HttpHeaders.AUTHORIZATION, steward)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("key", "erp", "name", "ERP", "engine", "postgresql", "databaseName", "erp"))))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("internal_error"))
                .andReturn().getResponse().getContentAsString();

        assertThat(response).doesNotContain(SECRET_FAILURE);
        assertThat(count("SELECT count(*) FROM data_source WHERE tenant_id = :t", Map.of("t", tenant.id().value())))
                .isZero();
    }

    @Test
    void dataSourceUpdateIsRolledBackWhenAuditFails() throws Exception {
        JsonNode created = body(mvc.perform(post("/api/v1/data-sources").header(HttpHeaders.AUTHORIZATION, steward)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("key", "erp", "name", "ERP", "engine", "postgresql", "databaseName", "erp"))))
                .andExpect(status().isCreated()).andReturn());
        UUID id = UUID.fromString(created.get("id").asString());
        failAuditing();

        mvc.perform(patch("/api/v1/data-sources/" + id).header(HttpHeaders.AUTHORIZATION, steward)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Renamed\",\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isInternalServerError());

        var row = appJdbc.sql("SELECT name, status, row_version FROM data_source WHERE tenant_id = :t AND id = :id")
                .param("t", tenant.id().value()).param("id", id)
                .query((rs, n) -> Map.of("name", rs.getString(1), "status", rs.getString(2), "version", rs.getLong(3)))
                .single();
        assertThat(row).containsEntry("name", "ERP").containsEntry("status", "ACTIVE").containsEntry("version", 0L);
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = :t AND target_id = :id",
                Map.of("t", tenant.id().value(), "id", id))).isEqualTo(1);
    }

    @Test
    void tenantRegistrationIsRolledBackWhenAuditFails() throws Exception {
        failAuditing();
        String key = unique("rollback");

        mvc.perform(post("/api/v1/admin/tenants").header(HttpHeaders.AUTHORIZATION, TestJwt.platformAdmin().bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("key", key, "name", "Rollback"))))
                .andExpect(status().isInternalServerError());

        assertThat(count("SELECT count(*) FROM tenant WHERE key = :k", Map.of("k", key))).isZero();
    }

    @Test
    void tenantSuspensionIsRolledBackWhenAuditFails() {
        failAuditing();

        assertThatThrownBy(() ->
                        tenantService.update(tenant.id(), 0, null, TenantStatus.SUSPENDED, PLATFORM_ADMIN, null))
                .hasMessage(SECRET_FAILURE);

        Tenant stored = tenantService.get(tenant.id());
        assertThat(stored.status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(stored.version()).isZero();
    }
}
