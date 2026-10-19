package com.nexora.semantic.domain.shared;

import java.util.Objects;

/**
 * Who is calling and on behalf of which tenant. The tenant always comes from the authenticated token,
 * never from request paths or bodies.
 *
 * @param correlationId request correlation identifier for auditing; may be null outside HTTP requests
 */
public record CallerContext(TenantId tenantId, PrincipalRef principal, String correlationId) {

    public CallerContext {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(principal, "principal");
    }
}
