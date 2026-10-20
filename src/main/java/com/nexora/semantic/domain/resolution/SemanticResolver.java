package com.nexora.semantic.domain.resolution;

import com.nexora.semantic.domain.catalog.DefinitionIssue;
import com.nexora.semantic.domain.catalog.DefinitionKind;
import com.nexora.semantic.domain.catalog.DefinitionSpec.ConceptSpec;
import com.nexora.semantic.domain.catalog.DefinitionSpec.DimensionSpec;
import com.nexora.semantic.domain.catalog.DefinitionSpec.MetricSpec;
import com.nexora.semantic.domain.catalog.DefinitionStatus;
import com.nexora.semantic.domain.catalog.DefinitionValidator;
import com.nexora.semantic.domain.catalog.SemanticDefinition;
import com.nexora.semantic.domain.catalog.TypeCategory;
import com.nexora.semantic.domain.relationship.RelationshipContract;
import com.nexora.semantic.domain.resolution.ResolutionDiagnostic.Code;
import com.nexora.semantic.domain.resolution.ResolutionRequest.FilterRequest;
import com.nexora.semantic.domain.resolution.SemanticResolution.FilterStage;
import com.nexora.semantic.domain.resolution.SemanticResolution.PhysicalDependency;
import com.nexora.semantic.domain.resolution.SemanticResolution.ResolvedConcept;
import com.nexora.semantic.domain.resolution.SemanticResolution.ResolvedDimension;
import com.nexora.semantic.domain.resolution.SemanticResolution.ResolvedFilter;
import com.nexora.semantic.domain.resolution.SemanticResolution.ResolvedMetric;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DataSourceId;
import com.nexora.semantic.domain.source.PhysicalObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * S4.1 semantic resolution (pure domain logic): turns a structured request into a {@link SemanticResolution} or into
 * typed diagnostics, against one consistent view of the catalog.
 * <ul>
 *   <li>Every referenced definition must exist in the tenant and data source (the lookup maps are scoped), have the
 *       expected kind, be APPROVED and pass {@link DefinitionValidator#usageIssues} (physical binding and concept
 *       still valid). Approval rules are not re-implemented here: only the stored lifecycle is read.</li>
 *   <li>Filters are typed by the field: dimensions filter rows before aggregation (by the column's type category);
 *       requested metrics filter aggregated values after aggregation (numeric comparisons only). Anything else is
 *       rejected rather than assumed.</li>
 *   <li>Preferred relationships must be usable (S3.1 contract); they are echoed, never selected as a route.</li>
 * </ul>
 * No SQL, JOIN or route is produced, and nothing is assumed about how objects connect.
 */
public final class SemanticResolver {

    public static final int MAX_METRICS = 20;
    public static final int MAX_DIMENSIONS = 20;
    public static final int MAX_FILTERS = 50;
    public static final int MAX_IN_VALUES = 100;
    public static final int MAX_PREFERRED_RELATIONSHIPS = 20;
    public static final int MAX_TEXT_LENGTH = 1000;

    /**
     * Everything read for one request, from one consistent snapshot. Maps are scoped to the tenant and data source.
     *
     * @param catalog       referenced definitions (and their concepts) and physical objects
     * @param relationships S3.1 contracts of the preferred relationships that exist in the data source
     */
    public record Context(TenantId tenantId, DataSourceId dataSourceId, UUID metadataSnapshotId,
                          DefinitionValidator.Context catalog, Map<UUID, RelationshipContract> relationships) {
        public Context {
            Objects.requireNonNull(tenantId, "tenantId");
            Objects.requireNonNull(dataSourceId, "dataSourceId");
            Objects.requireNonNull(catalog, "catalog");
            relationships = Map.copyOf(relationships);
        }
    }

    private static final Pattern UUID_FORMAT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Set<TypeCategory> ORDERED = EnumSet.of(TypeCategory.NUMERIC, TypeCategory.DATE,
            TypeCategory.TIMESTAMP, TypeCategory.TIMESTAMP_TZ, TypeCategory.TIME);
    private static final Set<TypeCategory> EQUALITY = EnumSet.of(TypeCategory.NUMERIC, TypeCategory.TEXT,
            TypeCategory.BOOLEAN, TypeCategory.DATE, TypeCategory.TIMESTAMP, TypeCategory.TIMESTAMP_TZ,
            TypeCategory.TIME, TypeCategory.UUID);

    private final DefinitionValidator validator = new DefinitionValidator();

    public ResolutionOutcome resolve(ResolutionRequest request, Context context) {
        List<ResolutionDiagnostic> diagnostics = new ArrayList<>();
        if (request.metricIds().isEmpty()) {
            diagnostics.add(ResolutionDiagnostic.of(Code.NO_METRICS, "metrics", "at least one metric is required"));
        }
        Set<UUID> metricIds = unique(request.metricIds(), "metrics", diagnostics);
        unique(request.dimensionIds(), "dimensions", diagnostics);
        unique(request.preferredRelationshipIds(), "preferredRelationships", diagnostics);

        List<SemanticDefinition> metrics = definitions(request.metricIds(), DefinitionKind.METRIC,
                Code.METRIC_NOT_FOUND, "metrics", context, diagnostics);
        List<SemanticDefinition> dimensions = definitions(request.dimensionIds(), DefinitionKind.DIMENSION,
                Code.DIMENSION_NOT_FOUND, "dimensions", context, diagnostics);

        List<ResolvedFilter> filters = new ArrayList<>();
        List<SemanticDefinition> filterDefinitions = new ArrayList<>();
        Set<String> seenFilters = new HashSet<>();
        for (int i = 0; i < request.filters().size(); i++) {
            FilterRequest filter = request.filters().get(i);
            String field = "filters[" + i + "]";
            if (!seenFilters.add(filter.definitionId() + "|" + filter.operator() + "|" + filter.values())) {
                diagnostics.add(ResolutionDiagnostic.of(Code.DUPLICATE_REFERENCE, field, "the same filter is repeated"));
                continue;
            }
            ResolvedFilter resolved = filter(filter, field, metricIds, context, diagnostics, filterDefinitions);
            if (resolved != null) {
                filters.add(resolved);
            }
        }

        List<RelationshipContract> relationships = new ArrayList<>();
        Set<UUID> processed = new HashSet<>();
        for (int i = 0; i < request.preferredRelationshipIds().size(); i++) {
            UUID id = request.preferredRelationshipIds().get(i);
            String field = "preferredRelationships[" + i + "]";
            if (!processed.add(id)) {
                continue;
            }
            RelationshipContract contract = context.relationships().get(id);
            if (contract == null) {
                diagnostics.add(ResolutionDiagnostic.of(Code.PREFERRED_RELATIONSHIP_NOT_FOUND, field,
                        "no relationship with this id in this data source"));
            } else if (!contract.usable()) {
                List<String> causes = new ArrayList<>();
                causes.add("STATUS_" + contract.status().name());
                contract.invalidReasons().forEach(r -> causes.add(r.code().name()));
                diagnostics.add(new ResolutionDiagnostic(Code.PREFERRED_RELATIONSHIP_NOT_USABLE, field,
                        "the relationship must be APPROVED and physically valid", causes.stream().distinct().toList()));
            } else {
                relationships.add(contract);
            }
        }

        if (!diagnostics.isEmpty()) {
            return new ResolutionOutcome.Unresolvable(diagnostics);
        }
        return new ResolutionOutcome.Resolved(build(request, context, metrics, dimensions, filterDefinitions, filters,
                relationships));
    }

    // --- definitions ---

    /** Usable definitions in request order; repeated ids are reported once (by {@link #unique}) and skipped. */
    private List<SemanticDefinition> definitions(List<UUID> ids, DefinitionKind kind, Code notFound, String field,
                                                 Context context, List<ResolutionDiagnostic> diagnostics) {
        List<SemanticDefinition> usable = new ArrayList<>();
        Set<UUID> processed = new HashSet<>();
        for (int i = 0; i < ids.size(); i++) {
            if (processed.add(ids.get(i))) {
                SemanticDefinition definition = usable(ids.get(i), kind, notFound, field + "[" + i + "]", context,
                        diagnostics);
                if (definition != null) {
                    usable.add(definition);
                }
            }
        }
        return usable;
    }

    /** The definition if it exists with this kind, is APPROVED and still valid; otherwise a diagnostic. */
    private SemanticDefinition usable(UUID id, DefinitionKind kind, Code notFound, String field, Context context,
                                      List<ResolutionDiagnostic> diagnostics) {
        SemanticDefinition definition = context.catalog().definitions().get(id);
        if (definition == null || definition.kind() != kind || !definition.dataSourceId().equals(context.dataSourceId())
                || !definition.tenantId().equals(context.tenantId())) {
            diagnostics.add(ResolutionDiagnostic.of(notFound, field,
                    "no " + kind.name().toLowerCase(Locale.ROOT) + " with this id in this data source"));
            return null;
        }
        return usable(definition, field, context, diagnostics) ? definition : null;
    }

    private boolean usable(SemanticDefinition definition, String field, Context context,
                           List<ResolutionDiagnostic> diagnostics) {
        if (definition.status() == DefinitionStatus.DRAFT) {
            diagnostics.add(ResolutionDiagnostic.of(Code.DEFINITION_NOT_APPROVED, field,
                    "the definition is DRAFT and must be APPROVED"));
            return false;
        }
        if (definition.status() == DefinitionStatus.INACTIVE) {
            boolean invalidated = definition.inactiveReason() != null
                    && definition.inactiveReason().startsWith(SemanticDefinition.IMPORT_INVALIDATION_PREFIX);
            if (invalidated) {
                diagnostics.add(new ResolutionDiagnostic(Code.DEFINITION_INVALIDATED, field,
                        "the definition was invalidated by a metadata change and must be revised and approved again",
                        codes(validator.issues(definition.spec(), context.catalog()))));
            } else {
                diagnostics.add(ResolutionDiagnostic.of(Code.DEFINITION_NOT_APPROVED, field,
                        "the definition is INACTIVE and must be revised and approved again"));
            }
            return false;
        }
        List<DefinitionIssue> issues = validator.usageIssues(definition.spec(), context.catalog());
        if (issues.isEmpty()) {
            return true;
        }
        boolean onlyConcept = issues.stream().allMatch(i -> i.code() == DefinitionIssue.Code.CONCEPT_NOT_APPROVED
                || i.code() == DefinitionIssue.Code.CONCEPT_NOT_USABLE);
        diagnostics.add(new ResolutionDiagnostic(onlyConcept ? Code.CONCEPT_NOT_USABLE : Code.PHYSICAL_BINDING_INVALID,
                field, onlyConcept ? "the definition's concept is not APPROVED and valid"
                : "the definition's physical binding is missing or incompatible", codes(issues)));
        return false;
    }

    // --- filters ---

    private ResolvedFilter filter(FilterRequest filter, String field, Set<UUID> requestedMetrics, Context context,
                                  List<ResolutionDiagnostic> diagnostics, List<SemanticDefinition> filterDefinitions) {
        SemanticDefinition definition = context.catalog().definitions().get(filter.definitionId());
        String idField = field + ".definitionId";
        if (definition == null || definition.kind() == DefinitionKind.CONCEPT
                || !definition.dataSourceId().equals(context.dataSourceId())
                || !definition.tenantId().equals(context.tenantId())) {
            diagnostics.add(ResolutionDiagnostic.of(Code.FILTER_FIELD_NOT_FOUND, idField,
                    "no dimension or metric with this id in this data source"));
            return null;
        }
        if (!usable(definition, idField, context, diagnostics)) {
            return null;
        }
        FilterStage stage;
        TypeCategory type;
        UUID columnId;
        Set<FilterOperator> allowed;
        if (definition.spec() instanceof DimensionSpec dimension) {
            stage = FilterStage.PRE_AGGREGATION;
            columnId = dimension.columnId();
            type = category(context.catalog().objects().get(columnId), context);
            allowed = operatorsFor(type);
        } else {
            MetricSpec metric = (MetricSpec) definition.spec();
            if (!requestedMetrics.contains(definition.id())) {
                diagnostics.add(ResolutionDiagnostic.of(Code.FILTER_STAGE_UNDETERMINED, idField,
                        "a metric can only be filtered after aggregation when it is one of the requested metrics"));
                return null;
            }
            stage = FilterStage.POST_AGGREGATION;
            columnId = metric.columnId();
            type = TypeCategory.NUMERIC;
            allowed = EnumSet.of(FilterOperator.EQ, FilterOperator.NE, FilterOperator.GT, FilterOperator.GTE,
                    FilterOperator.LT, FilterOperator.LTE);
        }
        if (!allowed.contains(filter.operator())) {
            diagnostics.add(ResolutionDiagnostic.of(Code.OPERATOR_INCOMPATIBLE, field + ".operator",
                    "operator " + filter.operator() + " is not supported for " + type + " "
                            + (stage == FilterStage.POST_AGGREGATION ? "metric values" : "dimensions")));
            return null;
        }
        List<FilterValue> values = values(filter, type, field, diagnostics);
        if (values == null) {
            return null;
        }
        if (filterDefinitions.stream().noneMatch(d -> d.id().equals(definition.id()))) {
            filterDefinitions.add(definition);
        }
        return new ResolvedFilter(definition.id(), definition.kind(), stage, filter.operator(), type, columnId, values);
    }

    /** Equality on every known category; ordering only where order is not collation-dependent. */
    private static Set<FilterOperator> operatorsFor(TypeCategory type) {
        Set<FilterOperator> operators = EnumSet.of(FilterOperator.IS_NULL, FilterOperator.IS_NOT_NULL);
        if (EQUALITY.contains(type)) {
            operators.add(FilterOperator.EQ);
            operators.add(FilterOperator.NE);
            if (type != TypeCategory.BOOLEAN) {
                operators.add(FilterOperator.IN);
            }
        }
        if (ORDERED.contains(type)) {
            operators.addAll(EnumSet.of(FilterOperator.GT, FilterOperator.GTE, FilterOperator.LT, FilterOperator.LTE));
        }
        return operators;
    }

    private static List<FilterValue> values(FilterRequest filter, TypeCategory type, String field,
                                            List<ResolutionDiagnostic> diagnostics) {
        List<FilterLiteral> literals = filter.values();
        String valuesField = field + ".values";
        switch (filter.operator().arity()) {
            case NONE -> {
                if (!literals.isEmpty()) {
                    diagnostics.add(ResolutionDiagnostic.of(Code.FILTER_INVALID, valuesField,
                            filter.operator() + " takes no value"));
                    return null;
                }
                return List.of();
            }
            case ONE -> {
                if (literals.size() != 1) {
                    diagnostics.add(ResolutionDiagnostic.of(Code.FILTER_INVALID, valuesField,
                            filter.operator() + " takes exactly one value"));
                    return null;
                }
            }
            case MANY -> {
                if (literals.isEmpty() || literals.size() > MAX_IN_VALUES) {
                    diagnostics.add(ResolutionDiagnostic.of(Code.FILTER_INVALID, valuesField,
                            "IN takes between 1 and " + MAX_IN_VALUES + " values"));
                    return null;
                }
            }
        }
        List<FilterValue> typed = new ArrayList<>();
        Set<String> distinct = new HashSet<>();
        for (int i = 0; i < literals.size(); i++) {
            String valueField = field + ".values[" + i + "]";
            FilterValue value = typed(literals.get(i), type, valueField, diagnostics);
            if (value == null) {
                return null;
            }
            if (!distinct.add(value.canonical())) {
                diagnostics.add(ResolutionDiagnostic.of(Code.DUPLICATE_REFERENCE, valueField,
                        "the same value is repeated"));
                return null;
            }
            typed.add(value);
        }
        return typed;
    }

    /** Types one literal by the field's category; text is data and is only parsed as ISO-8601 or UUID. */
    private static FilterValue typed(FilterLiteral literal, TypeCategory type, String field,
                                     List<ResolutionDiagnostic> diagnostics) {
        if (literal instanceof FilterLiteral.Null) {
            diagnostics.add(ResolutionDiagnostic.of(Code.FILTER_INVALID, field,
                    "null is not a comparison value; use IS_NULL or IS_NOT_NULL"));
            return null;
        }
        try {
            FilterValue value = switch (type) {
                case NUMERIC -> literal instanceof FilterLiteral.Number n ? new FilterValue.NumberValue(n.value())
                        : null;
                case BOOLEAN -> literal instanceof FilterLiteral.Bool b ? new FilterValue.BooleanValue(b.value())
                        : null;
                case TEXT -> literal instanceof FilterLiteral.Text t && t.value().length() <= MAX_TEXT_LENGTH
                        && t.value().indexOf('\0') < 0 ? new FilterValue.TextValue(t.value()) : null;
                case DATE -> literal instanceof FilterLiteral.Text t
                        ? new FilterValue.DateValue(LocalDate.parse(t.value())) : null;
                case TIMESTAMP -> literal instanceof FilterLiteral.Text t
                        ? new FilterValue.TimestampValue(LocalDateTime.parse(t.value())) : null;
                case TIMESTAMP_TZ -> literal instanceof FilterLiteral.Text t
                        ? new FilterValue.InstantValue(OffsetDateTime.parse(t.value())
                        .withOffsetSameInstant(ZoneOffset.UTC)) : null;
                case TIME -> literal instanceof FilterLiteral.Text t
                        ? new FilterValue.TimeValue(LocalTime.parse(t.value())) : null;
                case UUID -> literal instanceof FilterLiteral.Text t && UUID_FORMAT.matcher(t.value()).matches()
                        ? new FilterValue.UuidValue(UUID.fromString(t.value())) : null;
                case OTHER -> null;
            };
            if (value == null) {
                diagnostics.add(ResolutionDiagnostic.of(Code.FILTER_INVALID, field, expected(type)));
            }
            return value;
        } catch (DateTimeParseException e) {
            diagnostics.add(ResolutionDiagnostic.of(Code.FILTER_INVALID, field, expected(type)));
            return null;
        }
    }

    private static String expected(TypeCategory type) {
        return switch (type) {
            case NUMERIC -> "must be a JSON number";
            case BOOLEAN -> "must be a JSON boolean";
            case TEXT -> "must be a JSON string of at most " + MAX_TEXT_LENGTH + " characters";
            case DATE -> "must be an ISO-8601 date (yyyy-MM-dd)";
            case TIMESTAMP -> "must be an ISO-8601 local date-time without offset";
            case TIMESTAMP_TZ -> "must be an ISO-8601 date-time with offset";
            case TIME -> "must be an ISO-8601 time";
            case UUID -> "must be a UUID";
            case OTHER -> "values cannot be validated for this column type";
        };
    }

    // --- result ---

    private SemanticResolution build(ResolutionRequest request, Context context, List<SemanticDefinition> metrics,
                                     List<SemanticDefinition> dimensions, List<SemanticDefinition> filterDefinitions,
                                     List<ResolvedFilter> filters, List<RelationshipContract> relationships) {
        Map<UUID, PhysicalObject> objects = context.catalog().objects();
        Map<UUID, SemanticDefinition> definitions = context.catalog().definitions();

        Map<UUID, SemanticDefinition> concepts = new LinkedHashMap<>();
        Set<UUID> physical = new LinkedHashSet<>();
        List<SemanticDefinition> all = new ArrayList<>(metrics);
        all.addAll(dimensions);
        filterDefinitions.stream().filter(d -> !all.contains(d)).forEach(all::add);
        for (SemanticDefinition definition : all) {
            UUID conceptId = switch (definition.spec()) {
                case MetricSpec m -> m.conceptId();
                case DimensionSpec d -> d.conceptId();
                case ConceptSpec ignored -> throw new IllegalStateException("concepts are not requested directly");
            };
            SemanticDefinition concept = definitions.get(conceptId);
            concepts.putIfAbsent(concept.id(), concept);
            physical.add(((ConceptSpec) concept.spec()).relationId());
            switch (definition.spec()) {
                case MetricSpec m when m.columnId() != null -> physical.add(m.columnId());
                case DimensionSpec d -> physical.add(d.columnId());
                default -> {
                }
            }
        }

        List<ResolvedMetric> resolvedMetrics = metrics.stream().map(d -> {
            MetricSpec spec = (MetricSpec) d.spec();
            return new ResolvedMetric(d.id(), d.key().value(), d.name(), d.version(), spec.conceptId(),
                    spec.aggregation(), spec.columnId());
        }).toList();
        List<ResolvedDimension> resolvedDimensions = dimensions.stream().map(d -> dimension(d, context)).toList();
        List<ResolvedDimension> filterDimensions = filterDefinitions.stream()
                .filter(d -> d.kind() == DefinitionKind.DIMENSION && !dimensions.contains(d))
                .map(d -> dimension(d, context)).toList();
        List<ResolvedDimension> allDimensions = new ArrayList<>(resolvedDimensions);
        allDimensions.addAll(filterDimensions);
        List<ResolvedConcept> resolvedConcepts = concepts.values().stream()
                .map(c -> new ResolvedConcept(c.id(), c.key().value(), c.name(), c.version(),
                        ((ConceptSpec) c.spec()).relationId()))
                .toList();
        List<PhysicalDependency> dependencies = physical.stream().map(objects::get)
                .sorted(Comparator.comparing(o -> o.naturalKey().value()))
                .map(o -> new PhysicalDependency(o.id(), o.type(), o.naturalKey().value(), o.fingerprint(),
                        o.lastSeenSnapshotId(), o.attributes().values().get("dataType") instanceof String t ? t : null))
                .toList();

        String fingerprint = fingerprint(context, resolvedMetrics, allDimensions, resolvedConcepts, filters,
                dependencies, relationships);
        return new SemanticResolution(context.tenantId(), context.dataSourceId(), context.metadataSnapshotId(),
                resolvedMetrics, resolvedDimensions, filterDimensions, resolvedConcepts, filters, dependencies,
                relationships, fingerprint);
    }

    private static ResolvedDimension dimension(SemanticDefinition d, Context context) {
        DimensionSpec spec = (DimensionSpec) d.spec();
        return new ResolvedDimension(d.id(), d.key().value(), d.name(), d.version(), spec.conceptId(),
                spec.columnId(), spec.type(), category(context.catalog().objects().get(spec.columnId()), context),
                spec.granularities().stream().sorted().toList(),
                spec.timeZone() == null ? null : spec.timeZone().getId());
    }

    /** Canonical, order-preserving digest of the request as resolved and of every revision observed. */
    private static String fingerprint(Context context, List<ResolvedMetric> metrics, List<ResolvedDimension> dimensions,
                                      List<ResolvedConcept> concepts, List<ResolvedFilter> filters,
                                      List<PhysicalDependency> dependencies,
                                      List<RelationshipContract> relationships) {
        StringBuilder canonical = new StringBuilder("s4.1|").append(context.dataSourceId().value())
                .append("|snapshot=").append(context.metadataSnapshotId());
        metrics.forEach(m -> canonical.append("|M:").append(m.id()).append(':').append(m.version()));
        dimensions.forEach(d -> canonical.append("|D:").append(d.id()).append(':').append(d.version()));
        concepts.forEach(c -> canonical.append("|C:").append(c.id()).append(':').append(c.version()));
        filters.forEach(f -> canonical.append("|F:").append(f.definitionId()).append(':').append(f.operator())
                .append(':').append(f.values().stream().map(v -> quote(v.canonical()))
                        .collect(Collectors.joining(","))));
        dependencies.forEach(o -> canonical.append("|O:").append(o.objectId()).append(':').append(o.fingerprint())
                .append(':').append(o.lastSeenSnapshotId()));
        relationships.forEach(r -> canonical.append("|R:").append(r.relationshipId()).append(':')
                .append(r.version()));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    // --- helpers ---

    private static Set<UUID> unique(List<UUID> ids, String field, List<ResolutionDiagnostic> diagnostics) {
        Set<UUID> seen = new LinkedHashSet<>();
        for (int i = 0; i < ids.size(); i++) {
            if (!seen.add(ids.get(i))) {
                diagnostics.add(ResolutionDiagnostic.of(Code.DUPLICATE_REFERENCE, field + "[" + i + "]",
                        "the same id is repeated"));
            }
        }
        return seen;
    }

    private static TypeCategory category(PhysicalObject column, Context context) {
        Object dataType = column == null ? null : column.attributes().values().get("dataType");
        return TypeCategory.of(context.catalog().engine(), dataType instanceof String type ? type : null);
    }

    private static List<String> codes(List<DefinitionIssue> issues) {
        return issues.stream().map(i -> i.code().name()).distinct().toList();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
