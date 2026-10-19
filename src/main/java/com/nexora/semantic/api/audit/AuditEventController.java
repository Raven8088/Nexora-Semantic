package com.nexora.semantic.api.audit;

import com.nexora.semantic.api.security.Permission;
import com.nexora.semantic.api.web.PageResponse;
import com.nexora.semantic.application.audit.AuditQueryService;
import com.nexora.semantic.domain.audit.AuditAction;
import com.nexora.semantic.domain.audit.AuditEvent;
import com.nexora.semantic.domain.audit.AuditTargetType;
import com.nexora.semantic.domain.shared.CallerContext;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.shared.PrincipalType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Read-only access to the audit trail of the caller's tenant. */
@RestController
@RequestMapping(path = "/api/v1/audit-events", produces = MediaType.APPLICATION_JSON_VALUE)
public class AuditEventController {

    private final AuditQueryService auditQueryService;

    public AuditEventController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @GetMapping
    @PreAuthorize(Permission.Require.AUDIT_READ)
    public PageResponse<AuditEventResponse> find(CallerContext caller,
                                                 @RequestParam(required = false) AuditTargetType targetType,
                                                 @RequestParam(required = false) UUID targetId,
                                                 @RequestParam(defaultValue = "0") @Min(0) int page,
                                                 @RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE)
                                                 @Min(1) @Max(PageQuery.MAX_SIZE) int size) {
        return PageResponse.of(auditQueryService.find(caller, targetType, targetId, new PageQuery(page, size)),
                AuditEventResponse::from);
    }

    public record AuditEventResponse(
            UUID id,
            Instant occurredAt,
            String actorId,
            PrincipalType actorType,
            AuditAction action,
            AuditTargetType targetType,
            UUID targetId,
            String correlationId,
            Map<String, Object> details) {

        static AuditEventResponse from(AuditEvent event) {
            return new AuditEventResponse(event.id(), event.occurredAt(), event.actor().id(), event.actor().type(),
                    event.action(), event.targetType(), event.targetId(), event.correlationId(), event.details());
        }
    }
}
