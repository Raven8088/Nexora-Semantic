package com.nexora.semantic.domain.planning;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Why a semantically resolved request cannot be planned safely (S4.2). Only ids of the caller's tenant and data
 * source appear; messages describe rules and never echo values.
 *
 * @param relationshipIds relationships involved (e.g. the unsafe join)
 * @param alternatives    for AMBIGUOUS_PATH: each authorized route as its ordered relationship ids
 */
public record PlanDiagnostic(Code code, String field, String message, List<UUID> relationshipIds,
                             List<List<UUID>> alternatives) {

    public enum Code {
        /** No route of usable APPROVED relationships connects the base object with a required object. */
        NO_PATH,
        /** Several authorized routes exist and the preferred relationships do not single one out. */
        AMBIGUOUS_PATH,
        /** Only APPROVED relationships that are not usable (physically invalid) would connect the objects. */
        RELATIONSHIP_NOT_USABLE,
        /** The route needs a JOIN type the relationship does not authorize in that direction. */
        JOIN_NOT_AUTHORIZED,
        /** The joined side is declared or known to be "many": rows being aggregated would be multiplied. */
        FANOUT_RISK,
        /** The joined side is declared "one" but no primary key proves it: the declaration is not proof. */
        UNIQUENESS_NOT_PROVEN,
        /** Metrics over different base objects (grains) cannot be combined; no drill-across strategy exists. */
        INCOMPATIBLE_GRAIN,
        /** A row filter on the optional side of a LEFT JOIN would change the meaning of the aggregation. */
        UNSAFE_FILTER,
        /** Reserved: the catalog has no unit or currency metadata, so it is never emitted in S4.2. */
        INCOMPATIBLE_UNITS,
        /** Objects, joins, route depth or candidate routes exceed the planner limits. */
        COMPLEXITY_LIMIT_EXCEEDED,
        /** A requested TEMPORAL dimension has no time granularity. */
        GRANULARITY_REQUIRED,
        /** The granularity is not allowed by the dimension, is repeated, or refers to a non-temporal or unrequested dimension. */
        GRANULARITY_INVALID
    }

    public PlanDiagnostic {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(message, "message");
        relationshipIds = relationshipIds == null ? List.of() : List.copyOf(relationshipIds);
        alternatives = alternatives == null ? List.of() : alternatives.stream().map(List::copyOf).toList();
    }

    static PlanDiagnostic of(Code code, String field, String message) {
        return new PlanDiagnostic(code, field, message, List.of(), List.of());
    }
}
