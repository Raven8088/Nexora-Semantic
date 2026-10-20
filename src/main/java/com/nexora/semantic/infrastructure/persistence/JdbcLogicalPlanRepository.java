package com.nexora.semantic.infrastructure.persistence;

import com.nexora.semantic.application.port.LogicalPlanRepository;
import com.nexora.semantic.domain.planning.LogicalPlan;
import com.nexora.semantic.domain.planning.PlanDependency;
import com.nexora.semantic.domain.planning.StoredPlan;
import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.PrincipalType;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DataSourceId;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.nexora.semantic.infrastructure.persistence.SqlTypes.instant;
import static com.nexora.semantic.infrastructure.persistence.SqlTypes.toDb;

/**
 * Logical plans as immutable JSON documents (the {@link LogicalPlan} record serialized as is) plus typed dependency
 * rows. Every statement is scoped by {@code tenant_id} and {@code data_source_id}; the tables are append-only.
 */
public class JdbcLogicalPlanRepository implements LogicalPlanRepository {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcLogicalPlanRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public StoredPlan insert(UUID id, LogicalPlan plan, PrincipalRef createdBy, Instant createdAt) {
        jdbc.sql("""
                        INSERT INTO logical_plan (id, tenant_id, data_source_id, format_version, fingerprint,
                               resolution_fingerprint, metadata_snapshot_id, request, plan, created_by,
                               created_by_type, created_at)
                        VALUES (:id, :tenantId, :dataSourceId, :formatVersion, :fingerprint, :resolutionFingerprint,
                                :snapshotId, CAST(:request AS jsonb), CAST(:plan AS jsonb), :createdBy,
                                :createdByType, :createdAt)""")
                .param("id", id)
                .param("tenantId", plan.tenantId())
                .param("dataSourceId", plan.dataSourceId())
                .param("formatVersion", plan.formatVersion())
                .param("fingerprint", plan.fingerprint())
                .param("resolutionFingerprint", plan.resolutionFingerprint())
                .param("snapshotId", plan.metadataSnapshotId())
                .param("request", objectMapper.writeValueAsString(plan.request()))
                .param("plan", objectMapper.writeValueAsString(plan))
                .param("createdBy", createdBy.id())
                .param("createdByType", createdBy.type().name())
                .param("createdAt", toDb(createdAt))
                .update();
        for (PlanDependency d : plan.dependencies()) {
            jdbc.sql("""
                            INSERT INTO logical_plan_dependency (tenant_id, data_source_id, plan_id, kind,
                                   reference_id, definition_id, relationship_id, object_id, revision, fingerprint,
                                   data_type, navigation)
                            VALUES (:tenantId, :dataSourceId, :planId, :kind, :referenceId, :definitionId,
                                    :relationshipId, :objectId, :revision, :fingerprint, :dataType, :navigation)""")
                    .param("tenantId", plan.tenantId())
                    .param("dataSourceId", plan.dataSourceId())
                    .param("planId", id)
                    .param("kind", d.kind().name())
                    .param("referenceId", d.referenceId())
                    .param("definitionId", d.kind() == PlanDependency.Kind.DEFINITION ? d.referenceId() : null)
                    .param("relationshipId", d.kind() == PlanDependency.Kind.RELATIONSHIP ? d.referenceId() : null)
                    .param("objectId", d.kind() == PlanDependency.Kind.PHYSICAL_OBJECT ? d.referenceId() : null)
                    .param("revision", d.revision())
                    .param("fingerprint", d.fingerprint())
                    .param("dataType", d.dataType())
                    .param("navigation", d.navigation() == null ? null : d.navigation().name())
                    .update();
        }
        return findById(new TenantId(plan.tenantId()), new DataSourceId(plan.dataSourceId()), id)
                .orElseThrow(() -> new IllegalStateException("plan not readable after insert"));
    }

    @Override
    public Optional<StoredPlan> findById(TenantId tenantId, DataSourceId dataSourceId, UUID id) {
        Optional<StoredPlan> plan = jdbc.sql("""
                        SELECT id, format_version, fingerprint, plan, created_by, created_by_type, created_at
                          FROM logical_plan
                         WHERE tenant_id = :tenantId AND data_source_id = :dataSourceId AND id = :id""")
                .param("tenantId", tenantId.value())
                .param("dataSourceId", dataSourceId.value())
                .param("id", id)
                .query((rs, rowNum) -> new StoredPlan(rs.getObject("id", UUID.class), tenantId, dataSourceId,
                        rs.getString("format_version"), rs.getString("fingerprint"),
                        new PrincipalRef(rs.getString("created_by"),
                                PrincipalType.valueOf(rs.getString("created_by_type"))),
                        instant(rs, "created_at"), rs.getString("plan"), List.of()))
                .optional();
        if (plan.isEmpty()) {
            return plan;
        }
        List<PlanDependency> dependencies = jdbc.sql("""
                        SELECT kind, reference_id, revision, fingerprint, data_type, navigation
                          FROM logical_plan_dependency
                         WHERE tenant_id = :tenantId AND data_source_id = :dataSourceId AND plan_id = :id
                         ORDER BY kind, reference_id""")
                .param("tenantId", tenantId.value())
                .param("dataSourceId", dataSourceId.value())
                .param("id", id)
                .query((rs, rowNum) -> new PlanDependency(PlanDependency.Kind.valueOf(rs.getString("kind")),
                        rs.getObject("reference_id", UUID.class), (Long) rs.getObject("revision"),
                        rs.getString("fingerprint"), rs.getString("data_type"),
                        rs.getString("navigation") == null ? null
                                : LogicalPlan.Navigation.valueOf(rs.getString("navigation"))))
                .list();
        StoredPlan p = plan.get();
        return Optional.of(new StoredPlan(p.id(), p.tenantId(), p.dataSourceId(), p.formatVersion(), p.fingerprint(),
                p.createdBy(), p.createdAt(), p.document(), dependencies));
    }
}
