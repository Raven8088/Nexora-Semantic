package com.nexora.semantic.domain.relationship;

import java.util.Objects;
import java.util.UUID;

/** One equality of a relationship: {@code source.column = target.column}, by physical object id (S2). */
public record ColumnPair(UUID sourceColumnId, UUID targetColumnId) {

    public ColumnPair {
        Objects.requireNonNull(sourceColumnId, "sourceColumnId");
        Objects.requireNonNull(targetColumnId, "targetColumnId");
    }
}
