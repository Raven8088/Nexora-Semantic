package com.nexora.semantic.domain.shared;

/** Optimistic locking failure: the caller acted on a version that is no longer current. */
public class StaleResourceException extends DomainException {

    public StaleResourceException(String resource) {
        super(resource + " has been modified; reload it and retry");
    }
}
