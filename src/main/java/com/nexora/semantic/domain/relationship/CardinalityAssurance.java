package com.nexora.semantic.domain.relationship;

/** How far the declared cardinality is backed by physical metadata. */
public enum CardinalityAssurance {
    /** Every end declared as "one" is exactly a known primary key. */
    BACKED_BY_PRIMARY_KEY,
    /** At least one end declared as "one" has no uniqueness evidence: the cardinality is a human statement only. */
    DECLARED_ONLY,
    /** MANY_TO_MANY: nothing is claimed unique. */
    NO_UNIQUENESS_CLAIM
}
