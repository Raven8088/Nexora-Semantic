package com.nexora.semantic.domain.catalog;

/** Kinds of semantic definitions in S3 Basic. */
public enum DefinitionKind {
    /** Business entity bound to a physical table or view (e.g. "Pedido" → sales.orders). */
    CONCEPT,
    /** Controlled aggregation over a concept (COUNT, SUM, AVG). */
    METRIC,
    /** Attribute of a concept used to group or filter (categorical or temporal). */
    DIMENSION
}
