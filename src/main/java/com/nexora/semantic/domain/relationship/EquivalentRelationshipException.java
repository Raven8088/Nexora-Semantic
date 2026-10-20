package com.nexora.semantic.domain.relationship;

import com.nexora.semantic.domain.shared.DomainException;

import java.util.UUID;

/**
 * Another DRAFT or APPROVED relationship joins the same columns (in either direction): two of them would make
 * join paths ambiguous (HTTP 409). Replacing one requires {@code replacesId} (controlled substitution).
 */
public class EquivalentRelationshipException extends DomainException {

    private final UUID existingId;

    public EquivalentRelationshipException(UUID existingId) {
        super("An equivalent relationship already exists");
        this.existingId = existingId;
    }

    public UUID existingId() {
        return existingId;
    }
}
