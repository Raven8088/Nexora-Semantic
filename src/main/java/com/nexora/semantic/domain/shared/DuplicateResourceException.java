package com.nexora.semantic.domain.shared;

/** A resource with the same business key already exists in the same scope. */
public class DuplicateResourceException extends DomainException {

    public DuplicateResourceException(String resource) {
        super(resource + " with the same key already exists");
    }
}
