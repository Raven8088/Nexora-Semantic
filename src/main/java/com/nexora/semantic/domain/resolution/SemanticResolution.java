package com.nexora.semantic.domain.resolution;

import com.nexora.semantic.domain.catalog.Aggregation;
import com.nexora.semantic.domain.catalog.DefinitionKind;
import com.nexora.semantic.domain.catalog.DimensionType;
import com.nexora.semantic.domain.catalog.TimeGranularity;
import com.nexora.semantic.domain.catalog.TypeCategory;
import com.nexora.semantic.domain.relationship.RelationshipContract;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DataSourceId;
import com.nexora.semantic.domain.source.PhysicalObjectType;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable result of S4.1: the APPROVED, physically valid definitions a request refers to, their physical bindings,
 * the typed filters and the revisions observed, all read from one consistent snapshot of the catalog.
 * <p>
 * It is <b>not</b> a plan: it contains no SQL, no JOIN, no route and no claim that the objects can be joined, nor
 * that aggregating across them is safe (fan-out). Preferred relationships are validated and echoed, never selected.
 * S4.2 owns path finding, cardinality and the logical plan.
 *
 * @param filterDimensions   dimensions referenced only by filters (not grouped), kept for their revisions
 * @param metadataSnapshotId latest metadata import of the data source when the request was resolved (null if none)
 * @param fingerprint        SHA-256 of every revision observed (definitions, relationships, physical objects, latest
 *                           snapshot) and of the normalized request: equal fingerprints mean nothing relevant changed
 */
public record SemanticResolution(
        TenantId tenantId,
        DataSourceId dataSourceId,
        UUID metadataSnapshotId,
        List<ResolvedMetric> metrics,
        List<ResolvedDimension> dimensions,
        List<ResolvedDimension> filterDimensions,
        List<ResolvedConcept> concepts,
        List<ResolvedFilter> filters,
        List<PhysicalDependency> physicalDependencies,
        List<RelationshipContract> preferredRelationships,
        String fingerprint) {

    /** A concept used by a requested metric, dimension or filter, with the table or view it is bound to. */
    public record ResolvedConcept(UUID id, String key, String name, long version, UUID relationObjectId) {
    }

    /** @param columnObjectId null for COUNT of rows */
    public record ResolvedMetric(UUID id, String key, String name, long version, UUID conceptId,
                                 Aggregation aggregation, UUID columnObjectId) {
    }

    /** @param valueType category of the column type; granularities and time zone apply only to TEMPORAL */
    public record ResolvedDimension(UUID id, String key, String name, long version, UUID conceptId,
                                    UUID columnObjectId, DimensionType type, TypeCategory valueType,
                                    List<TimeGranularity> granularities, String timeZone) {
        public ResolvedDimension {
            granularities = List.copyOf(granularities);
        }
    }

    /** Where a filter applies relative to aggregation. */
    public enum FilterStage {
        /** On a dimension's column, row by row, before aggregating. */
        PRE_AGGREGATION,
        /** On a requested metric's aggregated value, after aggregating at the requested grain. */
        POST_AGGREGATION
    }

    /**
     * @param definitionKind DIMENSION (pre-aggregation) or METRIC (post-aggregation)
     * @param columnObjectId column of the dimension, or of the metric (null for COUNT of rows)
     * @param values         typed values in request order (empty for IS_NULL / IS_NOT_NULL)
     */
    public record ResolvedFilter(UUID definitionId, DefinitionKind definitionKind, FilterStage stage,
                                 FilterOperator operator, TypeCategory valueType, UUID columnObjectId,
                                 List<FilterValue> values) {
        public ResolvedFilter {
            values = List.copyOf(values);
        }
    }

    /**
     * A physical object the resolution depends on, with the revision observed.
     *
     * @param fingerprint        hash of the object's observed attributes and comment (S2)
     * @param lastSeenSnapshotId last import that observed it
     * @param dataType           declared data type for columns, null otherwise
     */
    public record PhysicalDependency(UUID objectId, PhysicalObjectType type, String naturalKey, String fingerprint,
                                     UUID lastSeenSnapshotId, String dataType) {
    }

    public SemanticResolution {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        metrics = List.copyOf(metrics);
        dimensions = List.copyOf(dimensions);
        filterDimensions = List.copyOf(filterDimensions);
        concepts = List.copyOf(concepts);
        filters = List.copyOf(filters);
        physicalDependencies = List.copyOf(physicalDependencies);
        preferredRelationships = List.copyOf(preferredRelationships);
        Objects.requireNonNull(fingerprint, "fingerprint");
        if (metrics.isEmpty()) {
            throw new IllegalArgumentException("a resolution has at least one metric");
        }
    }
}
