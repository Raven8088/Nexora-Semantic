package com.nexora.semantic.api.error;

import tools.jackson.databind.exc.UnrecognizedPropertyException;
import com.nexora.semantic.api.web.PayloadTooLargeException;
import com.nexora.semantic.api.web.PreconditionRequiredException;
import com.nexora.semantic.domain.catalog.ApprovalForbiddenException;
import com.nexora.semantic.domain.catalog.InvalidDefinitionException;
import com.nexora.semantic.domain.catalog.InvalidStateTransitionException;
import com.nexora.semantic.domain.relationship.EquivalentRelationshipException;
import com.nexora.semantic.domain.relationship.InvalidRelationshipException;
import com.nexora.semantic.domain.shared.DuplicateResourceException;
import com.nexora.semantic.domain.shared.InvalidValueException;
import com.nexora.semantic.domain.shared.ResourceNotFoundException;
import com.nexora.semantic.domain.shared.StaleResourceException;
import com.nexora.semantic.domain.shared.TenantAccessDeniedException;
import com.nexora.semantic.domain.source.DataSourceArchivedException;
import com.nexora.semantic.domain.source.InvalidSnapshotException;
import com.nexora.semantic.domain.source.SnapshotMismatchException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.jspecify.annotations.Nullable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translates every error into RFC 7807 with a stable {@code code}. Details are generic; exceptions are logged
 * server-side only (never with request bodies, which could contain sensitive text).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final int MAX_FIELD_LENGTH = 64;

    @ExceptionHandler(InvalidValueException.class)
    ResponseEntity<ProblemDetail> invalidValue(InvalidValueException e, HttpServletRequest request) {
        return respond(Problems.validation(List.of(error(e.field(), e.getMessage())), request));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ProblemDetail> notFound(HttpServletRequest request) {
        return respond(Problems.of(ErrorCode.NOT_FOUND, request));
    }

    @ExceptionHandler(DuplicateResourceException.class)
    ResponseEntity<ProblemDetail> duplicate(HttpServletRequest request) {
        return respond(Problems.of(ErrorCode.DUPLICATE_KEY, request));
    }

    @ExceptionHandler(StaleResourceException.class)
    ResponseEntity<ProblemDetail> stale(HttpServletRequest request) {
        return respond(Problems.of(ErrorCode.PRECONDITION_FAILED, request));
    }

    @ExceptionHandler(PreconditionRequiredException.class)
    ResponseEntity<ProblemDetail> preconditionRequired(HttpServletRequest request) {
        return respond(Problems.of(ErrorCode.PRECONDITION_REQUIRED, request));
    }

    @ExceptionHandler(TenantAccessDeniedException.class)
    ResponseEntity<ProblemDetail> tenantDenied(HttpServletRequest request) {
        return respond(Problems.of(ErrorCode.TENANT_ACCESS_DENIED, request));
    }

    @ExceptionHandler(PayloadTooLargeException.class)
    ResponseEntity<ProblemDetail> payloadTooLarge(HttpServletRequest request) {
        return respond(Problems.of(ErrorCode.PAYLOAD_TOO_LARGE, request));
    }

    /** Discovery import documents: violations carry JSON paths and rules, never payload values. */
    @ExceptionHandler(InvalidSnapshotException.class)
    ResponseEntity<ProblemDetail> invalidSnapshot(InvalidSnapshotException e, HttpServletRequest request) {
        ErrorCode code = switch (e.kind()) {
            case MALFORMED -> ErrorCode.MALFORMED_REQUEST;
            case UNSUPPORTED_CONTRACT_VERSION -> ErrorCode.UNSUPPORTED_CONTRACT_VERSION;
            case INVALID -> ErrorCode.SNAPSHOT_INVALID;
        };
        ProblemDetail problem = Problems.of(code, request);
        if (!e.violations().isEmpty()) {
            problem.setProperty("errors", e.violations());
        }
        return respond(problem);
    }

    @ExceptionHandler(SnapshotMismatchException.class)
    ResponseEntity<ProblemDetail> snapshotMismatch(SnapshotMismatchException e, HttpServletRequest request) {
        ProblemDetail problem = Problems.of(ErrorCode.SNAPSHOT_MISMATCH, request);
        problem.setProperty("errors", List.of(error(e.field(), "does not match the data source")));
        return respond(problem);
    }

    @ExceptionHandler(InvalidDefinitionException.class)
    ResponseEntity<ProblemDetail> invalidDefinition(InvalidDefinitionException e, HttpServletRequest request) {
        ProblemDetail problem = Problems.of(ErrorCode.DEFINITION_INVALID, request);
        problem.setProperty("errors", e.issues().stream()
                .map(issue -> Map.of("code", issue.code().name(), "field", issue.field(), "message", issue.message()))
                .toList());
        return respond(problem);
    }

    @ExceptionHandler(InvalidRelationshipException.class)
    ResponseEntity<ProblemDetail> invalidRelationship(InvalidRelationshipException e, HttpServletRequest request) {
        ProblemDetail problem = Problems.of(ErrorCode.RELATIONSHIP_INVALID, request);
        problem.setProperty("errors", e.issues().stream()
                .map(issue -> Map.of("code", issue.code().name(), "field", issue.field(), "message", issue.message()))
                .toList());
        return respond(problem);
    }

    @ExceptionHandler(EquivalentRelationshipException.class)
    ResponseEntity<ProblemDetail> equivalentRelationship(EquivalentRelationshipException e,
                                                         HttpServletRequest request) {
        ProblemDetail problem = Problems.of(ErrorCode.RELATIONSHIP_EQUIVALENT_EXISTS, request);
        if (e.existingId() != null) {
            problem.setProperty("existingRelationshipId", e.existingId().toString());
        }
        return respond(problem);
    }

    @ExceptionHandler(InvalidStateTransitionException.class)
    ResponseEntity<ProblemDetail> invalidTransition(HttpServletRequest request) {
        return respond(Problems.of(ErrorCode.INVALID_STATE_TRANSITION, request));
    }

    @ExceptionHandler(ApprovalForbiddenException.class)
    ResponseEntity<ProblemDetail> approvalForbidden(ApprovalForbiddenException e, HttpServletRequest request) {
        return respond(Problems.of(e.reason() == ApprovalForbiddenException.Reason.NON_HUMAN
                ? ErrorCode.NON_HUMAN_APPROVAL_FORBIDDEN : ErrorCode.SELF_APPROVAL_FORBIDDEN, request));
    }

    @ExceptionHandler(DataSourceArchivedException.class)
    ResponseEntity<ProblemDetail> dataSourceArchived(HttpServletRequest request) {
        return respond(Problems.of(ErrorCode.DATA_SOURCE_ARCHIVED, request));
    }

    /** Raised by method security ({@code @PreAuthorize}) when a permission is missing. */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ProblemDetail> accessDenied(HttpServletRequest request) {
        return respond(Problems.of(ErrorCode.FORBIDDEN, request));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ProblemDetail> unexpected(Exception e, HttpServletRequest request) {
        LOG.error("Unexpected error processing {} {}", request.getMethod(), request.getRequestURI(), e);
        return respond(Problems.of(ErrorCode.INTERNAL_ERROR, request));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            errors.add(error(fieldError.getField(), fieldError.getDefaultMessage()));
        }
        ex.getBindingResult().getGlobalErrors().forEach(e -> errors.add(error(e.getObjectName(), e.getDefaultMessage())));
        return respondObject(Problems.validation(errors, servlet(request)));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        List<Map<String, String>> errors = new ArrayList<>();
        ex.getParameterValidationResults().forEach(result -> {
            String name = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                errors.add(error(name, error.getDefaultMessage()));
            }
        });
        return respondObject(Problems.validation(errors, servlet(request)));
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers,
                                                                  HttpStatusCode status,
                                                                  WebRequest request) {
        if (ex.getCause() instanceof UnrecognizedPropertyException unknown) {
            return respondObject(Problems.validation(
                    List.of(error(unknown.getPropertyName(), "unknown property")), servlet(request)));
        }
        return respondObject(Problems.of(ErrorCode.MALFORMED_REQUEST, servlet(request)));
    }

    /** Path/query parameters of the wrong type (e.g. a malformed UUID); the rejected value is not echoed. */
    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex,
                                                        HttpHeaders headers,
                                                        HttpStatusCode status,
                                                        WebRequest request) {
        String field = ex.getPropertyName() == null ? "parameter" : ex.getPropertyName();
        return respondObject(Problems.validation(List.of(error(field, "has an invalid format")), servlet(request)));
    }

    /** Remaining framework errors: keep the status but replace the detail with a generic, value-free text. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, @Nullable Object body,
                                                             HttpHeaders headers,
                                                             HttpStatusCode statusCode,
                                                             WebRequest request) {
        ErrorCode code = ErrorCode.forStatus(statusCode);
        ProblemDetail problem = Problems.of(statusCode, code, code.detail(), servlet(request));
        HttpHeaders safeHeaders = new HttpHeaders();
        // Keep protocol headers such as Allow (405) and Accept (415); drop anything else.
        if (headers.getAllow() != null && !headers.getAllow().isEmpty()) {
            safeHeaders.setAllow(headers.getAllow());
        }
        if (!headers.getAccept().isEmpty()) {
            safeHeaders.setAccept(headers.getAccept());
        }
        return ResponseEntity.status(statusCode).headers(safeHeaders).body(problem);
    }

    private static Map<String, String> error(String field, String message) {
        String safeField = field == null ? "request"
                : field.length() > MAX_FIELD_LENGTH ? field.substring(0, MAX_FIELD_LENGTH) : field;
        return Map.of("field", safeField, "message", message == null ? "is invalid" : message);
    }

    private static HttpServletRequest servlet(WebRequest request) {
        if (request instanceof ServletWebRequest servletRequest) {
            return servletRequest.getRequest();
        }
        return request instanceof NativeWebRequest nativeRequest
                ? nativeRequest.getNativeRequest(HttpServletRequest.class) : null;
    }

    private static ResponseEntity<ProblemDetail> respond(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }

    private static ResponseEntity<Object> respondObject(ProblemDetail problem) {
        return ResponseEntity.status(problem.getStatus()).body(problem);
    }
}
