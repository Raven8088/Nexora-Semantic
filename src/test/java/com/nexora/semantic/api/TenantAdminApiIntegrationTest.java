package com.nexora.semantic.api;

import com.nexora.semantic.domain.tenant.Tenant;
import com.nexora.semantic.support.IntegrationTest;
import com.nexora.semantic.support.TestJwt;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TenantAdminApiIntegrationTest extends IntegrationTest {

    private static final String BASE = "/api/v1/admin/tenants";

    private final TestJwt.Builder adminToken = TestJwt.platformAdmin();
    private final String admin = adminToken.bearer();

    private MvcResult createTenant(String key) throws Exception {
        return mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, admin)
                        .header("X-Request-Id", "req-tenant-1")
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("key", key, "name", "Acme"))))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, startsWith(BASE + "/")))
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.key").value(key))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andReturn();
    }

    @Test
    void platformAdminRegistersTenantAndItIsAudited() throws Exception {
        String key = unique("acme");
        MvcResult created = createTenant(key);
        String id = body(created).get("id").asString();
        String serverCorrelationId = created.getResponse().getHeader("X-Request-Id");

        var event = appJdbc.sql("SELECT action, actor_id, actor_type, correlation_id FROM audit_event "
                        + "WHERE tenant_id = :id AND target_id = :id")
                .param("id", UUID.fromString(id))
                .query((rs, n) -> Map.of("action", rs.getString(1), "actor", rs.getString(2),
                        "type", rs.getString(3), "cid", rs.getString(4)))
                .single();
        assertThat(event).containsEntry("action", "TENANT_REGISTERED")
                .containsEntry("actor", adminToken.subjectValue())
                .containsEntry("type", "HUMAN")
                .containsEntry("cid", serverCorrelationId);
        assertThat(serverCorrelationId).isNotEqualTo("req-tenant-1");

        mvc.perform(get(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value(key));
    }

    @Test
    void duplicateAndInvalidKeys() throws Exception {
        String key = unique("dup");
        createTenant(key);
        mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("key", key, "name", "Other"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("duplicate_key"));
        mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("key", "Bad_Key", "name", "x"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("key"));
    }

    @Test
    void tenantUsersCannotAdministerTenants() throws Exception {
        Tenant tenant = newTenant();
        for (String role : new String[]{"VIEWER", "EDITOR", "APPROVER", "STEWARD", "AUDITOR"}) {
            String token = TestJwt.human(tenant, role).bearer();
            mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("forbidden"));
        }
    }

    @Test
    void aiPrincipalCannotAdministerTenantsEvenWithAdminRole() throws Exception {
        String token = TestJwt.principal("AI", null, "PLATFORM_ADMIN").bearer();
        mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("key", unique("ai"), "name", "x"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void suspendAndReactivateWithOptimisticLocking() throws Exception {
        String key = unique("susp");
        String id = body(createTenant(key)).get("id").asString();
        // Decision D2: the tenant is registered first, then its UUID is configured in the identity provider.
        String steward = TestJwt.principal("HUMAN", id, "STEWARD").bearer();

        mvc.perform(patch(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(patch(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, admin).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUSPENDED\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.status").value("SUSPENDED"));

        mvc.perform(get("/api/v1/data-sources").header(HttpHeaders.AUTHORIZATION, steward))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("tenant_access_denied"));

        mvc.perform(patch(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, admin).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(patch(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, admin).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/data-sources").header(HttpHeaders.AUTHORIZATION, steward))
                .andExpect(status().isOk());
    }

    @Test
    void unknownTenantIsNotFound() throws Exception {
        mvc.perform(get(BASE + "/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
    }
}
