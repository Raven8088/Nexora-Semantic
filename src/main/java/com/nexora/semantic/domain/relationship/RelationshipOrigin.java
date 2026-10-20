package com.nexora.semantic.domain.relationship;

/** Where a relationship comes from. Immutable once created. */
public enum RelationshipOrigin {
    /** Created by a person from a foreign key observed in the physical metadata; keeps the FK as evidence. */
    DECLARED_FK,
    /** Declared by a person between columns without (or regardless of) a physical foreign key. */
    SEMANTIC
}
