package com.nexora.semantic.api.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Assigns each request a server-generated correlation identifier (a random UUID). Any {@code X-Request-Id} sent by
 * the client is ignored, so callers cannot forge or reuse identifiers recorded in the audit trail (audit finding
 * I2). The identifier is returned in the {@code X-Request-Id} response header and added to the logs (MDC), to error
 * bodies and to audit events. Runs before Spring Security so that 401/403 responses carry it as well.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";
    public static final String MDC_KEY = "correlationId";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id = UUID.randomUUID().toString();
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        MDC.put(MDC_KEY, id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /** Correlation id of the request, or null if the filter did not run. */
    public static String current(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof String id ? id : null;
    }
}
