package com.nexora.semantic.infrastructure.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.nexora.semantic.application.port.AuditLog;
import com.nexora.semantic.domain.audit.AuditAction;
import com.nexora.semantic.domain.audit.AuditEvent;
import com.nexora.semantic.domain.audit.AuditTargetType;
import com.nexora.semantic.domain.shared.Page;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.PrincipalType;
import com.nexora.semantic.domain.shared.TenantId;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.nexora.semantic.infrastructure.persistence.SqlTypes.instant;
import static com.nexora.semantic.infrastructure.persistence.SqlTypes.toDb;

/**
 * Writes to {@code audit_event}, which is append-only: the application role has no UPDATE/DELETE grant and a
 * trigger rejects mutations from any role.
 */
public class JdbcAuditLog implements AuditLog {

    private static final String COLUMNS = "id, tenant_id, occurred_at, actor_id, actor_type, action, target_type, "
            + "target_id, correlation_id, details";
    private static final TypeReference<LinkedHashMap<String, Object>> DETAILS_TYPE = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<AuditEvent> mapper;

    public JdbcAuditLog(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.mapper = (rs, rowNum) -> new AuditEvent(
                rs.getObject("id", UUID.class),
                new TenantId(rs.getObject("tenant_id", UUID.class)),
                instant(rs, "occurred_at"),
                new PrincipalRef(rs.getString("actor_id"), PrincipalType.valueOf(rs.getString("actor_type"))),
                AuditAction.valueOf(rs.getString("action")),
                AuditTargetType.valueOf(rs.getString("target_type")),
                rs.getObject("target_id", UUID.class),
                rs.getString("correlation_id"),
                readDetails(rs.getString("details")));
    }

    @Override
    public void append(AuditEvent event) {
        jdbc.sql("INSERT INTO audit_event (" + COLUMNS + ") VALUES (:id, :tenantId, :occurredAt, :actorId, "
                        + ":actorType, :action, :targetType, :targetId, :correlationId, CAST(:details AS jsonb))")
                .param("id", event.id())
                .param("tenantId", event.tenantId().value())
                .param("occurredAt", toDb(event.occurredAt()))
                .param("actorId", event.actor().id())
                .param("actorType", event.actor().type().name())
                .param("action", event.action().name())
                .param("targetType", event.targetType().name())
                .param("targetId", event.targetId())
                .param("correlationId", event.correlationId())
                .param("details", writeDetails(event.details()))
                .update();
    }

    @Override
    public Page<AuditEvent> find(TenantId tenantId, AuditTargetType targetType, UUID targetId, PageQuery pageQuery) {
        StringBuilder where = new StringBuilder(" WHERE tenant_id = :tenantId");
        if (targetType != null) {
            where.append(" AND target_type = :targetType");
        }
        if (targetId != null) {
            where.append(" AND target_id = :targetId");
        }
        var count = bindFilters(jdbc.sql("SELECT count(*) FROM audit_event" + where), tenantId, targetType, targetId);
        var select = bindFilters(jdbc.sql("SELECT " + COLUMNS + " FROM audit_event" + where
                + " ORDER BY occurred_at DESC, id LIMIT :limit OFFSET :offset"), tenantId, targetType, targetId)
                .param("limit", pageQuery.size())
                .param("offset", pageQuery.offset());
        long total = count.query(Long.class).single();
        return new Page<>(select.query(mapper).list(), pageQuery.page(), pageQuery.size(), total);
    }

    private static JdbcClient.StatementSpec bindFilters(JdbcClient.StatementSpec spec, TenantId tenantId,
                                                        AuditTargetType targetType, UUID targetId) {
        spec = spec.param("tenantId", tenantId.value());
        if (targetType != null) {
            spec = spec.param("targetType", targetType.name());
        }
        if (targetId != null) {
            spec = spec.param("targetId", targetId);
        }
        return spec;
    }

    private String writeDetails(Map<String, Object> details) {
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JacksonException e) {
            throw new IllegalStateException("audit details could not be serialized", e);
        }
    }

    private Map<String, Object> readDetails(String json) {
        try {
            return json == null ? Map.of() : objectMapper.readValue(json, DETAILS_TYPE);
        } catch (JacksonException e) {
            throw new IllegalStateException("audit details could not be read", e);
        }
    }
}
