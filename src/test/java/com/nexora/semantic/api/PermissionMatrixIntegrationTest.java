package com.nexora.semantic.api;

import com.nexora.semantic.support.IntegrationTest;
import com.nexora.semantic.support.TestJwt;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Role x endpoint matrix of docs/ARCHITECTURE.md §8.1, for every principal type. "Allowed" means the request passes
 * authorization (2xx); "denied" means 403 {@code forbidden}.
 */
class PermissionMatrixIntegrationTest extends IntegrationTest {

    enum Endpoint { CREATE_DATA_SOURCE, LIST_DATA_SOURCES, READ_AUDIT, MANAGE_TENANTS }

    static Stream<Arguments> matrix() {
        return Stream.of(
                // role, principal type, create DS, list DS, audit, tenants
                Arguments.of("VIEWER", "HUMAN", false, true, false, false),
                Arguments.of("EDITOR", "HUMAN", false, true, false, false),
                Arguments.of("APPROVER", "HUMAN", false, true, false, false),
                Arguments.of("STEWARD", "HUMAN", true, true, false, false),
                Arguments.of("AUDITOR", "HUMAN", false, true, true, false),
                Arguments.of("REPORTS_CLIENT", "SERVICE", false, true, false, false),
                Arguments.of("PLATFORM_ADMIN", "HUMAN", false, false, false, true),
                Arguments.of("PLATFORM_ADMIN", "SERVICE", false, false, false, true),
                Arguments.of("AI_AGENT", "AI", false, true, false, false),
                // Defense in depth: AI principals keep only read/propose permissions whatever roles they carry.
                Arguments.of("STEWARD", "AI", false, true, false, false),
                Arguments.of("AUDITOR", "AI", false, true, false, false),
                Arguments.of("NOT_A_ROLE", "HUMAN", false, false, false, false));
    }

    @ParameterizedTest(name = "{0} as {1}")
    @MethodSource("matrix")
    void enforcesPermissions(String role, String type, boolean createDs, boolean listDs, boolean audit,
                             boolean tenants) throws Exception {
        String tenantClaim = newTenant().id().toString();
        String token = TestJwt.principal(type, tenantClaim, role).bearer();

        assertAccess(token, Endpoint.CREATE_DATA_SOURCE, createDs);
        assertAccess(token, Endpoint.LIST_DATA_SOURCES, listDs);
        assertAccess(token, Endpoint.READ_AUDIT, audit);
        assertAccess(token, Endpoint.MANAGE_TENANTS, tenants);
    }

    private void assertAccess(String token, Endpoint endpoint, boolean allowed) throws Exception {
        MockHttpServletRequestBuilder request = switch (endpoint) {
            case CREATE_DATA_SOURCE -> post("/api/v1/data-sources").contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("key", unique("ds"), "name", "DS", "engine", "postgresql",
                            "databaseName", "db")));
            case LIST_DATA_SOURCES -> get("/api/v1/data-sources");
            case READ_AUDIT -> get("/api/v1/audit-events");
            case MANAGE_TENANTS -> post("/api/v1/admin/tenants").contentType(MediaType.APPLICATION_JSON)
                    .content(json(Map.of("key", unique("tn"), "name", "Tenant")));
        };
        var response = mvc.perform(request.header(HttpHeaders.AUTHORIZATION, token)).andReturn().getResponse();
        if (allowed) {
            assertThat(response.getStatus()).as(endpoint + " allowed").isBetween(200, 299);
        } else {
            assertThat(response.getStatus()).as(endpoint + " denied").isEqualTo(403);
            assertThat(response.getContentAsString()).as(endpoint + " body").contains("\"code\":\"forbidden\"");
        }
    }
}
