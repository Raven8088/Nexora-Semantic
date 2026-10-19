package com.nexora.semantic.domain.shared;

/**
 * Base of all business exceptions. Messages are generic: they must never echo client-supplied values,
 * because they may reach API responses.
 */
public abstract class DomainException extends RuntimeException {

    protected DomainException(String message) {
        super(message);
    }
}
