package com.nexora.semantic.api;

import tools.jackson.databind.JsonNode;
import com.nexora.semantic.domain.tenant.Tenant;
import com.nexora.semantic.domain.tenant.TenantStatus;
import com.nexora.semantic.support.IntegrationTest;
import com.nexora.semantic.support.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class DataSourceApiIntegrationTest extends IntegrationTest {

    private static final String BASE = "/api/v1/data-sources";

    private Tenant tenant;
    private String steward;
    private String viewer;
    private String auditor;

    @BeforeEach
    void setUp() {
        tenant = newTenant();
        steward = TestJwt.human(tenant, "STEWARD").bearer();
        viewer = TestJwt.human(tenant, "VIEWER").bearer();
        auditor = TestJwt.human(tenant, "AUDITOR").bearer();
    }

    private static Map<String, Object> createBody(String key) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", key);
        body.put("name", "ERP " + key);
        body.put("engine", "PostgreSQL");
        body.put("databaseName", "erp");
        body.put("description", "Main ERP");
        return body;
    }

    private JsonNode create(String token, String key) throws Exception {
        MvcResult result = mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON).content(json(createBody(key))))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result);
    }

    @Test
    void registersDataSourceAndAuditsIt() throws Exception {
        MvcResult result = mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, steward)
                        .header("X-Request-Id", "req-create-1")
                        .contentType(MediaType.APPLICATION_JSON).content(json(createBody("erp"))))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, startsWith("/api/v1/data-sources/")))
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.key").value("erp"))
                .andExpect(jsonPath("$.engine").value("postgresql"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();
        String id = body(result).get("id").asString();
        String serverCorrelationId = result.getResponse().getHeader("X-Request-Id");
        assertThat(serverCorrelationId).isNotEqualTo("req-create-1").matches("[0-9a-f-]{36}");

        mvc.perform(get("/api/v1/audit-events").param("targetType", "DATA_SOURCE").param("targetId", id)
                        .header(HttpHeaders.AUTHORIZATION, auditor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].action").value("DATA_SOURCE_REGISTERED"))
                .andExpect(jsonPath("$.items[0].actorType").value("HUMAN"))
                .andExpect(jsonPath("$.items[0].correlationId").value(serverCorrelationId))
                .andExpect(jsonPath("$.items[0].details.key").value("erp"));
    }

    @Test
    void getReturnsDataSourceWithEtag() throws Exception {
        String id = create(steward, "erp").get("id").asString();
        mvc.perform(get(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.description").value("Main ERP"));
    }

    @Test
    void credentialFieldsAreRejectedAndNothingIsStored() throws Exception {
        Map<String, Object> body = createBody("with-password");
        body.put("password", "s3cr3t");
        mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, steward)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors[0].field").value("password"))
                .andExpect(jsonPath("$.detail").value("Request validation failed"));

        Map<String, Object> withConnection = createBody("with-connection");
        withConnection.put("connection", Map.of("host", "db", "username", "u", "password", "p"));
        mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, steward)
                        .contentType(MediaType.APPLICATION_JSON).content(json(withConnection)))
                .andExpect(status().isBadRequest());

        mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void invalidFieldsAreReportedWithoutEchoingValues() throws Exception {
        Map<String, Object> body = createBody("Invalid Key!");
        body.put("name", "");
        body.put("engine", "pg;drop table");
        String response = mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, steward)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors[*].field", hasItem("key")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("name")))
                .andExpect(jsonPath("$.errors[*].field", hasItem("engine")))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("Invalid Key!").doesNotContain("drop table");
    }

    @Test
    void duplicateKeyInSameTenantConflictsButOtherTenantMayReuseIt() throws Exception {
        create(steward, "erp");
        mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, steward)
                        .contentType(MediaType.APPLICATION_JSON).content(json(createBody("erp"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("duplicate_key"));

        Tenant other = newTenant();
        create(TestJwt.human(other, "STEWARD").bearer(), "erp");
    }

    @Test
    void tenantsAreIsolated() throws Exception {
        String id = create(steward, "erp").get("id").asString();
        Tenant other = newTenant();
        String otherSteward = TestJwt.human(other, "STEWARD", "VIEWER", "AUDITOR").bearer();

        mvc.perform(get(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, otherSteward))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
        mvc.perform(patch(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, otherSteward)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"hijacked\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, otherSteward))
                .andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(get("/api/v1/audit-events").param("targetId", id).header(HttpHeaders.AUTHORIZATION, otherSteward))
                .andExpect(jsonPath("$.totalItems").value(0));

        mvc.perform(get(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(jsonPath("$.name").value("ERP erp"));
    }

    @Test
    void listPaginatesAndFiltersByStatus() throws Exception {
        create(steward, "aaa");
        create(steward, "bbb");
        String cccId = create(steward, "ccc").get("id").asString();
        mvc.perform(patch(BASE + "/" + cccId).header(HttpHeaders.AUTHORIZATION, steward)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isOk());

        mvc.perform(get(BASE).param("size", "2").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].key").value("aaa"));
        mvc.perform(get(BASE).param("size", "2").param("page", "1").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].key").value("ccc"));
        mvc.perform(get(BASE).param("status", "ARCHIVED").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].key").value("ccc"));
    }

    @Test
    void invalidPaginationAndFiltersAreRejected() throws Exception {
        mvc.perform(get(BASE).param("size", "500").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
        mvc.perform(get(BASE).param("page", "-1").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isBadRequest());
        mvc.perform(get(BASE).param("status", "DELETED").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("status"));
    }

    @Test
    void updateRequiresMatchingIfMatchAndIncrementsVersion() throws Exception {
        String id = create(steward, "erp").get("id").asString();
        String uri = BASE + "/" + id;

        mvc.perform(patch(uri).header(HttpHeaders.AUTHORIZATION, steward)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ERP v2\"}"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("precondition_required"));

        mvc.perform(patch(uri).header(HttpHeaders.AUTHORIZATION, steward).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ERP v2\",\"description\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.name").value("ERP v2"))
                .andExpect(jsonPath("$.description").doesNotExist())
                .andExpect(jsonPath("$.version").value(1));

        mvc.perform(patch(uri).header(HttpHeaders.AUTHORIZATION, steward).header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ERP v3\"}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("precondition_failed"));
        mvc.perform(patch(uri).header(HttpHeaders.AUTHORIZATION, steward).header(HttpHeaders.IF_MATCH, "*")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"ERP v3\"}"))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    void archiveIsAuditedAsStatusTransition() throws Exception {
        String id = create(steward, "erp").get("id").asString();
        mvc.perform(patch(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, steward)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));

        mvc.perform(get("/api/v1/audit-events").param("targetId", id).header(HttpHeaders.AUTHORIZATION, auditor))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].action").value("DATA_SOURCE_ARCHIVED"))
                .andExpect(jsonPath("$.items[0].details.from").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].details.to").value("ARCHIVED"));
    }

    @Test
    void immutableAttributesCannotBeChanged() throws Exception {
        String id = create(steward, "erp").get("id").asString();
        for (String field : new String[]{"key", "engine", "databaseName", "id", "tenantId"}) {
            mvc.perform(patch(BASE + "/" + id).header(HttpHeaders.AUTHORIZATION, steward)
                            .header(HttpHeaders.IF_MATCH, "\"0\"")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"" + field + "\":\"x\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(field));
        }
    }

    @Test
    void unknownOrMalformedIdentifiers() throws Exception {
        mvc.perform(get(BASE + "/" + UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isNotFound());
        mvc.perform(get(BASE + "/not-a-uuid").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"))
                .andExpect(jsonPath("$.errors[0].field").value("id"));
    }

    @Test
    void suspendedTenantLosesAccess() throws Exception {
        create(steward, "erp");
        tenantService.update(tenant.id(), 0, null, TenantStatus.SUSPENDED, PLATFORM_ADMIN, null);

        mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("tenant_access_denied"));
    }
}
