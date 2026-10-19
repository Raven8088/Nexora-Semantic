package com.nexora.semantic.api.error;

import tools.jackson.databind.ObjectMapper;
import com.nexora.semantic.api.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Builds RFC 7807 bodies with a stable {@code code} and the request's {@code correlationId}. Details are generic:
 * they never contain stack traces, SQL, driver messages or values sent by the client.
 */
public final class Problems {

    private static final URI ABOUT_BLANK = URI.create("about:blank");

    private Problems() {
    }

    public static ProblemDetail of(ErrorCode code, HttpServletRequest request) {
        return of(code.status(), code, code.detail(), request);
    }

    public static ProblemDetail of(HttpStatusCode status, ErrorCode code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        // Spring Framework 7 no longer defaults "type" to about:blank (it would be omitted); keep the contract explicit.
        problem.setType(ABOUT_BLANK);
        problem.setProperty("code", code.code());
        if (request != null) {
            String correlationId = CorrelationIdFilter.current(request);
            if (correlationId != null) {
                problem.setProperty("correlationId", correlationId);
            }
            problem.setInstance(safeInstance(request.getRequestURI()));
        }
        return problem;
    }

    /** Validation problem listing the offending fields; messages describe rules, never rejected values. */
    public static ProblemDetail validation(List<Map<String, String>> errors, HttpServletRequest request) {
        ProblemDetail problem = of(ErrorCode.VALIDATION_FAILED, request);
        problem.setProperty("errors", errors);
        return problem;
    }

    /** Writes a problem from servlet filters (authentication and authorization failures). */
    public static void write(HttpServletResponse response, ProblemDetail problem, ObjectMapper objectMapper)
            throws IOException {
        response.setStatus(problem.getStatus());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    private static URI safeInstance(String path) {
        try {
            return path == null ? null : URI.create(path);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
