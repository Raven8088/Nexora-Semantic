package com.nexora.semantic.domain.catalog;

/** Controlled aggregations of S3 Basic; there is no free-form expression language. */
public enum Aggregation {
    /** Rows of the concept, or non-null values of a column. Any column type. */
    COUNT,
    /** Numeric columns only. */
    SUM,
    /** Numeric columns only. */
    AVG;

    public boolean requiresNumericColumn() {
        return this != COUNT;
    }
}
