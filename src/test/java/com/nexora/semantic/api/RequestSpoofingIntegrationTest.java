package com.nexora.semantic.api;

import com.nexora.semantic.api.security.CallerArgumentResolver;
import com.nexora.semantic.api.security.JwtPrincipalConverter;
import com.nexora.semantic.domain.shared.CallerContext;
import com.nexora.semantic.domain.shared.PrincipalType;
import com.nexora.semantic.domain.tenant.Tenant;
import com.nexora.semantic.support.IntegrationTest;
import com.nexora.semantic.support.TestJwt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.context.request.ServletWebRequest;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The tenant, the caller and its permissions come only from the validated access token: request bodies, query
 * parameters, headers and unknown token claims can never declare or switch them.
 */
class RequestSpoofingIntegrationTest extends IntegrationTest {

    private static final String BASE = "/api/v1/data-sources";

    @Autowired
    private JwtPrincipalConverter converter;

    private Tenant tenant;
    private Tenant otherTenant;
    private String steward;

    @BeforeEach
    void setUp() {
        tenant = newTenant();
        otherTenant = newTenant();
        steward = TestJwt.human(tenant, "STEWARD").bearer();
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @SuppressWarnings("unused")
    private void target(CallerContext caller) {
    }

    @Test
    void callerTenantAndPermissionsComeOnlyFromTheConfiguredTokenClaims() throws Exception {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "RS256")
                .subject("alice")
                .claim("principal_type", "HUMAN")
                .claim("tenant", tenant.id().toString())
                .claim("roles", List.of("VIEWER", "EDITOR"))
                // Claims a consumer could try to use to switch tenant, re-scope or elevate itself: never read.
                .claim("tenant_id", otherTenant.id().toString())
                .claim("company_id", "1042")
                .claim("companyId", "1042")
                .claim("integration", "wellnode")
                .claim("permissions", List.of("semantic:proposal:approve", "platform:tenant:manage"))
                .claim("authorities", List.of("semantic:proposal:approve"))
                .build();
        JwtAuthenticationToken authentication = converter.convert(jwt);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Nexora-Tenant-Id", otherTenant.id().toString());
        request.addHeader("X-Nexora-Company-Id", "1042");
        request.setParameter("tenantId", otherTenant.id().toString());

        CallerContext context = (CallerContext) new CallerArgumentResolver(tenantService).resolveArgument(
                new MethodParameter(getClass().getDeclaredMethod("target", CallerContext.class), 0), null,
                new ServletWebRequest(request), null);

        assertThat(context.tenantId()).isEqualTo(tenant.id());
        assertThat(context.principal().id()).isEqualTo("alice");
        assertThat(context.principal().type()).isEqualTo(PrincipalType.HUMAN);
        assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("semantic:catalog:read", "semantic:proposal:write");
    }

    @Test
    void consumersCannotDeclareTenantCompanyActorOrPermissionsInBodies() throws Exception {
        for (String field : List.of("tenantId", "companyId", "company_id", "actorId", "permissions", "integration",
                "scope")) {
            mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, steward)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("key", unique("erp"), "name", "ERP", "engine", "PostgreSQL",
                                    "databaseName", "erp", field, otherTenant.id().toString()))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation_failed"))
                    .andExpect(jsonPath("$.errors[0].field").value(field))
                    .andExpect(jsonPath("$.errors[0].message").value("unknown property"));
            mvc.perform(post(BASE + "/" + UUID.randomUUID() + "/semantic-resolutions")
                            .header(HttpHeaders.AUTHORIZATION, TestJwt.principal("SERVICE",
                                    tenant.id().toString(), "REPORTS_CLIENT").bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("metrics", List.of(UUID.randomUUID().toString()),
                                    field, "1042"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("validation_failed"))
                    .andExpect(jsonPath("$.errors[0].field").value(field))
                    .andExpect(jsonPath("$.errors[0].message").value("unknown property"));
        }
    }

    @Test
    void headersParametersAndClaimsNeverSwitchTenant() throws Exception {
        String otherSteward = TestJwt.human(otherTenant, "STEWARD").bearer();
        String othersSource = body(mvc.perform(post(BASE).header(HttpHeaders.AUTHORIZATION, otherSteward)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("key", unique("erp"), "name", "ERP", "engine", "PostgreSQL",
                                "databaseName", "erp"))))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        String spoofing = TestJwt.human(tenant, "VIEWER").claim("company_id", "1042")
                .claim("tenant_id", otherTenant.id().toString()).bearer();

        mvc.perform(get(BASE + "/" + othersSource).header(HttpHeaders.AUTHORIZATION, spoofing)
                        .header("X-Nexora-Tenant-Id", otherTenant.id().toString())
                        .header("X-Nexora-Company-Id", "1042")
                        .param("tenantId", otherTenant.id().toString()))
                .andExpect(status().isNotFound());
        mvc.perform(get(BASE).header(HttpHeaders.AUTHORIZATION, spoofing)
                        .header("X-Nexora-Tenant-Id", otherTenant.id().toString())
                        .param("tenantId", otherTenant.id().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }
}
