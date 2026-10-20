package com.nexora.semantic.domain.planning;

import com.nexora.semantic.domain.shared.DomainException;

import java.util.List;

/** The request resolves semantically but cannot be planned safely (HTTP 422); diagnostics explain why. */
public class PlanRejectedException extends DomainException {

    private final List<PlanDiagnostic> diagnostics;

    public PlanRejectedException(List<PlanDiagnostic> diagnostics) {
        super("Request cannot be planned safely");
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<PlanDiagnostic> diagnostics() {
        return diagnostics;
    }
}
