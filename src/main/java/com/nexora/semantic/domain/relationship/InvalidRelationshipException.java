package com.nexora.semantic.domain.relationship;

import com.nexora.semantic.domain.catalog.DefinitionIssue;
import com.nexora.semantic.domain.shared.DomainException;

import java.util.List;

/** The relationship does not satisfy the physical validation rules (HTTP 422); issues explain why. */
public class InvalidRelationshipException extends DomainException {

    private final List<DefinitionIssue> issues;

    public InvalidRelationshipException(List<DefinitionIssue> issues) {
        super("Relationship is not valid");
        this.issues = List.copyOf(issues);
    }

    public List<DefinitionIssue> issues() {
        return issues;
    }
}
