package com.nexora.semantic.domain.relationship;

/**
 * What the physical metadata says about the uniqueness of one end of a relationship. Discovery reports primary keys
 * but not UNIQUE constraints or unique indexes, so the only positive evidence is an exact primary key match.
 */
public enum UniquenessEvidence {
    /** The columns of this end are exactly the primary key of its table (same set of columns). */
    PRIMARY_KEY,
    /** No metadata backs uniqueness: values may repeat. This is not evidence that they do. */
    UNKNOWN
}
