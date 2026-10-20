package com.nexora.semantic.application.planning;

import com.nexora.semantic.application.catalog.CatalogContextLoader;
import com.nexora.semantic.application.catalog.SemanticRelationshipService;
import com.nexora.semantic.application.catalog.SemanticRelationshipService.EvaluatedRelationship;
import com.nexora.semantic.application.port.AuditLog;
import com.nexora.semantic.application.port.DataSourceRepository;
import com.nexora.semantic.application.port.LogicalPlanRepository;
import com.nexora.semantic.application.port.PhysicalObjectRepository;
import com.nexora.semantic.application.port.SemanticDefinitionRepository;
import com.nexora.semantic.application.resolution.SemanticResolutionService;
import com.nexora.semantic.domain.audit.AuditAction;
import com.nexora.semantic.domain.audit.AuditEvent;
import com.nexora.semantic.domain.audit.AuditTargetType;
import com.nexora.semantic.domain.catalog.DefinitionValidator;
import com.nexora.semantic.domain.catalog.SemanticDefinition;
import com.nexora.semantic.domain.planning.LogicalPlan;
import com.nexora.semantic.domain.planning.LogicalPlanner;
import com.nexora.semantic.domain.planning.PlanDependency;
import com.nexora.semantic.domain.planning.PlanRejectedException;
import com.nexora.semantic.domain.planning.PlanRequest;
import com.nexora.semantic.domain.planning.PlanValidity;
import com.nexora.semantic.domain.planning.PlanValidityChecker;
import com.nexora.semantic.domain.planning.PlanningOutcome;
import com.nexora.semantic.domain.planning.StoredPlan;
import com.nexora.semantic.domain.resolution.ResolutionOutcome;
import com.nexora.semantic.domain.resolution.SemanticResolution;
import com.nexora.semantic.domain.resolution.UnresolvableRequestException;
import com.nexora.semantic.domain.shared.CallerContext;
import com.nexora.semantic.domain.shared.ResourceNotFoundException;
import com.nexora.semantic.domain.source.DataSource;
import com.nexora.semantic.domain.source.DataSourceArchivedException;
import com.nexora.semantic.domain.source.DataSourceId;
import com.nexora.semantic.domain.source.PhysicalObject;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * S4.2 use cases: create a safe logical plan (persisted, immutable, audited), read it, and check its validity.
 * <p>
 * Consistency of creation: the data source row is locked first ({@code FOR UPDATE}), exactly like every catalog
 * write, relationship write and metadata import of that source. While the lock is held none of them can commit, so
 * the S4.1 resolution (re-run, never reused), the relationship graph, the safety checks, the snapshot copied into
 * the plan and the insert all see the same catalog state in one transaction. Reads and validity checks are
 * read-only and change nothing.
 */
@Transactional
public class LogicalPlanService {

    private static final String RESOURCE = "Logical plan";

    private final DataSourceRepository dataSources;
    private final SemanticResolutionService resolutions;
    private final SemanticRelationshipService relationships;
    private final SemanticDefinitionRepository definitions;
    private final PhysicalObjectRepository objects;
    private final LogicalPlanRepository plans;
    private final AuditLog auditLog;
    private final Clock clock;
    private final CatalogContextLoader loader;
    private final LogicalPlanner planner = new LogicalPlanner();
    private final PlanValidityChecker checker = new PlanValidityChecker();

    public LogicalPlanService(DataSourceRepository dataSources, SemanticResolutionService resolutions,
                              SemanticRelationshipService relationships, SemanticDefinitionRepository definitions,
                              PhysicalObjectRepository objects, LogicalPlanRepository plans, AuditLog auditLog,
                              Clock clock) {
        this.dataSources = dataSources;
        this.resolutions = resolutions;
        this.relationships = relationships;
        this.definitions = definitions;
        this.objects = objects;
        this.plans = plans;
        this.auditLog = auditLog;
        this.clock = clock;
        this.loader = new CatalogContextLoader(objects, definitions);
    }

