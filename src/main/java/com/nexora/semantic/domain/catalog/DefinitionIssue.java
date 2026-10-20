package com.nexora.semantic.domain.catalog;

import java.util.Objects;

/**
 * Why a catalog element (definition or relationship) cannot be saved, approved or used. {@code field} is a request
 * path, never a value.
 */
public record DefinitionIssue(Code code, String field, String message) {

    public enum Code {
        REFERENCE_NOT_FOUND,
        REFERENCE_WRONG_TYPE,
        REFERENCE_MISSING,
        COLUMN_NOT_IN_CONCEPT,
        INCOMPATIBLE_AGGREGATION,
        INCOMPATIBLE_DIMENSION,
        NOT_APPLICABLE,
        CONCEPT_NOT_APPROVED,
        CONCEPT_NOT_USABLE,
        // S3.1 relationships
        COLUMN_NOT_IN_RELATION,
        INCOMPATIBLE_TYPES,
        DUPLICATE_PAIR,
        CONTRADICTORY_PAIR,
        FOREIGN_KEY_LOST,
        REFERENCE_UNRESOLVABLE,
        REPLACEMENT_INVALID
    }

    public DefinitionIssue {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(message, "message");
    }
}
