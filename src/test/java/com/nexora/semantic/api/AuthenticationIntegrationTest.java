package com.nexora.semantic.api;

import com.nexora.semantic.domain.tenant.Tenant;
import com.nexora.semantic.domain.tenant.TenantStatus;
import com.nexora.semantic.support.IntegrationTest;
import com.nexora.semantic.support.TestJwt;
import com.nexora.semantic.support.TestOidcProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Token validation against the local OIDC provider (issuer discovery + JWKS, as in production), the claim rules of
 * decision D2, the 401/403 contract and public endpoints.
 */
class AuthenticationIntegrationTest extends IntegrationTest {

    private static final String DATA_SOURCES = "/api/v1/data-sources";

    private ResultActions getDataSources(String bearer) throws Exception {
        return mvc.perform(get(DATA_SOURCES).header(HttpHeaders.AUTHORIZATION, bearer));
    }

    private void assertInvalidToken(String bearer) throws Exception {
        getDataSources(bearer)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer error=\"invalid_token\"")))
                .andExpect(jsonPath("$.code").value("unauthorized"));
    }

    @Test
    void validTokenFromConfiguredIssuerIsAccepted() throws Exception {
        getDataSources(TestJwt.human(newTenant(), "VIEWER").bearer()).andExpect(status().isOk());
    }

    @Test
    void requestWithoutTokenIsRejectedWithProblemAndChallenge() throws Exception {
        mvc.perform(get(DATA_SOURCES))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                .andExpect(header().exists("X-Request-Id"))
                .andExpect(jsonPath("$.type").value("about:blank"))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(jsonPath("$.correlationId").exists())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    // --- Issuer (audit finding I4/I6) ---

    @Test
    void tokenFromAnotherIssuerIsRejectedEvenWithTrustedSignature() throws Exception {
        assertInvalidToken(TestJwt.human(newTenant(), "VIEWER").issuer("https://evil.example/realms/test").bearer());
    }

    @Test
    void tokenWithIssuerDifferingOnlyByTrailingSlashIsRejected() throws Exception {
        assertInvalidToken(TestJwt.human(newTenant(), "VIEWER").issuer(TestOidcProvider.issuer() + "/").bearer());
    }

    @Test
    void tokenWithoutIssuerIsRejected() throws Exception {
        assertInvalidToken(TestJwt.human(newTenant(), "VIEWER").without("iss").bearer());
    }

    // --- Signature, expiry, audience, subject ---

    @Test
    void tokenSignedByUntrustedKeyIsRejected() throws Exception {
        assertInvalidToken(TestJwt.human(newTenant(), "VIEWER").signedWithUntrustedKey().bearer());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        assertInvalidToken(TestJwt.human(newTenant(), "VIEWER").expiredAgo(Duration.ofMinutes(5)).bearer());
    }

    @Test
    void tokenForAnotherAudienceIsRejected() throws Exception {
        assertInvalidToken(TestJwt.human(newTenant(), "VIEWER").audience("another-api").bearer());
    }

    @Test
    void tokenWithoutSubjectIsRejected() throws Exception {
        assertInvalidToken(TestJwt.human(newTenant(), "VIEWER").without("sub").bearer());
    }

    @Test
    void malformedTokenIsRejected() throws Exception {
        assertInvalidToken("Bearer not-a-jwt");
    }

    // --- principal_type (decision D2: mandatory, exact value) ---

    @Test
    void tokenWithoutPrincipalTypeIsRejected() throws Exception {
        assertInvalidToken(TestJwt.human(newTenant(), "VIEWER").without("principal_type").bearer());
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROBOT", "human", "", " HUMAN"})
    void tokenWithInvalidPrincipalTypeIsRejected(String type) throws Exception {
        assertInvalidToken(TestJwt.human(newTenant(), "VIEWER").type(type).bearer());
    }

    @Test
    void principalTypeMustBeAString() throws Exception {
        assertInvalidToken(TestJwt.human(newTenant(), "VIEWER").claim("principal_type", List.of("HUMAN")).bearer());
    }

    // --- tenant claim (decision D2: tenant UUID) ---

    @ParameterizedTest
    @ValueSource(strings = {"demo", "not-a-uuid", "", "00000000000000000000000000000000",
            "{3fa85f64-5717-4562-b3fc-2c963f66afa6}", "3fa85f64-5717-4562-b3fc-2c963f66afa6 "})
    void tokenWithMalformedTenantIsRejected(String tenantClaim) throws Exception {
        assertInvalidToken(TestJwt.principal("HUMAN", tenantClaim, "VIEWER").bearer());
    }

    @Test
    void tenantClaimMustBeAString() throws Exception {
        assertInvalidToken(TestJwt.principal("HUMAN", null, "VIEWER")
                .claim("tenant", List.of(newTenant().id().toString())).bearer());
    }

    @Test
    void tokenWithoutTenantIsRejectedForTenantPrincipals() throws Exception {
        assertInvalidToken(TestJwt.principal("HUMAN", null, "VIEWER", "STEWARD").bearer());
        assertInvalidToken(TestJwt.principal("SERVICE", null, "REPORTS_CLIENT").bearer());
    }

    @Test
    void tenantKeyIsNoLongerAcceptedAsTenantClaim() throws Exception {
        Tenant tenant = newTenant();
        assertInvalidToken(TestJwt.principal("HUMAN", tenant.key().value(), "VIEWER").bearer());
    }

    @Test
    void unknownTenantUuidIsDenied() throws Exception {
        getDataSources(TestJwt.principal("HUMAN", UUID.randomUUID().toString(), "VIEWER").bearer())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("tenant_access_denied"));
    }

    @Test
    void suspendedTenantIsDeniedWithSameResponseAsUnknown() throws Exception {
        Tenant tenant = newTenant();
        tenantService.update(tenant.id(), 0, null, TenantStatus.SUSPENDED, PLATFORM_ADMIN, null);
        getDataSources(TestJwt.human(tenant, "VIEWER").bearer())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("tenant_access_denied"))
                .andExpect(jsonPath("$.detail").value("Tenant is not available for this principal"));
    }

    @Test
    void uppercaseTenantUuidIsAccepted() throws Exception {
        Tenant tenant = newTenant();
        getDataSources(TestJwt.principal("HUMAN", tenant.id().toString().toUpperCase(), "VIEWER").bearer())
                .andExpect(status().isOk());
    }

    @Test
    void platformAdminWithoutTenantIsAcceptedButHasNoTenantAccess() throws Exception {
        String admin = TestJwt.platformAdmin().bearer();
        mvc.perform(get("/api/v1/admin/tenants").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk());
        getDataSources(admin)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("tenant_access_denied"));
    }

    // --- Public endpoints and correlation id (audit finding I2) ---

    @Test
    void healthIsPublicWithoutDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void otherActuatorEndpointsAndUnknownPathsAreNotReachable() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/beans")).andExpect(status().isUnauthorized());
        String token = TestJwt.human(newTenant(), "AUDITOR").bearer();
        mvc.perform(get("/actuator/env").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    void correlationIdIsAlwaysGeneratedByTheServer() throws Exception {
        mvc.perform(get("/actuator/health").header("X-Request-Id", "client-chosen-id"))
                .andExpect(header().string("X-Request-Id", not("client-chosen-id")))
                .andExpect(header().string("X-Request-Id", matchesPattern("[0-9a-f-]{36}")));
        mvc.perform(get(DATA_SOURCES).header("X-Request-Id", "client-chosen-id"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.correlationId", not("client-chosen-id")));
    }
}
