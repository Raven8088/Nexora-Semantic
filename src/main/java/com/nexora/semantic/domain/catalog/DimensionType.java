package com.nexora.semantic.domain.catalog;

public enum DimensionType {
    /** Grouping by distinct values (text, numbers, booleans, identifiers, dates). */
    CATEGORICAL,
    /** Grouping by time buckets ({@link TimeGranularity}); requires a date or timestamp column. */
    TEMPORAL
}
