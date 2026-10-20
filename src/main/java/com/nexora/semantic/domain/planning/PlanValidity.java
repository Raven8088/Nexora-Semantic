package com.nexora.semantic.domain.planning;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Whether a persisted plan still matches the current catalog of its tenant and data source. Derived on demand: the
 * stored plan is never modified. A STALE plan stays auditable (its snapshot keeps its meaning) but must not be
 * executed or reused as current.
 */
public record PlanValidity(UUID planId, Status status, List<Issue> issues) {

    public enum Status {
        /** Every dependency is unchanged and usable. */
        CURRENT,
        /** Only non-blocking changes (e.g. a column comment or a compatible type rename). */
        CURRENT_WITH_CHANGES,
        /** At least one blocking change: the plan no longer reflects the current catalog. */
        STALE
    }

    public enum Code {
        DATA_SOURCE_ARCHIVED,
        /** Revised (and possibly re-approved) since the plan: its meaning may differ. */
        DEFINITION_CHANGED,
        /** No longer APPROVED, invalidated, or no longer valid against the metadata. */
        DEFINITION_NOT_USABLE,
        RELATIONSHIP_CHANGED,
        /** No longer APPROVED and physically valid (deactivated, invalidated, foreign key lost...). */
        RELATIONSHIP_NOT_USABLE,
        /** The primary key that proved the join safe no longer backs it. */
        UNIQUENESS_NO_LONGER_PROVEN,
        PHYSICAL_OBJECT_MISSING,
        /** The column type changed to another type category. */
        PHYSICAL_TYPE_CHANGED,
        /** Non-blocking: other observed attributes changed (comment, type within the same category...). */
        PHYSICAL_OBJECT_CHANGED
    }

    public record Issue(Code code, boolean blocking, PlanDependency.Kind kind, UUID referenceId, String message) {
        public Issue {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }

    public PlanValidity {
        Objects.requireNonNull(planId, "planId");
        Objects.requireNonNull(status, "status");
        issues = List.copyOf(issues);
    }
}
