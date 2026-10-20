package com.nexora.semantic.domain.source;

/**
 * Presence of a physical object according to the imports that covered it. Objects are never deleted: absence
 * does not prove that the object was dropped (it may have been renamed or the Discovery role may have lost
 * privileges).
 */
public enum PhysicalObjectStatus {
    /** Seen in the latest import that covered its schema. */
    ACTIVE,
    /** Its schema was imported and present, but the object was absent. */
    MISSING,
    /**
     * Only for schemas: requested (or previously known in a full import) but absent from the payload, e.g. because
     * the Discovery role lost USAGE. Its children keep their previous status.
     */
    UNAVAILABLE
}
