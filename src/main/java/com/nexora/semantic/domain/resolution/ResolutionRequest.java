package com.nexora.semantic.domain.resolution;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Structured request of S4.1: persistent ids of APPROVED definitions and relationships of one data source, plus typed
 * filters. There are no table names, column names or expressions. The tenant comes from the caller's context, never
 * from the request.
 *
 * @param metricIds               one or more METRIC ids, in the order the result must keep
 * @param dimensionIds            zero or more DIMENSION ids
 * @param filters                 filters on dimensions (before aggregation) or requested metrics (after aggregation)
 * @param preferredRelationshipIds relationships S4.2 should prefer; validated, never selected as a route here
 */
public record ResolutionRequest(List<UUID> metricIds, List<UUID> dimensionIds, List<FilterRequest> filters,
                                List<UUID> preferredRelationshipIds) {

    /** @param values no value for IS_NULL / IS_NOT_NULL, exactly one for comparisons, one or more for IN */
    public record FilterRequest(UUID definitionId, FilterOperator operator, List<FilterLiteral> values) {

        public FilterRequest {
            Objects.requireNonNull(definitionId, "definitionId");
            Objects.requireNonNull(operator, "operator");
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    public ResolutionRequest {
        metricIds = metricIds == null ? List.of() : List.copyOf(metricIds);
        dimensionIds = dimensionIds == null ? List.of() : List.copyOf(dimensionIds);
        filters = filters == null ? List.of() : List.copyOf(filters);
        preferredRelationshipIds = preferredRelationshipIds == null ? List.of() : List.copyOf(preferredRelationshipIds);
    }
}
