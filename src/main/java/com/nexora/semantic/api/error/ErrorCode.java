package com.nexora.semantic.api.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

import java.util.Locale;

/** Stable machine-readable error codes, exposed as the {@code code} property of RFC 7807 responses. */
public enum ErrorCode {
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Request validation failed"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Malformed or unreadable request"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Authentication is required"),
    FORBIDDEN(HttpStatus.FORBIDDEN, "Not allowed to perform this operation"),
    TENANT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "Tenant is not available for this principal"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Requested representation not available"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type"),
    DUPLICATE_KEY(HttpStatus.CONFLICT, "A resource with the same key already exists"),
    PRECONDITION_FAILED(HttpStatus.PRECONDITION_FAILED, "The resource has been modified; reload it and retry"),
    PRECONDITION_REQUIRED(HttpStatus.PRECONDITION_REQUIRED, "If-Match header is required"),
    PAYLOAD_TOO_LARGE(HttpStatus.CONTENT_TOO_LARGE, "Request too large"),
    SNAPSHOT_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Snapshot does not satisfy the import contract"),
    SNAPSHOT_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "Snapshot does not belong to this data source"),
    UNSUPPORTED_CONTRACT_VERSION(HttpStatus.BAD_REQUEST, "Unsupported import contract version"),
    DATA_SOURCE_ARCHIVED(HttpStatus.CONFLICT, "Data source is archived"),
    DEFINITION_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Definition is not valid"),
    INVALID_STATE_TRANSITION(HttpStatus.CONFLICT, "Action not allowed in the current status"),
    SELF_APPROVAL_FORBIDDEN(HttpStatus.FORBIDDEN, "The author or last editor of a definition cannot approve it"),
    NON_HUMAN_APPROVAL_FORBIDDEN(HttpStatus.FORBIDDEN, "Only human principals can approve definitions"),
    RELATIONSHIP_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "Relationship is not valid"),
    RELATIONSHIP_EQUIVALENT_EXISTS(HttpStatus.CONFLICT,
            "An equivalent relationship already exists; replace it explicitly instead"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");

    private final HttpStatus status;
    private final String detail;

    ErrorCode(HttpStatus status, String detail) {
        this.status = status;
        this.detail = detail;
    }

    public HttpStatus status() {
        return status;
    }

    public String detail() {
        return detail;
    }

    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Generic code for framework errors that only carry a status. */
    public static ErrorCode forStatus(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> MALFORMED_REQUEST;
            case 401 -> UNAUTHORIZED;
            case 403 -> FORBIDDEN;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            default -> status.is4xxClientError() ? MALFORMED_REQUEST : INTERNAL_ERROR;
        };
    }
}
