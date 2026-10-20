package com.nexora.semantic.domain.catalog;

import java.time.ZoneId;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Typed content of a definition. References are persistent ids: physical objects imported in S2
 * ({@code physical_object.id}) or other definitions. There is no free-form SQL or expression language.
 */
public sealed interface DefinitionSpec {

    DefinitionKind kind();

    /** @param relationId physical TABLE, VIEW or MATERIALIZED_VIEW of the same data source */
    record ConceptSpec(UUID relationId) implements DefinitionSpec {
        public ConceptSpec {
            Objects.requireNonNull(relationId, "relationId");
        }

        @Override
        public DefinitionKind kind() {
            return DefinitionKind.CONCEPT;
        }
    }

    /**
     * @param conceptId concept the metric aggregates
     * @param columnId  column of the concept's relation; null only for COUNT (counts rows)
     */
    record MetricSpec(UUID conceptId, Aggregation aggregation, UUID columnId) implements DefinitionSpec {
        public MetricSpec {
            Objects.requireNonNull(conceptId, "conceptId");
            Objects.requireNonNull(aggregation, "aggregation");
        }

        @Override
        public DefinitionKind kind() {
            return DefinitionKind.METRIC;
        }
    }

    /**
     * @param granularities allowed time buckets (temporal only; all of them when not specified)
     * @param timeZone      IANA zone used to bucket {@code timestamptz} values (decision D14); null otherwise
     */
    record DimensionSpec(UUID conceptId, UUID columnId, DimensionType type, Set<TimeGranularity> granularities,
                         ZoneId timeZone) implements DefinitionSpec {
        public DimensionSpec {
            Objects.requireNonNull(conceptId, "conceptId");
            Objects.requireNonNull(columnId, "columnId");
            Objects.requireNonNull(type, "type");
            granularities = granularities == null || granularities.isEmpty()
                    ? Set.of() : Set.copyOf(EnumSet.copyOf(granularities));
        }

        @Override
        public DefinitionKind kind() {
            return DefinitionKind.DIMENSION;
        }
    }
}
