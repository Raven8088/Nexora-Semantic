package com.nexora.semantic.application.port;

import com.nexora.semantic.domain.planning.LogicalPlan;
import com.nexora.semantic.domain.planning.StoredPlan;
import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DataSourceId;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Immutable logical plans (S4.2), scoped to tenant and data source. Plans are never updated or deleted. */
public interface LogicalPlanRepository {

    /** Stores the plan document and its dependencies; returns them exactly as stored. */
    StoredPlan insert(UUID id, LogicalPlan plan, PrincipalRef createdBy, Instant createdAt);

    Optional<StoredPlan> findById(TenantId tenantId, DataSourceId dataSourceId, UUID id);
}
