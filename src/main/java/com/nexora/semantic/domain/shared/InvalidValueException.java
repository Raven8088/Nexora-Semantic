package com.nexora.semantic.domain.shared;

/** A value violates a domain rule. {@link #field()} names the offending attribute, never its value. */
public class InvalidValueException extends DomainException {

    private final String field;

    public InvalidValueException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
