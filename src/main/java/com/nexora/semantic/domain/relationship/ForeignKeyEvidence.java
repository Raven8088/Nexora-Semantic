package com.nexora.semantic.domain.relationship;

/** Physical foreign key backing a relationship, kept apart from the declared cardinality. */
public enum ForeignKeyEvidence {
    /** DECLARED_FK whose foreign key is still observed with exactly the same columns and target. */
    PRESENT,
    /** DECLARED_FK whose foreign key is missing, changed or no longer resolvable: the relationship is not usable. */
    LOST,
    /** SEMANTIC relationship: no foreign key is claimed. */
    NOT_DECLARED
}
