package com.nexora.semantic.domain.resolution;

import java.util.List;
import java.util.Objects;

/** Either a complete resolution or the typed diagnostics that prevent it; never a partial result. */
public sealed interface ResolutionOutcome {

    record Resolved(SemanticResolution resolution) implements ResolutionOutcome {
        public Resolved {
            Objects.requireNonNull(resolution, "resolution");
        }
    }

    record Unresolvable(List<ResolutionDiagnostic> diagnostics) implements ResolutionOutcome {
        public Unresolvable {
            diagnostics = List.copyOf(diagnostics);
            if (diagnostics.isEmpty()) {
                throw new IllegalArgumentException("an unresolvable outcome explains why");
            }
        }
    }
}
