package com.nexora.semantic.domain.catalog;

/**
 * Minimal lifecycle (decision D19): DRAFT → APPROVED; APPROVED or DRAFT → INACTIVE; any change → DRAFT (requires a
 * new approval). Only APPROVED definitions without validation issues are usable.
 */
public enum DefinitionStatus {
    DRAFT,
    APPROVED,
    /** Deactivated explicitly or invalidated by a metadata change; needs a revision and a new approval. */
    INACTIVE
}
