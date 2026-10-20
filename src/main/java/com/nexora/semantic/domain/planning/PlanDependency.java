package com.nexora.semantic.domain.planning;

import java.util.Objects;
import java.util.UUID;

/**
 * A catalog element a persisted plan depends on, with the revision observed when it was planned.
 *
 * @param revision    definition or relationship version (null for physical objects)
 * @param fingerprint physical object fingerprint (null otherwise)
 * @param dataType    declared column data type (null otherwise)
 * @param navigation  direction the plan navigates a relationship (null otherwise)
 */
public record PlanDependency(Kind kind, UUID referenceId, Long revision, String fingerprint, String dataType,
                             LogicalPlan.Navigation navigation) {

    public enum Kind { DEFINITION, RELATIONSHIP, PHYSICAL_OBJECT }

    public PlanDependency {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(referenceId, "referenceId");
    }

    static PlanDependency definition(UUID id, long version) {
        return new PlanDependency(Kind.DEFINITION, id, version, null, null, null);
    }

    static PlanDependency relationship(UUID id, long version, LogicalPlan.Navigation navigation) {
        return new PlanDependency(Kind.RELATIONSHIP, id, version, null, null, navigation);
    }

    static PlanDependency object(UUID id, String fingerprint, String dataType) {
        return new PlanDependency(Kind.PHYSICAL_OBJECT, id, null, fingerprint, dataType, null);
    }
}
