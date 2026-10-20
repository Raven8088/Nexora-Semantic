package com.nexora.semantic.domain.planning;

import java.util.List;
import java.util.Objects;

/** Either a safe logical plan or the reasons it cannot be planned; never a partial or "best effort" plan. */
public sealed interface PlanningOutcome {

    record Planned(LogicalPlan plan) implements PlanningOutcome {
        public Planned {
            Objects.requireNonNull(plan, "plan");
        }
    }

    record Rejected(List<PlanDiagnostic> diagnostics) implements PlanningOutcome {
        public Rejected {
            diagnostics = List.copyOf(diagnostics);
            if (diagnostics.isEmpty()) {
                throw new IllegalArgumentException("a rejection explains why");
            }
        }
    }
}
