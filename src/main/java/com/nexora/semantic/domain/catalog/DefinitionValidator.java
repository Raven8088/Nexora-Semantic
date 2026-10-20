package com.nexora.semantic.domain.catalog;

import com.nexora.semantic.domain.catalog.DefinitionIssue.Code;
import com.nexora.semantic.domain.catalog.DefinitionSpec.ConceptSpec;
import com.nexora.semantic.domain.catalog.DefinitionSpec.DimensionSpec;
import com.nexora.semantic.domain.catalog.DefinitionSpec.MetricSpec;
import com.nexora.semantic.domain.source.EngineCode;
import com.nexora.semantic.domain.source.PhysicalObject;
import com.nexora.semantic.domain.source.PhysicalObjectStatus;
import com.nexora.semantic.domain.source.PhysicalObjectType;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Validates a definition against the current physical metadata of its data source (pure domain logic).
 * <ul>
 *   <li>{@link #issues}: references exist in the same tenant and data source (the lookup maps are scoped), have the
 *       right type, are not MISSING, columns belong to the concept's relation, and aggregations/dimensions are
 *       compatible with the column's data type. Required to save, approve and use a definition.</li>
 *   <li>{@link #usageIssues}: additionally, the referenced concept must be APPROVED and itself valid. Required to
 *       approve and use metrics and dimensions.</li>
 * </ul>
 * Messages describe rules and never echo names or values.
 */
public final class DefinitionValidator {

    /**
     * @param objects     physical objects of the data source, by id (only the referenced ones are needed)
     * @param definitions definitions of the data source, by id (only referenced concepts are needed)
     */
    public record Context(EngineCode engine, Map<UUID, PhysicalObject> objects,
                          Map<UUID, SemanticDefinition> definitions) {
    }

    /** Decision D14: default zone for bucketing {@code timestamptz} columns. */
    public static final ZoneId DEFAULT_TIME_ZONE = ZoneId.of("UTC");

    private static final EnumSet<PhysicalObjectType> RELATIONS =
            EnumSet.of(PhysicalObjectType.TABLE, PhysicalObjectType.VIEW, PhysicalObjectType.MATERIALIZED_VIEW);
    private static final EnumSet<TypeCategory> CATEGORICAL = EnumSet.complementOf(
            EnumSet.of(TypeCategory.OTHER));

    public List<DefinitionIssue> issues(DefinitionSpec spec, Context context) {
        List<DefinitionIssue> issues = new ArrayList<>();
        switch (spec) {
            case ConceptSpec concept -> relation(concept.relationId(), "concept.relationId", context, issues);
            case MetricSpec metric -> {
                SemanticDefinition concept = concept(metric.conceptId(), "metric.conceptId", context, issues);
                if (metric.columnId() == null) {
                    if (metric.aggregation().requiresNumericColumn()) {
                        issues.add(new DefinitionIssue(Code.INCOMPATIBLE_AGGREGATION, "metric.columnId",
                                "is required for " + metric.aggregation()));
                    }
                } else {
                    PhysicalObject column = column(metric.columnId(), "metric.columnId", concept, context, issues);
                    if (column != null && metric.aggregation().requiresNumericColumn()
                            && category(column, context) != TypeCategory.NUMERIC) {
                        issues.add(new DefinitionIssue(Code.INCOMPATIBLE_AGGREGATION, "metric.aggregation",
                                metric.aggregation() + " requires a numeric column"));
                    }
                }
            }
            case DimensionSpec dimension -> dimension(dimension, context, issues);
        }
        return issues;
    }

    /** {@link #issues} plus the state of the referenced concept (metrics and dimensions). */
    public List<DefinitionIssue> usageIssues(DefinitionSpec spec, Context context) {
        List<DefinitionIssue> issues = issues(spec, context);
        UUID conceptId = switch (spec) {
            case ConceptSpec ignored -> null;
            case MetricSpec metric -> metric.conceptId();
            case DimensionSpec dimension -> dimension.conceptId();
        };
        if (conceptId != null) {
            SemanticDefinition concept = context.definitions().get(conceptId);
            if (concept != null && concept.kind() == DefinitionKind.CONCEPT) {
                String field = spec.kind().name().toLowerCase(Locale.ROOT) + ".conceptId";
                if (concept.status() != DefinitionStatus.APPROVED) {
                    issues.add(new DefinitionIssue(Code.CONCEPT_NOT_APPROVED, field, "concept must be APPROVED"));
                } else if (!issues(concept.spec(), context).isEmpty()) {
                    issues.add(new DefinitionIssue(Code.CONCEPT_NOT_USABLE, field, "concept has validation issues"));
                }
            }
        }
        return issues;
    }

    /**
     * Fills defaults that depend on the column type (decision D14): a temporal dimension allows every granularity
     * unless restricted, and buckets {@code timestamptz} columns in UTC unless another IANA zone is given.
     */
    public DefinitionSpec normalize(DefinitionSpec spec, Context context) {
        if (!(spec instanceof DimensionSpec dimension) || dimension.type() != DimensionType.TEMPORAL) {
            return spec;
        }
        PhysicalObject column = context.objects().get(dimension.columnId());
        boolean withTimeZone = column != null && category(column, context) == TypeCategory.TIMESTAMP_TZ;
        return new DimensionSpec(dimension.conceptId(), dimension.columnId(), dimension.type(),
                dimension.granularities().isEmpty() ? EnumSet.allOf(TimeGranularity.class) : dimension.granularities(),
                dimension.timeZone() == null && withTimeZone ? DEFAULT_TIME_ZONE : dimension.timeZone());
    }

    private void dimension(DimensionSpec dimension, Context context, List<DefinitionIssue> issues) {
        SemanticDefinition concept = concept(dimension.conceptId(), "dimension.conceptId", context, issues);
        PhysicalObject column = column(dimension.columnId(), "dimension.columnId", concept, context, issues);
        TypeCategory category = column == null ? null : category(column, context);
        if (dimension.type() == DimensionType.TEMPORAL) {
            if (category != null && !category.isTemporal()) {
                issues.add(new DefinitionIssue(Code.INCOMPATIBLE_DIMENSION, "dimension.type",
                        "TEMPORAL requires a date or timestamp column"));
            }
            if (dimension.timeZone() != null && category != null && category != TypeCategory.TIMESTAMP_TZ) {
                issues.add(new DefinitionIssue(Code.NOT_APPLICABLE, "dimension.timeZone",
                        "applies only to timestamp with time zone columns"));
            }
        } else {
            if (category != null && !CATEGORICAL.contains(category)) {
                issues.add(new DefinitionIssue(Code.INCOMPATIBLE_DIMENSION, "dimension.type",
                        "column type cannot be used as a categorical dimension"));
            }
            if (!dimension.granularities().isEmpty()) {
                issues.add(new DefinitionIssue(Code.NOT_APPLICABLE, "dimension.granularities",
                        "apply only to TEMPORAL dimensions"));
            }
            if (dimension.timeZone() != null) {
                issues.add(new DefinitionIssue(Code.NOT_APPLICABLE, "dimension.timeZone",
                        "applies only to TEMPORAL dimensions"));
            }
        }
    }

    private static PhysicalObject relation(UUID id, String field, Context context, List<DefinitionIssue> issues) {
        PhysicalObject object = context.objects().get(id);
        if (object == null) {
            issues.add(new DefinitionIssue(Code.REFERENCE_NOT_FOUND, field, "no such object in this data source"));
            return null;
        }
        if (!RELATIONS.contains(object.type())) {
            issues.add(new DefinitionIssue(Code.REFERENCE_WRONG_TYPE, field, "must reference a table or view"));
            return null;
        }
        missing(object, field, issues);
        return object;
    }

    private static PhysicalObject column(UUID id, String field, SemanticDefinition concept, Context context,
                                         List<DefinitionIssue> issues) {
        PhysicalObject object = context.objects().get(id);
        if (object == null) {
            issues.add(new DefinitionIssue(Code.REFERENCE_NOT_FOUND, field, "no such object in this data source"));
            return null;
        }
        if (object.type() != PhysicalObjectType.COLUMN) {
            issues.add(new DefinitionIssue(Code.REFERENCE_WRONG_TYPE, field, "must reference a column"));
            return null;
        }
        missing(object, field, issues);
        if (concept != null && concept.spec() instanceof ConceptSpec conceptSpec
                && !conceptSpec.relationId().equals(object.parentId())) {
            issues.add(new DefinitionIssue(Code.COLUMN_NOT_IN_CONCEPT, field,
                    "must be a column of the concept's table or view"));
        }
        return object;
    }

    private static SemanticDefinition concept(UUID id, String field, Context context, List<DefinitionIssue> issues) {
        SemanticDefinition concept = context.definitions().get(id);
        if (concept == null) {
            issues.add(new DefinitionIssue(Code.REFERENCE_NOT_FOUND, field, "no such definition in this data source"));
            return null;
        }
        if (concept.kind() != DefinitionKind.CONCEPT) {
            issues.add(new DefinitionIssue(Code.REFERENCE_WRONG_TYPE, field, "must reference a CONCEPT"));
            return null;
        }
        return concept;
    }

    private static void missing(PhysicalObject object, String field, List<DefinitionIssue> issues) {
        if (object.status() == PhysicalObjectStatus.MISSING) {
            issues.add(new DefinitionIssue(Code.REFERENCE_MISSING, field,
                    "references an object missing from the latest metadata import"));
        }
    }

    private static TypeCategory category(PhysicalObject column, Context context) {
        Object dataType = column.attributes().values().get("dataType");
        return TypeCategory.of(context.engine(), dataType instanceof String type ? type : null);
    }
}
