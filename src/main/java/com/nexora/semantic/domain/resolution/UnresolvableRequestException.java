package com.nexora.semantic.domain.resolution;

import com.nexora.semantic.domain.shared.DomainException;

import java.util.List;

/** The request cannot be resolved (HTTP 422); diagnostics explain why. */
public class UnresolvableRequestException extends DomainException {

    private final List<ResolutionDiagnostic> diagnostics;

    public UnresolvableRequestException(List<ResolutionDiagnostic> diagnostics) {
        super("Semantic request cannot be resolved");
        this.diagnostics = List.copyOf(diagnostics);
    }

    public List<ResolutionDiagnostic> diagnostics() {
        return diagnostics;
    }
}
