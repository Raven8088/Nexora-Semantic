package com.nexora.semantic.domain.resolution;

import java.util.List;
import java.util.Objects;

/**
 * Why a request cannot be resolved. {@code field} is a request path (e.g. {@code metrics[1]}), never a value; the
 * message describes the rule and never echoes names, values or data of other tenants or data sources.
 *
 * @param causes underlying catalog issue codes (e.g. {@code REFERENCE_MISSING}), when there are any
 */
public record ResolutionDiagnostic(Code code, String field, String message, List<String> causes) {

    public enum Code {
        /** The request selects no metric. */
        NO_METRICS,
        /** The same id appears twice (metrics, dimensions, preferred relationships) or a filter is repeated. */
        DUPLICATE_REFERENCE,
        /** No METRIC with this id in this tenant and data source (also when the id is of another kind). */
        METRIC_NOT_FOUND,
        /** No DIMENSION with this id in this tenant and data source. */
        DIMENSION_NOT_FOUND,
        /** DRAFT, or INACTIVE after an explicit deactivation. */
        DEFINITION_NOT_APPROVED,
        /** INACTIVE because a metadata import invalidated it (D21); needs revision and a new approval. */
        DEFINITION_INVALIDATED,
        /** APPROVED, but its concept is no longer APPROVED or valid. */
        CONCEPT_NOT_USABLE,
        /** APPROVED, but a referenced object or column is missing, of the wrong type or incompatible. */
        PHYSICAL_BINDING_INVALID,
        /** The filtered id is not a DIMENSION or METRIC of this data source. */
        FILTER_FIELD_NOT_FOUND,
        /** Missing, extra, null, malformed or wrongly typed values. */
        FILTER_INVALID,
        /** The operator cannot be validated for the field's type (e.g. GT on text). */
        OPERATOR_INCOMPATIBLE,
        /** The filter cannot be placed before or after aggregation (e.g. on a metric that is not requested). */
        FILTER_STAGE_UNDETERMINED,
        /** No relationship with this id in this tenant and data source. */
        PREFERRED_RELATIONSHIP_NOT_FOUND,
        /** The relationship exists but is not APPROVED and physically valid. */
        PREFERRED_RELATIONSHIP_NOT_USABLE
    }

    public ResolutionDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(message, "message");
        causes = causes == null ? List.of() : List.copyOf(causes);
    }

    static ResolutionDiagnostic of(Code code, String field, String message) {
        return new ResolutionDiagnostic(code, field, message, List.of());
    }
}
