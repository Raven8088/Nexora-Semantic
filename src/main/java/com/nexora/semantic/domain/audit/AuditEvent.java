package com.nexora.semantic.domain.audit;

import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.TenantId;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable record of a decision or modification. Stored append-only, in the same transaction as the change.
 *
 * @param correlationId request correlation identifier; may be null
 * @param details       small structured context (changed field names, status transitions). Values are
 *                      limited to strings, numbers, booleans and lists of strings; never secrets or business data.
 */
public record AuditEvent(
        UUID id,
        TenantId tenantId,
        Instant occurredAt,
        PrincipalRef actor,
        AuditAction action,
        AuditTargetType targetType,
        UUID targetId,
        String correlationId,
        Map<String, Object> details) {

    public AuditEvent {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(targetType, "targetType");
        Objects.requireNonNull(targetId, "targetId");
        details = copyDetails(details);
    }

    public static AuditEvent of(TenantId tenantId, Instant occurredAt, PrincipalRef actor, String correlationId,
                                AuditAction action, AuditTargetType targetType, UUID targetId,
                                Map<String, Object> details) {
        return new AuditEvent(UUID.randomUUID(), tenantId, occurredAt, actor, action, targetType, targetId,
                correlationId, details);
    }

    private static Map<String, Object> copyDetails(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            Objects.requireNonNull(key, "details key");
            copy.put(key, requireSupported(key, value));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object requireSupported(String key, Object value) {
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return List.copyOf(list);
        }
        throw new IllegalArgumentException("unsupported audit detail value for key " + key);
    }
}
