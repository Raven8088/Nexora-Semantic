package com.nexora.semantic.domain.relationship;

/**
 * Cardinality declared by a person, read from the source object to the target object ("many measurements to one
 * equipment" is {@link #MANY_TO_ONE}). It is a statement, never a guarantee: Semantic does not analyse data, and the
 * only uniqueness it can confirm is a known primary key ({@link UniquenessEvidence}).
 */
public enum DeclaredCardinality {
    ONE_TO_ONE(true, true),
    ONE_TO_MANY(true, false),
    MANY_TO_ONE(false, true),
    MANY_TO_MANY(false, false);

    private final boolean sourceOne;
    private final boolean targetOne;

    DeclaredCardinality(boolean sourceOne, boolean targetOne) {
        this.sourceOne = sourceOne;
        this.targetOne = targetOne;
    }

    /** Each target row matches at most one source row, according to the declaration. */
    public boolean sourceOne() {
        return sourceOne;
    }

    /** Each source row matches at most one target row, according to the declaration. */
    public boolean targetOne() {
        return targetOne;
    }
}
