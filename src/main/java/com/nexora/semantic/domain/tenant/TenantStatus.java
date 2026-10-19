package com.nexora.semantic.domain.tenant;

public enum TenantStatus {
    ACTIVE,
    /** No principal of the tenant may operate until it is reactivated. */
    SUSPENDED
}
