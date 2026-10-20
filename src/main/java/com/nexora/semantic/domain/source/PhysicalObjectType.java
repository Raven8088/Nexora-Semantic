package com.nexora.semantic.domain.source;

/** Kind of database object discovered by Nexora Discovery and tracked by Semantic. */
public enum PhysicalObjectType {
    SCHEMA,
    TABLE,
    VIEW,
    MATERIALIZED_VIEW,
    COLUMN,
    FOREIGN_KEY;

    public boolean isRelation() {
        return this == TABLE || this == VIEW || this == MATERIALIZED_VIEW;
    }
}
