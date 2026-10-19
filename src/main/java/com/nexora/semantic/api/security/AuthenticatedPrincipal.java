package com.nexora.semantic.api.security;

import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.PrincipalType;
import com.nexora.semantic.domain.shared.TenantId;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Identity extracted from a validated access token.
 *
 * @param tenantId tenant UUID carried by the token; null only for platform principals without a tenant. Whether
 *                 the tenant exists and is ACTIVE is checked on each tenant-scoped request.
 */
public record AuthenticatedPrincipal(
        String subject,
        PrincipalType type,
        TenantId tenantId,
        Set<Permission> permissions) {

    public AuthenticatedPrincipal {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(type, "type");
        permissions = permissions.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(permissions));
    }

    public PrincipalRef toRef() {
        return new PrincipalRef(subject, type);
    }
}
