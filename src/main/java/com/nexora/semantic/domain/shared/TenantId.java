package com.nexora.semantic.domain.shared;

import java.util.Objects;
import java.util.UUID;

/** Identity of a tenant (company). Every tenant-scoped operation requires one. */
public record TenantId(UUID value) {

    public TenantId {
        Objects.requireNonNull(value, "value");
    }

    public static TenantId newId() {
        return new TenantId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
