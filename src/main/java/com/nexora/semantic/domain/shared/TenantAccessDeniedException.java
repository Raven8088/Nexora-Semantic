package com.nexora.semantic.domain.shared;

/**
 * The caller is not bound to an active tenant: the claim is missing, malformed, unknown or the tenant is
 * suspended. All cases share one message so the response does not reveal which tenants exist.
 */
public class TenantAccessDeniedException extends DomainException {

    public TenantAccessDeniedException() {
        super("Tenant is not available for this principal");
    }
}
