package com.nexora.semantic.domain.catalog;

import com.nexora.semantic.domain.shared.DomainException;

import java.util.List;

/** The definition references physical objects or concepts that make it invalid (HTTP 422). */
public class InvalidDefinitionException extends DomainException {

    private final List<DefinitionIssue> issues;

    public InvalidDefinitionException(List<DefinitionIssue> issues) {
        super("Definition is not valid");
        this.issues = List.copyOf(issues);
    }

    public List<DefinitionIssue> issues() {
        return issues;
    }
}
