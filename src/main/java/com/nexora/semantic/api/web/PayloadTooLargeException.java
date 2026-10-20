package com.nexora.semantic.api.web;

/** The request body exceeds the configured limit (HTTP 413). */
public class PayloadTooLargeException extends RuntimeException {

    public PayloadTooLargeException() {
        super("Request body too large");
    }
}
