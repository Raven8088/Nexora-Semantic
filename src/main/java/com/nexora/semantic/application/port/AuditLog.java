package com.nexora.semantic.application.port;

import com.nexora.semantic.domain.audit.AuditEvent;
import com.nexora.semantic.domain.audit.AuditTargetType;
import com.nexora.semantic.domain.shared.Page;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.shared.TenantId;

import java.util.UUID;

/** Append-only audit trail. Events are written in the caller's transaction. */
public interface AuditLog {

    void append(AuditEvent event);

    /**
     * Events of one tenant, newest first.
     *
     * @param targetType optional filter
     * @param targetId   optional filter
     */
    Page<AuditEvent> find(TenantId tenantId, AuditTargetType targetType, UUID targetId, PageQuery pageQuery);
}
