package com.nexora.semantic.domain.resolution;

/** Filter operators of S4.1. Each one has a fixed number of values; none of them carries an expression. */
public enum FilterOperator {
    EQ(Arity.ONE),
    NE(Arity.ONE),
    GT(Arity.ONE),
    GTE(Arity.ONE),
    LT(Arity.ONE),
    LTE(Arity.ONE),
    /** One to {@link SemanticResolver#MAX_IN_VALUES} distinct values. */
    IN(Arity.MANY),
    IS_NULL(Arity.NONE),
    IS_NOT_NULL(Arity.NONE);

    enum Arity { NONE, ONE, MANY }

    private final Arity arity;

    FilterOperator(Arity arity) {
        this.arity = arity;
    }

    Arity arity() {
        return arity;
    }

    /** Ordering operators: only meaningful for numeric and temporal values. */
    boolean isOrdering() {
        return this == GT || this == GTE || this == LT || this == LTE;
    }

    boolean isNullCheck() {
        return arity == Arity.NONE;
    }
}
