package com.nexora.semantic.domain.catalog;

import com.nexora.semantic.domain.shared.DomainException;

/** The requested lifecycle action is not allowed from the current status (HTTP 409). */
public class InvalidStateTransitionException extends DomainException {

    public InvalidStateTransitionException(DefinitionStatus from, String action) {
        super("Cannot " + action + " a definition in status " + from);
    }
}
