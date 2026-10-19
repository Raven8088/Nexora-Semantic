package com.nexora.semantic.api.web;

/** A modifying request lacks the mandatory {@code If-Match} header (HTTP 428). */
public class PreconditionRequiredException extends RuntimeException {

    public PreconditionRequiredException() {
        super("If-Match header is required");
    }
}
