package com.nexora.semantic.api.security;

import com.nexora.semantic.domain.shared.PrincipalType;
import com.nexora.semantic.domain.shared.TenantId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtPrincipalConverterTest {

    private static final String TENANT = "3fa85f64-5717-4562-b3fc-2c963f66afa6";

    private final JwtPrincipalConverter converter =
            new JwtPrincipalConverter(new SecurityProperties("tenant", "principal_type", "roles"));

    private static Jwt jwt(Consumer<Map<String, Object>> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("token").header("alg", "RS256")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
        builder.claims(claims);
        return builder.build();
    }

    /** Valid human tenant user; {@code customizer} adjusts claims afterwards. */
    private static Jwt human(String role, Consumer<Map<String, Object>> customizer) {
        return jwt(c -> {
            c.put("sub", "user-1");
            c.put("tenant", TENANT);
            c.put("principal_type", "HUMAN");
            c.put("roles", List.of(role));
            customizer.accept(c);
        });
    }

    private AuthenticatedPrincipal principal(Jwt jwt) {
        return ((SemanticAuthenticationToken) converter.convert(jwt)).semanticPrincipal();
    }

    private void assertRejected(Jwt jwt) {
        assertThatThrownBy(() -> converter.convert(jwt)).isInstanceOf(OAuth2AuthenticationException.class);
    }

    @Test
    void mapsSubjectTenantTypeAndRolePermissions() {
        SemanticAuthenticationToken token = (SemanticAuthenticationToken) converter.convert(
                human("VIEWER", c -> c.put("roles", List.of("VIEWER", "STEWARD"))));

        AuthenticatedPrincipal principal = token.semanticPrincipal();
        assertThat(principal.subject()).isEqualTo("user-1");
        assertThat(principal.tenantId()).isEqualTo(new TenantId(UUID.fromString(TENANT)));
        assertThat(principal.type()).isEqualTo(PrincipalType.HUMAN);
        assertThat(principal.permissions()).containsExactlyInAnyOrder(Permission.CATALOG_READ, Permission.SOURCE_MANAGE);
        assertThat(token.getAuthorities()).extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("semantic:catalog:read", "semantic:source:manage");
        assertThat(token.getName()).isEqualTo("user-1");
    }

    @Test
    void roleNamesAreCaseInsensitiveAndUnknownRolesAreIgnored() {
        AuthenticatedPrincipal principal = principal(human("VIEWER",
                c -> c.put("roles", List.of("auditor", "offline_access", "default-roles-nexora", "ADMIN"))));
        assertThat(principal.permissions()).containsExactlyInAnyOrder(Permission.CATALOG_READ, Permission.AUDIT_READ);
    }

    @Test
    void uppercaseTenantUuidIsAccepted() {
        assertThat(principal(human("VIEWER", c -> c.put("tenant", TENANT.toUpperCase()))).tenantId().value())
                .isEqualTo(UUID.fromString(TENANT));
    }

    // --- principal_type: mandatory and exact (decision D2) ---

    @Test
    void missingPrincipalTypeIsRejected() {
        assertRejected(human("VIEWER", c -> c.remove("principal_type")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROBOT", "human", "Human", "", " HUMAN", "HUMAN "})
    void invalidPrincipalTypeIsRejected(String type) {
        assertRejected(human("VIEWER", c -> c.put("principal_type", type)));
    }

    @Test
    void nonStringPrincipalTypeIsRejected() {
        assertRejected(human("VIEWER", c -> c.put("principal_type", List.of("HUMAN"))));
        assertRejected(human("VIEWER", c -> c.put("principal_type", 1)));
    }

    // --- tenant: canonical UUID (decision D2) ---

    @ParameterizedTest
    @ValueSource(strings = {"acme", "", "3fa85f6457174562b3fc2c963f66afa6", "{3fa85f64-5717-4562-b3fc-2c963f66afa6}",
            " 3fa85f64-5717-4562-b3fc-2c963f66afa6", "3fa85f64-5717-4562-b3fc-2c963f66afa", "1-1-1-1-1"})
    void malformedTenantIsRejected(String tenant) {
        assertRejected(human("VIEWER", c -> c.put("tenant", tenant)));
    }

    @Test
    void nonStringTenantIsRejected() {
        assertRejected(human("VIEWER", c -> c.put("tenant", List.of(TENANT))));
        assertRejected(human("VIEWER", c -> c.put("tenant", Map.of("id", TENANT))));
    }

    @Test
    void missingTenantIsRejectedUnlessPlatformAdmin() {
        assertRejected(human("VIEWER", c -> c.remove("tenant")));
        assertRejected(human("REPORTS_CLIENT", c -> {
            c.remove("tenant");
            c.put("principal_type", "SERVICE");
        }));

        AuthenticatedPrincipal admin = principal(human("PLATFORM_ADMIN", c -> c.remove("tenant")));
        assertThat(admin.tenantId()).isNull();
        assertThat(admin.permissions()).containsExactly(Permission.PLATFORM_TENANT_MANAGE);
    }

    @Test
    void platformAdminWithMalformedTenantIsStillRejected() {
        assertRejected(human("PLATFORM_ADMIN", c -> c.put("tenant", "not-a-uuid")));
    }

    // --- Defense in depth for non-human principals ---

    @Test
    void servicePrincipalsNeverReceiveApprovalPermission() {
        AuthenticatedPrincipal principal = principal(human("APPROVER", c -> {
            c.put("principal_type", "SERVICE");
            c.put("roles", List.of("APPROVER", "REPORTS_CLIENT"));
        }));
        assertThat(principal.permissions()).containsExactlyInAnyOrder(
                Permission.CATALOG_READ, Permission.PROPOSAL_WRITE, Permission.RESOLVE);
    }

    @Test
    void aiPrincipalsAreLimitedToReadingAndProposingWhateverTheirRoles() {
        AuthenticatedPrincipal principal = principal(human("AI_AGENT", c -> {
            c.put("principal_type", "AI");
            c.put("roles", List.of("APPROVER", "STEWARD", "AUDITOR", "PLATFORM_ADMIN", "AI_AGENT"));
        }));
        assertThat(principal.permissions()).containsExactlyInAnyOrder(Permission.CATALOG_READ, Permission.PROPOSAL_WRITE);
    }

    @Test
    void humanApproverKeepsApprovalPermission() {
        assertThat(principal(human("APPROVER", c -> { })).permissions()).contains(Permission.PROPOSAL_APPROVE);
    }

    @Test
    void supportsNestedClaimPathsAndSingleStringRoles() {
        JwtPrincipalConverter keycloakStyle =
                new JwtPrincipalConverter(new SecurityProperties("org.tenant", "principal_type", "realm_access.roles"));
        Jwt jwt = jwt(c -> {
            c.put("sub", "user-1");
            c.put("principal_type", "HUMAN");
            c.put("org", Map.of("tenant", TENANT));
            c.put("realm_access", Map.of("roles", List.of("VIEWER")));
        });
        AuthenticatedPrincipal principal = ((SemanticAuthenticationToken) keycloakStyle.convert(jwt)).semanticPrincipal();
        assertThat(principal.tenantId().value()).isEqualTo(UUID.fromString(TENANT));
        assertThat(principal.permissions()).containsExactly(Permission.CATALOG_READ);

        assertThat(principal(human("VIEWER", c -> c.put("roles", "AUDITOR"))).permissions())
                .contains(Permission.AUDIT_READ);
    }

    @Test
    void tenantUserWithoutKnownRolesHasNoPermissions() {
        assertThat(principal(human("NOT_A_ROLE", c -> { })).permissions()).isEmpty();
    }

    @Test
    void rejectsMissingOrInvalidSubject() {
        assertRejected(human("VIEWER", c -> c.remove("sub")));
        assertRejected(human("VIEWER", c -> c.put("sub", "x".repeat(256))));
        assertRejected(human("VIEWER", c -> c.put("sub", "user\n1")));
    }
}
