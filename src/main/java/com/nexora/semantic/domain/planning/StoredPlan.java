package com.nexora.semantic.domain.planning;

import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DataSourceId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A persisted, immutable logical plan: the plan document exactly as stored (JSON of {@link LogicalPlan}) and the
 * typed dependencies used to check its validity. Rows are never updated or deleted.
 */
public record StoredPlan(UUID id, TenantId tenantId, DataSourceId dataSourceId, String formatVersion,
                         String fingerprint, PrincipalRef createdBy, Instant createdAt, String document,
                         List<PlanDependency> dependencies) {

    public StoredPlan {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        Objects.requireNonNull(formatVersion, "formatVersion");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(document, "document");
        dependencies = List.copyOf(dependencies);
    }
}