    /**
     * @throws UnresolvableRequestException if S4.1 cannot resolve the request (422 semantic_resolution_failed)
     * @throws PlanRejectedException        if it resolves but cannot be planned safely (422 logical_plan_rejected)
     */
    public StoredPlan create(CallerContext caller, DataSourceId dataSourceId, PlanRequest request) {
        DataSource dataSource = dataSources.lockById(caller.tenantId(), dataSourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Data source"));
        if (!dataSource.isActive()) {
            throw new DataSourceArchivedException();
        }
        SemanticResolution resolution = switch (resolutions.resolve(caller, dataSourceId, request.resolution())) {
            case ResolutionOutcome.Resolved resolved -> resolved.resolution();
            case ResolutionOutcome.Unresolvable unresolvable ->
                    throw new UnresolvableRequestException(unresolvable.diagnostics());
        };

        List<EvaluatedRelationship> approved = relationships.approved(caller, dataSourceId);
        Map<UUID, PhysicalObject> known = new HashMap<>();
        approved.forEach(r -> known.putAll(r.references()));
        objects.findByIds(caller.tenantId(), dataSourceId, resolution.physicalDependencies().stream()
                .map(SemanticResolution.PhysicalDependency::objectId).toList()).forEach(o -> known.put(o.id(), o));
        LogicalPlanner.Context context = new LogicalPlanner.Context(known, approved.stream()
                .map(r -> new LogicalPlanner.Edge(r.contract(), r.relationship().name())).toList());

        LogicalPlan plan = switch (planner.plan(request, resolution, context)) {
            case PlanningOutcome.Planned planned -> planned.plan();
            case PlanningOutcome.Rejected rejected -> throw new PlanRejectedException(rejected.diagnostics());
        };
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        StoredPlan stored = plans.insert(UUID.randomUUID(), plan, caller.principal(), now);

        // Counts and identifiers only: filter values may contain business information.
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("dataSourceId", dataSourceId.value().toString());
        details.put("formatVersion", plan.formatVersion());
        details.put("fingerprint", plan.fingerprint());
        details.put("metrics", plan.aggregations().size());
        details.put("dimensions", plan.outputGrain().size());
        details.put("joins", plan.joins().size());
        details.put("filters", plan.preAggregationFilters().size() + plan.postAggregationFilters().size());
        details.put("safety", plan.safety().name());
        auditLog.append(AuditEvent.of(caller.tenantId(), now, caller.principal(), caller.correlationId(),
                AuditAction.LOGICAL_PLAN_CREATED, AuditTargetType.LOGICAL_PLAN, stored.id(), details));
        return stored;
    }

    /** The historical snapshot exactly as stored. */
    @Transactional(readOnly = true)
    public StoredPlan get(CallerContext caller, DataSourceId dataSourceId, UUID planId) {
        dataSources.findById(caller.tenantId(), dataSourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Data source"));
        return plans.findById(caller.tenantId(), dataSourceId, planId)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE));
    }

    /** Compares the plan's recorded revisions with the current catalog, in one consistent snapshot. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public PlanValidity validity(CallerContext caller, DataSourceId dataSourceId, UUID planId) {
        DataSource dataSource = dataSources.findById(caller.tenantId(), dataSourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Data source"));
        StoredPlan plan = plans.findById(caller.tenantId(), dataSourceId, planId)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE));

        List<UUID> definitionIds = ids(plan, PlanDependency.Kind.DEFINITION);
        List<SemanticDefinition> current = definitionIds.isEmpty() ? List.of()
                : definitions.findByIds(caller.tenantId(), dataSourceId, definitionIds);
        DefinitionValidator.Context catalog = loader.load(caller.tenantId(), dataSourceId, dataSource.engine(),
                current.stream().map(SemanticDefinition::spec).toList(), current);
        Map<UUID, PhysicalObject> currentObjects = new HashMap<>();
        List<UUID> objectIds = ids(plan, PlanDependency.Kind.PHYSICAL_OBJECT);
        if (!objectIds.isEmpty()) {
            objects.findByIds(caller.tenantId(), dataSourceId, objectIds).forEach(o -> currentObjects.put(o.id(), o));
        }
        List<UUID> relationshipIds = ids(plan, PlanDependency.Kind.RELATIONSHIP);
        return checker.check(plan, new PlanValidityChecker.Current(dataSource.isActive(), dataSource.engine(),
                catalog, relationshipIds.isEmpty() ? Map.of()
                : relationships.contracts(caller, dataSourceId, relationshipIds), currentObjects));
    }

    private static List<UUID> ids(StoredPlan plan, PlanDependency.Kind kind) {
        return plan.dependencies().stream().filter(d -> d.kind() == kind).map(PlanDependency::referenceId).toList();
    }
}
