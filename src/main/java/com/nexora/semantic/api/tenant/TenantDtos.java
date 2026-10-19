package com.nexora.semantic.api.tenant;

import com.nexora.semantic.domain.tenant.Tenant;
import com.nexora.semantic.domain.tenant.TenantStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

public final class TenantDtos {

    private TenantDtos() {
    }

    public record CreateTenantRequest(
            @NotBlank @Size(max = 63) @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,62}") String key,
            @NotBlank @Size(max = 200) String name) {
    }

    /** Partial update: absent fields are unchanged. The key is immutable (it is the tenant claim of tokens). */
    public record UpdateTenantRequest(
            @Size(min = 1, max = 200) String name,
            TenantStatus status) {
    }

    public record TenantResponse(
            UUID id,
            String key,
            String name,
            TenantStatus status,
            Instant createdAt,
            String createdBy,
            Instant updatedAt,
            String updatedBy,
            long version) {

        static TenantResponse from(Tenant tenant) {
            return new TenantResponse(tenant.id().value(), tenant.key().value(), tenant.name(), tenant.status(),
                    tenant.createdAt(), tenant.createdBy(), tenant.updatedAt(), tenant.updatedBy(), tenant.version());
        }
    }
}
