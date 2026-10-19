package com.nexora.semantic.application.audit;

import com.nexora.semantic.application.port.AuditLog;
import com.nexora.semantic.domain.audit.AuditEvent;
import com.nexora.semantic.domain.audit.AuditTargetType;
import com.nexora.semantic.domain.shared.CallerContext;
import com.nexora.semantic.domain.shared.Page;
import com.nexora.semantic.domain.shared.PageQuery;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Read access to the audit trail of the caller's tenant. */
@Transactional(readOnly = true)
public class AuditQueryService {

    private final AuditLog auditLog;

    public AuditQueryService(AuditLog auditLog) {
        this.auditLog = auditLog;
    }

    public Page<AuditEvent> find(CallerContext caller, AuditTargetType targetType, UUID targetId,
                                 PageQuery pageQuery) {
        return auditLog.find(caller.tenantId(), targetType, targetId, pageQuery);
    }
}
