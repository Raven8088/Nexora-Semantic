package com.nexora.semantic.api.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Names of the access token claims Semantic reads, so any OIDC provider can be used. A dotted name reaches a
 * nested claim, e.g. {@code realm_access.roles} for Keycloak realm roles.
 *
 * @param tenantClaim        claim holding the tenant UUID (canonical form). Mandatory for every principal except
 *                           platform administrators
 * @param principalTypeClaim claim holding exactly HUMAN, SERVICE or AI. Mandatory: tokens without a valid value
 *                           are rejected
 * @param rolesClaim         claim holding the list of Nexora roles
 */
@Validated
@ConfigurationProperties("nexora.semantic.security")
public record SecurityProperties(
        @NotBlank String tenantClaim,
        @NotBlank String principalTypeClaim,
        @NotBlank String rolesClaim) {
}
