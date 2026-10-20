package com.nexora.semantic.domain.planning;

import com.nexora.semantic.domain.catalog.DefinitionKind;
import com.nexora.semantic.domain.catalog.DimensionType;
import com.nexora.semantic.domain.catalog.TimeGranularity;
import com.nexora.semantic.domain.relationship.ColumnPair;
import com.nexora.semantic.domain.relationship.ForeignKeyEvidence;
import com.nexora.semantic.domain.relationship.JoinType;
import com.nexora.semantic.domain.relationship.RelationshipContract;
import com.nexora.semantic.domain.relationship.RowMultiplicationRisk;
import com.nexora.semantic.domain.relationship.UniquenessEvidence;
import com.nexora.semantic.domain.planning.LogicalPlan.AggregationSafety;
import com.nexora.semantic.domain.planning.LogicalPlan.BaseObject;
import com.nexora.semantic.domain.planning.LogicalPlan.DefinitionSnapshot;
import com.nexora.semantic.domain.planning.LogicalPlan.FilterSnapshot;
import com.nexora.semantic.domain.planning.LogicalPlan.GroupingKey;
import com.nexora.semantic.domain.planning.LogicalPlan.InputGrain;
import com.nexora.semantic.domain.planning.LogicalPlan.JoinCondition;
import com.nexora.semantic.domain.planning.LogicalPlan.Navigation;
import com.nexora.semantic.domain.planning.LogicalPlan.ObjectSnapshot;
import com.nexora.semantic.domain.planning.LogicalPlan.OperationKind;
import com.nexora.semantic.domain.planning.LogicalPlan.PairSnapshot;
import com.nexora.semantic.domain.planning.LogicalPlan.PlanAggregation;
import com.nexora.semantic.domain.planning.LogicalPlan.PlanFilter;
import com.nexora.semantic.domain.planning.LogicalPlan.PlanJoin;
import com.nexora.semantic.domain.planning.LogicalPlan.PlanOperation;
import com.nexora.semantic.domain.planning.LogicalPlan.RelationshipSnapshot;
import com.nexora.semantic.domain.planning.LogicalPlan.RowPreservation;
import com.nexora.semantic.domain.planning.PlanDiagnostic.Code;
import com.nexora.semantic.domain.resolution.SemanticResolution;
import com.nexora.semantic.domain.resolution.SemanticResolution.FilterStage;
import com.nexora.semantic.domain.resolution.SemanticResolution.ResolvedConcept;
import com.nexora.semantic.domain.resolution.SemanticResolution.ResolvedDimension;
import com.nexora.semantic.domain.resolution.SemanticResolution.ResolvedFilter;
import com.nexora.semantic.domain.resolution.SemanticResolution.ResolvedMetric;
import com.nexora.semantic.domain.source.PhysicalObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * S4.2 logical planner (pure domain logic, deterministic, no SQL). Builds a {@link LogicalPlan} from an S4.1
 * {@link SemanticResolution} and the usable APPROVED relationships of the data source, or rejects the request.
 * <h2>Policy (conservative and verifiable with the existing metadata)</h2>
 * <ol>
 *   <li><b>Grain.</b> All metrics aggregate rows of one base object (the table or view of their concepts). Metrics
 *       over different objects are rejected ({@code INCOMPATIBLE_GRAIN}); no drill-across or pre-aggregation.</li>
 *   <li><b>Routes.</b> Every other object needed (dimension or filtered column) is reached from the base object
 *       through usable APPROVED relationships, using their exact column pairs, in either direction. All authorized
 *       simple routes up to {@link #MAX_DEPTH} hops are enumerated. More than one route ⇒ {@code AMBIGUOUS_PATH},
 *       whatever their safety: two routes mean two different semantics and none is chosen silently. Preferred
 *       relationships single a route out only when the whole route is made of preferred relationships.</li>
 *   <li><b>Fan-out.</b> Each join must reach a side unique by an exact primary key in the navigation direction
 *       ({@link RowMultiplicationRisk#NONE_BY_PRIMARY_KEY} from S3.1 evidence). A declared cardinality, a foreign
 *       key or a column name is never taken as proof ({@code UNIQUENESS_NOT_PROVEN} / {@code FANOUT_RISK}).
 *       {@code DISTINCT} is never used.</li>
 *   <li><b>JOIN types.</b> Only types the relationship authorizes, which S3.1 defines from source to target.
 *       Forward: LEFT when authorized (keeps every aggregated row), otherwise INNER. Reverse: INNER only (a reversed
 *       LEFT is a different, unauthorized join). An INNER join after a LEFT join is rejected (it would silently turn
 *       the LEFT into an INNER).</li>
 *   <li><b>Filters.</b> Dimension filters apply to rows before aggregation, metric filters to groups after it, as
 *       resolved by S4.1. A row filter on the optional side of a LEFT join is rejected ({@code UNSAFE_FILTER}).</li>
 *   <li><b>Units.</b> The catalog has no unit or currency metadata: none is attributed and metrics are never
 *       combined arithmetically.</li>
 * </ol>
 */
public final class LogicalPlanner {

    /** Usable relationships considered per data source. */
    public static final int MAX_RELATIONSHIPS = 500;
    /** Objects in a plan (base object included). */
    public static final int MAX_OBJECTS = 9;
    public static final int MAX_JOINS = MAX_OBJECTS - 1;
    /** Hops of a route. */
    public static final int MAX_DEPTH = 4;
    /** Authorized routes enumerated per required object. */
    public static final int MAX_ROUTES = 16;
    /** Graph expansions per required object. */
    public static final int MAX_EXPANSIONS = 20_000;
    private static final int MAX_REPORTED_ALTERNATIVES = 10;

    /** A relationship of the data source as the planner sees it: its S3.1 contract and its name. */
    public record Edge(RelationshipContract contract, String name) {
        public Edge {
            Objects.requireNonNull(contract, "contract");
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * @param objects       physical objects of the data source by id: at least the resolution's dependencies and
     *                      the objects and columns of the relationships
     * @param relationships every APPROVED relationship of the data source (usable or not)
     */
    public record Context(Map<UUID, PhysicalObject> objects, List<Edge> relationships) {
        public Context {
            objects = Map.copyOf(objects);
            relationships = List.copyOf(relationships);
        }
    }

    private record Hop(RelationshipContract contract, UUID from, UUID to, Navigation navigation) {
    }

    private record Enumeration(List<List<Hop>> routes, boolean overflow) {
    }

    public PlanningOutcome plan(PlanRequest request, SemanticResolution resolution, Context context) {
        List<PlanDiagnostic> diagnostics = new ArrayList<>();
        Map<UUID, TimeGranularity> granularities = granularities(request, resolution, diagnostics);

        Map<UUID, ResolvedConcept> concepts = new HashMap<>();
        resolution.concepts().forEach(c -> concepts.put(c.id(), c));
        List<ResolvedMetric> metrics = resolution.metrics();
        UUID base = concepts.get(metrics.getFirst().conceptId()).relationObjectId();
        for (int i = 1; i < metrics.size(); i++) {
            if (!concepts.get(metrics.get(i).conceptId()).relationObjectId().equals(base)) {
                diagnostics.add(PlanDiagnostic.of(Code.INCOMPATIBLE_GRAIN, "metrics[" + i + "]",
                        "metrics aggregate rows of different tables or views; combining them needs a strategy "
                                + "(drill-across) that is not available"));
            }
        }
        if (!diagnostics.isEmpty()) {
            return new PlanningOutcome.Rejected(diagnostics);
        }

        // Objects that must be reached from the base object, with the request field that needs them.
        Map<UUID, String> targets = new LinkedHashMap<>();
        for (int i = 0; i < resolution.dimensions().size(); i++) {
            UUID object = concepts.get(resolution.dimensions().get(i).conceptId()).relationObjectId();
            if (!object.equals(base)) {
                targets.putIfAbsent(object, "dimensions[" + i + "]");
            }
        }
        for (int i = 0; i < resolution.filters().size(); i++) {
            ResolvedFilter filter = resolution.filters().get(i);
            if (filter.stage() == FilterStage.PRE_AGGREGATION) {
                UUID object = context.objects().get(filter.columnObjectId()).parentId();
                if (!object.equals(base)) {
                    targets.putIfAbsent(object, "filters[" + i + "]");
                }
            }
        }
        if (targets.size() + 1 > MAX_OBJECTS) {
            return reject(Code.COMPLEXITY_LIMIT_EXCEEDED, "dimensions",
                    "a plan can involve at most " + MAX_OBJECTS + " tables or views");
        }

        List<RelationshipContract> approved = context.relationships().stream().map(Edge::contract)
                .filter(c -> !c.sourceObjectId().equals(c.targetObjectId())).toList();
        List<RelationshipContract> usable = approved.stream().filter(RelationshipContract::usable).toList();
        if (usable.size() > MAX_RELATIONSHIPS) {
            return reject(Code.COMPLEXITY_LIMIT_EXCEEDED, "dataSource",
                    "the data source has more than " + MAX_RELATIONSHIPS + " usable relationships to explore");
        }
        Map<UUID, List<Hop>> graph = graph(usable);
        Set<UUID> preferred = new HashSet<>();
        resolution.preferredRelationships().forEach(r -> preferred.add(r.relationshipId()));

        // Route selection: one authorized route per required object, merged into a tree rooted at the base.
        Map<UUID, Hop> tree = new LinkedHashMap<>();
        Map<UUID, String> hopField = new HashMap<>();
        for (Map.Entry<UUID, String> target : targets.entrySet()) {
            String field = target.getValue();
            Enumeration enumeration = enumerate(graph, base, target.getKey());
            if (enumeration.overflow()) {
                diagnostics.add(PlanDiagnostic.of(Code.COMPLEXITY_LIMIT_EXCEEDED, field,
                        "too many candidate routes (limits: " + MAX_DEPTH + " hops, " + MAX_ROUTES + " routes)"));
                continue;
            }
            List<List<Hop>> routes = enumeration.routes();
            if (routes.isEmpty()) {
                diagnostics.add(noRoute(approved, base, target.getKey(), field));
                continue;
            }
            if (!preferred.isEmpty()) {
                List<List<Hop>> preferredRoutes = routes.stream().filter(route -> route.stream()
                        .allMatch(h -> preferred.contains(h.contract().relationshipId()))).toList();
                if (!preferredRoutes.isEmpty()) {
                    routes = preferredRoutes;
                }
            }
            if (routes.size() > 1) {
                diagnostics.add(new PlanDiagnostic(Code.AMBIGUOUS_PATH, field,
                        "several approved routes reach this object; list the relationships of one route in "
                                + "preferredRelationships", List.of(),
                        routes.stream().limit(MAX_REPORTED_ALTERNATIVES).map(LogicalPlanner::ids).toList()));
                continue;
            }
            for (Hop hop : routes.getFirst()) {
                Hop existing = tree.get(hop.to());
                if (existing == null) {
                    tree.put(hop.to(), hop);
                    hopField.put(hop.to(), field);
                } else if (!existing.contract().relationshipId().equals(hop.contract().relationshipId())
                        || !existing.from().equals(hop.from())) {
                    diagnostics.add(new PlanDiagnostic(Code.AMBIGUOUS_PATH, field,
                            "two required objects need different routes to the same table or view",
                            List.of(existing.contract().relationshipId(), hop.contract().relationshipId()),
                            List.of()));
                    break;
                }
            }
        }
        if (!diagnostics.isEmpty()) {
            return new PlanningOutcome.Rejected(diagnostics);
        }
        if (tree.size() > MAX_JOINS) {
            return reject(Code.COMPLEXITY_LIMIT_EXCEEDED, "dimensions",
                    "a plan can contain at most " + MAX_JOINS + " joins");
        }

        // Joins in breadth-first order from the base object; safety of each one.
        List<Hop> ordered = order(base, tree);
        Map<UUID, Boolean> optional = new HashMap<>();
        optional.put(base, false);
        List<PlanJoin> joins = new ArrayList<>();
        for (Hop hop : ordered) {
            RelationshipContract c = hop.contract();
            String field = hopField.get(hop.to());
            boolean forward = hop.navigation() == Navigation.FORWARD;
            JoinType joinType = forward
                    ? (c.allowedJoinTypes().contains(JoinType.LEFT) ? JoinType.LEFT : JoinType.INNER)
                    : (c.allowedJoinTypes().contains(JoinType.INNER) ? JoinType.INNER : null);
            boolean parentOptional = optional.getOrDefault(hop.from(), false);
            if (joinType == null) {
                diagnostics.add(new PlanDiagnostic(Code.JOIN_NOT_AUTHORIZED, field,
                        "the relationship authorizes only LEFT joins from its source; navigating it in reverse "
                                + "would need a join it does not authorize", List.of(c.relationshipId()), List.of()));
                continue;
            }
            if (parentOptional && joinType == JoinType.INNER) {
                diagnostics.add(new PlanDiagnostic(Code.JOIN_NOT_AUTHORIZED, field,
                        "an INNER join after a LEFT join would discard the rows the LEFT join preserves",
                        List.of(c.relationshipId()), List.of()));
                continue;
            }
            RowMultiplicationRisk risk = forward ? c.evidence().sourceToTargetRisk() : c.evidence().targetToSourceRisk();
            UniquenessEvidence joinedSide = forward ? c.evidence().targetUniqueness() : c.evidence().sourceUniqueness();
            boolean declaredOne = forward ? c.declaredCardinality().targetOne() : c.declaredCardinality().sourceOne();
            if (risk != RowMultiplicationRisk.NONE_BY_PRIMARY_KEY) {
                diagnostics.add(new PlanDiagnostic(declaredOne ? Code.UNIQUENESS_NOT_PROVEN : Code.FANOUT_RISK, field,
                        declaredOne ? "the joined side is declared unique but no primary key proves it; "
                                + "aggregating after this join is not proven safe"
                                : "the joined side may have several rows per row being aggregated (fan-out)",
                        List.of(c.relationshipId()), List.of()));
                continue;
            }
            optional.put(hop.to(), parentOptional || joinType == JoinType.LEFT);
            List<JoinCondition> conditions = c.pairs().stream().map(p -> forward
                    ? new JoinCondition(p.sourceColumnId(), p.targetColumnId())
                    : new JoinCondition(p.targetColumnId(), p.sourceColumnId())).toList();
            joins.add(new PlanJoin(joins.size() + 1, c.relationshipId(), c.version(), hop.from(), hop.to(),
                    hop.navigation(), joinType, conditions, joinedSide, risk,
                    preservation(joinType, forward, c, context), optional.get(hop.to())));
        }

        List<ResolvedFilter> pre = new ArrayList<>();
        List<ResolvedFilter> post = new ArrayList<>();
        for (int i = 0; i < resolution.filters().size(); i++) {
            ResolvedFilter filter = resolution.filters().get(i);
            if (filter.stage() == FilterStage.POST_AGGREGATION) {
                post.add(filter);
                continue;
            }
            UUID object = context.objects().get(filter.columnObjectId()).parentId();
            if (Boolean.TRUE.equals(optional.get(object))) {
                diagnostics.add(PlanDiagnostic.of(Code.UNSAFE_FILTER, "filters[" + i + "]",
                        "a row filter on the optional side of a LEFT join would remove the rows the join keeps "
                                + "(or keep only unmatched ones); it cannot be applied safely"));
            } else {
                pre.add(filter);
            }
        }
        if (!diagnostics.isEmpty()) {
            return new PlanningOutcome.Rejected(diagnostics);
        }
        return new PlanningOutcome.Planned(build(request, resolution, context, base, granularities, joins, pre, post));
    }

    // --- granularity ---

    private static Map<UUID, TimeGranularity> granularities(PlanRequest request, SemanticResolution resolution,
                                                            List<PlanDiagnostic> diagnostics) {
        Map<UUID, ResolvedDimension> dimensions = new HashMap<>();
        resolution.dimensions().forEach(d -> dimensions.put(d.id(), d));
        Map<UUID, TimeGranularity> chosen = new HashMap<>();
        for (int i = 0; i < request.timeGranularities().size(); i++) {
            PlanRequest.GranularityChoice choice = request.timeGranularities().get(i);
            ResolvedDimension dimension = dimensions.get(choice.dimensionId());
            String field = "timeGranularities[" + i + "]";
            if (dimension == null || dimension.type() != DimensionType.TEMPORAL) {
                diagnostics.add(PlanDiagnostic.of(Code.GRANULARITY_INVALID, field + ".dimensionId",
                        "must be one of the requested TEMPORAL dimensions"));
            } else if (!dimension.granularities().contains(choice.granularity())) {
                diagnostics.add(PlanDiagnostic.of(Code.GRANULARITY_INVALID, field + ".granularity",
                        "is not allowed by the dimension"));
            } else if (chosen.putIfAbsent(choice.dimensionId(), choice.granularity()) != null) {
                diagnostics.add(PlanDiagnostic.of(Code.GRANULARITY_INVALID, field + ".dimensionId",
                        "the dimension already has a granularity"));
            }
        }
        for (int i = 0; i < resolution.dimensions().size(); i++) {
            ResolvedDimension dimension = resolution.dimensions().get(i);
            if (dimension.type() == DimensionType.TEMPORAL && !chosen.containsKey(dimension.id())) {
                diagnostics.add(PlanDiagnostic.of(Code.GRANULARITY_REQUIRED, "dimensions[" + i + "]",
                        "a TEMPORAL dimension needs a time granularity in timeGranularities"));
            }
        }
        return chosen;
    }

    // --- routes ---

    private static Map<UUID, List<Hop>> graph(List<RelationshipContract> relationships) {
        Map<UUID, List<Hop>> graph = new HashMap<>();
        for (RelationshipContract c : relationships) {
            graph.computeIfAbsent(c.sourceObjectId(), k -> new ArrayList<>())
                    .add(new Hop(c, c.sourceObjectId(), c.targetObjectId(), Navigation.FORWARD));
            graph.computeIfAbsent(c.targetObjectId(), k -> new ArrayList<>())
                    .add(new Hop(c, c.targetObjectId(), c.sourceObjectId(), Navigation.REVERSE));
        }
        Comparator<Hop> deterministic = Comparator.comparing((Hop h) -> h.contract().relationshipId())
                .thenComparing(Hop::navigation);
        graph.values().forEach(hops -> hops.sort(deterministic));
        return graph;
    }

    /** Every simple route from {@code from} to {@code to}, bounded in depth, count and work. */
    private static Enumeration enumerate(Map<UUID, List<Hop>> graph, UUID from, UUID to) {
        List<List<Hop>> routes = new ArrayList<>();
        int[] expansions = {0};
        boolean overflow = !dfs(graph, from, to, new LinkedHashSet<>(List.of(from)), new ArrayDeque<>(), routes,
                expansions);
        return new Enumeration(routes, overflow);
    }

    /** @return false when a limit was exceeded */
    private static boolean dfs(Map<UUID, List<Hop>> graph, UUID current, UUID target, Set<UUID> visited,
                               Deque<Hop> path, List<List<Hop>> routes, int[] expansions) {
        if (current.equals(target)) {
            routes.add(List.copyOf(path));
            return routes.size() <= MAX_ROUTES;
        }
        if (path.size() == MAX_DEPTH) {
            return true;
        }
        for (Hop hop : graph.getOrDefault(current, List.of())) {
            if (++expansions[0] > MAX_EXPANSIONS) {
                return false;
            }
            if (visited.contains(hop.to())) {
                continue;
            }
            visited.add(hop.to());
            path.addLast(hop);
            boolean ok = dfs(graph, hop.to(), target, visited, path, routes, expansions);
            path.removeLast();
            visited.remove(hop.to());
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** No usable route: say whether only unusable APPROVED relationships would connect the objects. */
    private static PlanDiagnostic noRoute(List<RelationshipContract> approved, UUID base, UUID target, String field) {
        Enumeration withUnusable = enumerate(graph(approved), base, target);
        if (!withUnusable.routes().isEmpty()) {
            List<UUID> unusable = withUnusable.routes().stream().flatMap(List::stream).map(Hop::contract)
                    .filter(c -> !c.usable()).map(RelationshipContract::relationshipId).distinct().sorted().toList();
            return new PlanDiagnostic(Code.RELATIONSHIP_NOT_USABLE, field,
                    "only relationships that are not usable (physically invalid) connect this object",
                    unusable, List.of());
        }
        return PlanDiagnostic.of(Code.NO_PATH, field,
                "no route of usable approved relationships connects this object with the metrics' table or view");
    }

    private static List<Hop> order(UUID base, Map<UUID, Hop> tree) {
        List<Hop> ordered = new ArrayList<>();
        Deque<UUID> queue = new ArrayDeque<>(List.of(base));
        while (!queue.isEmpty()) {
            UUID current = queue.removeFirst();
            tree.values().stream().filter(h -> h.from().equals(current))
                    .sorted(Comparator.comparing((Hop h) -> h.contract().relationshipId()).thenComparing(Hop::to))
                    .forEach(h -> {
                        ordered.add(h);
                        queue.addLast(h.to());
                    });
        }
        return ordered;
    }

    private static List<UUID> ids(List<Hop> route) {
        return route.stream().map(h -> h.contract().relationshipId()).toList();
    }

    private static RowPreservation preservation(JoinType joinType, boolean forward, RelationshipContract c,
                                                Context context) {
        if (joinType == JoinType.LEFT) {
            return RowPreservation.PRESERVED;
        }
        boolean notNull = c.pairs().stream().map(ColumnPair::sourceColumnId).map(context.objects()::get)
                .allMatch(col -> col != null && Boolean.FALSE.equals(col.attributes().values().get("nullable")));
        return forward && c.evidence().foreignKey() == ForeignKeyEvidence.PRESENT && notNull
                ? RowPreservation.PRESERVED_BY_FOREIGN_KEY : RowPreservation.UNMATCHED_ROWS_EXCLUDED;
    }

    private static PlanningOutcome reject(Code code, String field, String message) {
        return new PlanningOutcome.Rejected(List.of(PlanDiagnostic.of(code, field, message)));
    }

    // --- plan ---

    private LogicalPlan build(PlanRequest request, SemanticResolution resolution, Context context, UUID base,
                              Map<UUID, TimeGranularity> granularities, List<PlanJoin> joins,
                              List<ResolvedFilter> pre, List<ResolvedFilter> post) {
        Map<UUID, PhysicalObject> objects = context.objects();
        Map<UUID, ResolvedConcept> concepts = new LinkedHashMap<>();
        resolution.concepts().forEach(c -> concepts.put(c.id(), c));
        PhysicalObject baseObject = objects.get(base);

        List<String> primaryKey = baseObject.attributes().values().get("primaryKey") instanceof List<?> pk
                ? pk.stream().map(String::valueOf).toList() : List.of();
        InputGrain inputGrain = new InputGrain(base, primaryKey,
                primaryKey.isEmpty() ? UniquenessEvidence.UNKNOWN : UniquenessEvidence.PRIMARY_KEY);

        List<GroupingKey> outputGrain = resolution.dimensions().stream().map(d -> new GroupingKey(d.id(), d.key(),
                concepts.get(d.conceptId()).relationObjectId(), d.columnObjectId(), d.type(), d.valueType(),
                granularities.get(d.id()), d.type() == DimensionType.TEMPORAL ? d.timeZone() : null)).toList();

        List<PlanFilter> preFilters = new ArrayList<>();
        for (ResolvedFilter f : pre) {
            preFilters.add(new PlanFilter(preFilters.size() + 1, f.definitionId(), f.definitionKind(), f.operator(),
                    f.valueType(), objects.get(f.columnObjectId()).parentId(), f.columnObjectId(),
                    f.values().stream().map(v -> v.canonical()).toList()));
        }
        List<PlanFilter> postFilters = new ArrayList<>();
        for (ResolvedFilter f : post) {
            postFilters.add(new PlanFilter(postFilters.size() + 1, f.definitionId(), f.definitionKind(),
                    f.operator(), f.valueType(), null, f.columnObjectId(),
                    f.values().stream().map(v -> v.canonical()).toList()));
        }
        List<PlanAggregation> aggregations = new ArrayList<>();
        for (ResolvedMetric m : resolution.metrics()) {
            aggregations.add(new PlanAggregation(aggregations.size() + 1, m.id(), m.key(), m.aggregation(),
                    m.columnObjectId()));
        }

        List<PlanOperation> operations = new ArrayList<>();
        operations.add(new PlanOperation(1, OperationKind.SCAN, List.of()));
        joins.forEach(j -> operations.add(new PlanOperation(operations.size() + 1, OperationKind.JOIN,
                List.of(j.order()))));
        if (!preFilters.isEmpty()) {
            operations.add(new PlanOperation(operations.size() + 1, OperationKind.FILTER_ROWS,
                    preFilters.stream().map(PlanFilter::order).toList()));
        }
        operations.add(new PlanOperation(operations.size() + 1, OperationKind.GROUP_AND_AGGREGATE, List.of()));
        if (!postFilters.isEmpty()) {
            operations.add(new PlanOperation(operations.size() + 1, OperationKind.FILTER_GROUPS,
                    postFilters.stream().map(PlanFilter::order).toList()));
        }

        // Copies of everything the plan relies on.
        List<DefinitionSnapshot> definitions = new ArrayList<>();
        resolution.metrics().forEach(m -> definitions.add(new DefinitionSnapshot(m.id(), DefinitionKind.METRIC,
                m.key(), m.name(), m.version(), m.conceptId(), null, m.aggregation(), m.columnObjectId(), null,
                null, null)));
        List<ResolvedDimension> allDimensions = new ArrayList<>(resolution.dimensions());
        allDimensions.addAll(resolution.filterDimensions());
        allDimensions.forEach(d -> definitions.add(new DefinitionSnapshot(d.id(), DefinitionKind.DIMENSION, d.key(),
                d.name(), d.version(), d.conceptId(), null, null, d.columnObjectId(), d.type(), d.granularities(),
                d.timeZone())));
        resolution.concepts().forEach(c -> definitions.add(new DefinitionSnapshot(c.id(), DefinitionKind.CONCEPT,
                c.key(), c.name(), c.version(), null, c.relationObjectId(), null, null, null, null, null)));

        Map<UUID, RelationshipContract> contracts = new HashMap<>();
        Map<UUID, String> names = new HashMap<>();
        context.relationships().forEach(e -> {
            contracts.put(e.contract().relationshipId(), e.contract());
            names.put(e.contract().relationshipId(), e.name());
        });
        Set<UUID> objectIds = new LinkedHashSet<>();
        objectIds.add(base);
        resolution.physicalDependencies().forEach(d -> objectIds.add(d.objectId()));
        List<RelationshipSnapshot> relationships = new ArrayList<>();
        for (PlanJoin join : joins) {
            RelationshipContract c = contracts.get(join.relationshipId());
            objectIds.add(c.sourceObjectId());
            objectIds.add(c.targetObjectId());
            List<PairSnapshot> pairs = new ArrayList<>();
            for (ColumnPair p : c.pairs()) {
                objectIds.add(p.sourceColumnId());
                objectIds.add(p.targetColumnId());
                pairs.add(new PairSnapshot(p.sourceColumnId(), naturalKey(objects, p.sourceColumnId()),
                        p.targetColumnId(), naturalKey(objects, p.targetColumnId())));
            }
            relationships.add(new RelationshipSnapshot(c.relationshipId(), c.version(), names.get(c.relationshipId()),
                    c.origin(), c.sourceObjectId(), c.targetObjectId(), pairs, c.declaredCardinality(),
                    c.allowedJoinTypes().stream().sorted().toList(), c.foreignKeyId(),
                    c.evidence().sourceUniqueness(), c.evidence().targetUniqueness(), c.evidence().foreignKey(),
                    c.evidence().cardinalityAssurance(), c.evidence().sourceToTargetRisk(),
                    c.evidence().targetToSourceRisk()));
        }
        List<ObjectSnapshot> physicalObjects = objectIds.stream().map(objects::get).filter(Objects::nonNull)
                .sorted(Comparator.comparing(o -> o.naturalKey().value()))
                .map(o -> new ObjectSnapshot(o.id(), o.type(), o.naturalKey().value(),
                        o.attributes().values().get("dataType") instanceof String t ? t : null,
                        o.attributes().values().get("nullable") instanceof Boolean b ? b : null,
                        o.fingerprint(), o.lastSeenSnapshotId()))
                .toList();

        List<FilterSnapshot> requestFilters = resolution.filters().stream().map(f -> new FilterSnapshot(
                f.definitionId(), f.operator(), f.values().stream().map(v -> v.canonical()).toList())).toList();
        LogicalPlan.RequestSnapshot requestSnapshot = new LogicalPlan.RequestSnapshot(
                request.resolution().metricIds(), request.resolution().dimensionIds(), requestFilters,
                request.resolution().preferredRelationshipIds(), request.timeGranularities());

        LogicalPlan draft = new LogicalPlan(LogicalPlan.FORMAT_VERSION, resolution.tenantId().value(),
                resolution.dataSourceId().value(), resolution.metadataSnapshotId(), resolution.fingerprint(),
                requestSnapshot, new BaseObject(base, baseObject.naturalKey().value()), inputGrain, outputGrain, joins,
                preFilters, aggregations, postFilters, operations,
                joins.isEmpty() ? AggregationSafety.SINGLE_OBJECT : AggregationSafety.JOINS_TO_PRIMARY_KEY_ONLY,
                definitions, relationships, physicalObjects, null);
        return new LogicalPlan(draft.formatVersion(), draft.tenantId(), draft.dataSourceId(),
                draft.metadataSnapshotId(), draft.resolutionFingerprint(), draft.request(), draft.base(),
                draft.inputGrain(), draft.outputGrain(), draft.joins(), draft.preAggregationFilters(),
                draft.aggregations(), draft.postAggregationFilters(), draft.operations(), draft.safety(),
                draft.definitions(), draft.relationships(), draft.physicalObjects(), sha256(draft.toString()));
    }

    private static String naturalKey(Map<UUID, PhysicalObject> objects, UUID id) {
        PhysicalObject object = objects.get(id);
        return object == null ? null : object.naturalKey().value();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
