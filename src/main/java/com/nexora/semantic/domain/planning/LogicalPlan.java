package com.nexora.semantic.domain.planning;

import com.nexora.semantic.domain.catalog.Aggregation;
import com.nexora.semantic.domain.catalog.DefinitionKind;
import com.nexora.semantic.domain.catalog.DimensionType;
import com.nexora.semantic.domain.catalog.TimeGranularity;
import com.nexora.semantic.domain.catalog.TypeCategory;
import com.nexora.semantic.domain.relationship.CardinalityAssurance;
import com.nexora.semantic.domain.relationship.DeclaredCardinality;
import com.nexora.semantic.domain.relationship.ForeignKeyEvidence;
import com.nexora.semantic.domain.relationship.JoinType;
import com.nexora.semantic.domain.relationship.RelationshipOrigin;
import com.nexora.semantic.domain.relationship.RowMultiplicationRisk;
import com.nexora.semantic.domain.relationship.UniquenessEvidence;
import com.nexora.semantic.domain.resolution.FilterOperator;
import com.nexora.semantic.domain.source.PhysicalObjectType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Typed, immutable, self-contained logical plan (S4.2, format {@value #FORMAT_VERSION}). It describes <em>what</em>
 * to compute with explicit physical references, and carries copies of every definition, relationship and physical
 * object it relies on, so that it keeps its meaning after the catalog changes. It contains no SQL and no expression:
 * a future Reports engine translates it without reinterpreting the semantic catalog.
 * <p>
 * Logical order ({@link #operations()}): scan the base object, apply the joins in order, filter rows, group by the
 * output grain and aggregate, filter the aggregated groups. Every join reaches a side that is unique by primary key
 * in the navigation direction, so no aggregated row is ever multiplied ({@link #safety()}).
 *
 * @param resolutionFingerprint fingerprint of the S4.1 resolution this plan was built from
 * @param metadataSnapshotId    latest metadata import when planned (null if none)
 * @param fingerprint           SHA-256 of the whole plan content (everything except this field)
 */
public record LogicalPlan(
        String formatVersion,
        UUID tenantId,
        UUID dataSourceId,
        UUID metadataSnapshotId,
        String resolutionFingerprint,
        RequestSnapshot request,
        BaseObject base,
        InputGrain inputGrain,
        List<GroupingKey> outputGrain,
        List<PlanJoin> joins,
        List<PlanFilter> preAggregationFilters,
        List<PlanAggregation> aggregations,
        List<PlanFilter> postAggregationFilters,
        List<PlanOperation> operations,
        AggregationSafety safety,
        List<DefinitionSnapshot> definitions,
        List<RelationshipSnapshot> relationships,
        List<ObjectSnapshot> physicalObjects,
        String fingerprint) {

    public static final String FORMAT_VERSION = "nexora-logical-plan/1";

    /** The normalized request (filter values in canonical form, typed by the resolution). */
    public record RequestSnapshot(List<UUID> metrics, List<UUID> dimensions, List<FilterSnapshot> filters,
                                  List<UUID> preferredRelationships,
                                  List<PlanRequest.GranularityChoice> timeGranularities) {
        public RequestSnapshot {
            metrics = List.copyOf(metrics);
            dimensions = List.copyOf(dimensions);
            filters = List.copyOf(filters);
            preferredRelationships = List.copyOf(preferredRelationships);
            timeGranularities = List.copyOf(timeGranularities);
        }
    }

    public record FilterSnapshot(UUID definitionId, FilterOperator operator, List<String> values) {
        public FilterSnapshot {
            values = List.copyOf(values);
        }
    }

    /** The table or view whose rows the metrics aggregate. */
    public record BaseObject(UUID objectId, String naturalKey) {
    }

    /**
     * One input row per row of the base object. {@code rowIdentity} says whether a primary key identifies those
     * rows; aggregating a single object is well defined either way.
     */
    public record InputGrain(UUID objectId, List<String> primaryKeyColumns, UniquenessEvidence rowIdentity) {
        public InputGrain {
            primaryKeyColumns = List.copyOf(primaryKeyColumns);
        }
    }

    /** One grouping key of the output grain (one output row per distinct combination). */
    public record GroupingKey(UUID dimensionId, String key, UUID objectId, UUID columnObjectId, DimensionType type,
                              TypeCategory valueType, TimeGranularity granularity, String timeZone) {
    }

    /** Whether navigation follows the relationship from source to target or the reverse. */
    public enum Navigation { FORWARD, REVERSE }

    /** What the join does to the rows being aggregated (it never multiplies them). */
    public enum RowPreservation {
        /** LEFT JOIN: every row is kept (unmatched rows get no values on the joined side). */
        PRESERVED,
        /** INNER JOIN backed by a present foreign key over NOT NULL columns: every row has exactly one match. */
        PRESERVED_BY_FOREIGN_KEY,
        /** INNER JOIN: rows without a match are excluded, as the relationship authorizes. */
        UNMATCHED_ROWS_EXCLUDED
    }

    /** {@code fromColumnId = toColumnId}, oriented in the navigation direction. */
    public record JoinCondition(UUID fromColumnId, UUID toColumnId) {
    }

    /**
     * @param joinedSideUniqueness always PRIMARY_KEY in a persisted plan (the evidence used)
     * @param multiplicationRisk   always NONE_BY_PRIMARY_KEY in a persisted plan
     * @param optional             reached through a LEFT JOIN (directly or through an earlier one)
     */
    public record PlanJoin(int order, UUID relationshipId, long relationshipVersion, UUID fromObjectId,
                           UUID toObjectId, Navigation navigation, JoinType joinType, List<JoinCondition> conditions,
                           UniquenessEvidence joinedSideUniqueness, RowMultiplicationRisk multiplicationRisk,
                           RowPreservation rowPreservation, boolean optional) {
        public PlanJoin {
            conditions = List.copyOf(conditions);
        }
    }

    /**
     * @param objectId row filters: the object whose column is filtered; null for filters on aggregated values
     * @param values   canonical values (ISO-8601 for temporal values), typed by {@code valueType}
     */
    public record PlanFilter(int order, UUID definitionId, DefinitionKind definitionKind, FilterOperator operator,
                             TypeCategory valueType, UUID objectId, UUID columnObjectId, List<String> values) {
        public PlanFilter {
            values = List.copyOf(values);
        }
    }

    /** @param columnObjectId null for COUNT of rows */
    public record PlanAggregation(int order, UUID metricId, String key, Aggregation aggregation,
                                  UUID columnObjectId) {
    }

    public enum OperationKind { SCAN, JOIN, FILTER_ROWS, GROUP_AND_AGGREGATE, FILTER_GROUPS }

    /** @param references orders of the joins or filters the operation applies (empty for SCAN and aggregation) */
    public record PlanOperation(int order, OperationKind kind, List<Integer> references) {
        public PlanOperation {
            references = List.copyOf(references);
        }
    }

    /** The strategy that makes the aggregation correct. */
    public enum AggregationSafety {
        /** No join: metrics aggregate rows of the base object only. */
        SINGLE_OBJECT,
        /** Every join reaches a side unique by primary key: base rows are never multiplied. */
        JOINS_TO_PRIMARY_KEY_ONLY
    }

    /** Copy of a definition as used (metric, dimension, filtered dimension or concept). */
    public record DefinitionSnapshot(UUID id, DefinitionKind kind, String key, String name, long version,
                                     UUID conceptId, UUID relationObjectId, Aggregation aggregation,
                                     UUID columnObjectId, DimensionType dimensionType,
                                     List<TimeGranularity> granularities, String timeZone) {
        public DefinitionSnapshot {
            granularities = granularities == null ? List.of() : List.copyOf(granularities);
        }
    }

    public record PairSnapshot(UUID sourceColumnId, String sourceNaturalKey, UUID targetColumnId,
                               String targetNaturalKey) {
    }

    /** Copy of a selected relationship with the evidence considered. */
    public record RelationshipSnapshot(UUID id, long version, String name, RelationshipOrigin origin,
                                       UUID sourceObjectId, UUID targetObjectId, List<PairSnapshot> pairs,
                                       DeclaredCardinality declaredCardinality, List<JoinType> allowedJoinTypes,
                                       UUID foreignKeyId, UniquenessEvidence sourceUniqueness,
                                       UniquenessEvidence targetUniqueness, ForeignKeyEvidence foreignKey,
                                       CardinalityAssurance cardinalityAssurance,
                                       RowMultiplicationRisk sourceToTargetRisk,
                                       RowMultiplicationRisk targetToSourceRisk) {
        public RelationshipSnapshot {
            pairs = List.copyOf(pairs);
            allowedJoinTypes = List.copyOf(allowedJoinTypes);
        }
    }

    /** Copy of a physical object as observed (revision: fingerprint and last import that saw it). */
    public record ObjectSnapshot(UUID objectId, PhysicalObjectType type, String naturalKey, String dataType,
                                 Boolean nullable, String fingerprint, UUID lastSeenSnapshotId) {
    }

    public LogicalPlan {
        Objects.requireNonNull(formatVersion, "formatVersion");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(inputGrain, "inputGrain");
        outputGrain = List.copyOf(outputGrain);
        joins = List.copyOf(joins);
        preAggregationFilters = List.copyOf(preAggregationFilters);
        aggregations = List.copyOf(aggregations);
        postAggregationFilters = List.copyOf(postAggregationFilters);
        operations = List.copyOf(operations);
        Objects.requireNonNull(safety, "safety");
        definitions = List.copyOf(definitions);
        relationships = List.copyOf(relationships);
        physicalObjects = List.copyOf(physicalObjects);
        if (aggregations.isEmpty()) {
            throw new IllegalArgumentException("a plan aggregates at least one metric");
        }
        if (joins.stream().anyMatch(j -> j.multiplicationRisk() != RowMultiplicationRisk.NONE_BY_PRIMARY_KEY)) {
            throw new IllegalArgumentException("a plan never joins a side that may multiply rows");
        }
    }

    /** What the plan depends on, with the revisions observed: the basis of the validity check. */
    public List<PlanDependency> dependencies() {
        Map<String, PlanDependency> dependencies = new LinkedHashMap<>();
        definitions.forEach(d -> dependencies.putIfAbsent("D" + d.id(),
                PlanDependency.definition(d.id(), d.version())));
        for (PlanJoin join : joins) {
            dependencies.putIfAbsent("R" + join.relationshipId(),
                    PlanDependency.relationship(join.relationshipId(), join.relationshipVersion(), join.navigation()));
        }
        physicalObjects.forEach(o -> dependencies.putIfAbsent("O" + o.objectId(),
                PlanDependency.object(o.objectId(), o.fingerprint(), o.dataType())));
        return new ArrayList<>(dependencies.values());
    }
}
