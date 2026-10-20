package com.nexora.semantic.domain.relationship;

/**
 * Whether joining in one direction can repeat rows of the side the query starts from. Only a primary key on the
 * joined side rules it out; a declared cardinality does not, and {@code DISTINCT} is not a remedy (it changes
 * aggregates and hides the duplication instead of preventing it).
 */
public enum RowMultiplicationRisk {
    /** The joined side is unique by primary key: each row matches at most one row. */
    NONE_BY_PRIMARY_KEY,
    /** The joined side is not known to be unique: rows may be multiplied. */
    POSSIBLE
}
