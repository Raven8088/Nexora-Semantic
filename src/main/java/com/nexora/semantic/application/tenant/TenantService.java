package com.nexora.semantic.application.tenant;

import com.nexora.semantic.application.port.AuditLog;
import com.nexora.semantic.application.port.TenantRepository;
import com.nexora.semantic.domain.audit.AuditAction;
import com.nexora.semantic.domain.audit.AuditEvent;
import com.nexora.semantic.domain.audit.AuditTargetType;
import com.nexora.semantic.domain.shared.Page;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.ResourceNotFoundException;
import com.nexora.semantic.domain.shared.StaleResourceException;
import com.nexora.semantic.domain.shared.TenantAccessDeniedException;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.tenant.Tenant;
import com.nexora.semantic.domain.tenant.TenantKey;
import com.nexora.semantic.domain.tenant.TenantStatus;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

/**
 * Tenant administration (platform operators) and resolution of the tenant bound to an access token.
 */
@Transactional
public class TenantService {

    private static final String RESOURCE = "Tenant";

    private final TenantRepository tenants;
    private final AuditLog auditLog;
    private final Clock clock;

    public TenantService(TenantRepository tenants, AuditLog auditLog, Clock clock) {
        this.tenants = tenants;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    public Tenant register(String key, String name, PrincipalRef actor, String correlationId) {
        Instant now = now();
        Tenant tenant = Tenant.register(TenantId.newId(), new TenantKey(key), name, actor.id(), now);
        tenants.insert(tenant);
        auditLog.append(AuditEvent.of(tenant.id(), now, actor, correlationId, AuditAction.TENANT_REGISTERED,
                AuditTargetType.TENANT, tenant.id().value(), Map.of("key", tenant.key().value())));
        return tenant;
    }

    @Transactional(readOnly = true)
    public Tenant get(TenantId id) {
        return tenants.findById(id).orElseThrow(() -> new ResourceNotFoundException(RESOURCE));
    }

    @Transactional(readOnly = true)
    public Page<Tenant> list(PageQuery pageQuery) {
        return tenants.findAll(pageQuery);
    }

    /** {@code null} arguments keep the current value. */
    public Tenant update(TenantId id, long expectedVersion, String newName, TenantStatus newStatus,
                         PrincipalRef actor, String correlationId) {
        Tenant current = get(id);
        if (current.version() != expectedVersion) {
            throw new StaleResourceException(RESOURCE);
        }
        Instant now = now();
        Tenant changed = current.change(newName, newStatus, actor.id(), now);
        if (changed == current) {
            return current;
        }
        Tenant saved = tenants.update(changed, expectedVersion);
        if (!changed.name().equals(current.name())) {
            auditLog.append(AuditEvent.of(id, now, actor, correlationId, AuditAction.TENANT_UPDATED,
                    AuditTargetType.TENANT, id.value(), Map.of("changedFields", List.of("name"))));
        }
        if (changed.status() != current.status()) {
            AuditAction action = changed.isActive() ? AuditAction.TENANT_REACTIVATED : AuditAction.TENANT_SUSPENDED;
            auditLog.append(AuditEvent.of(id, now, actor, correlationId, action, AuditTargetType.TENANT,
                    id.value(), Map.of("from", current.status().name(), "to", changed.status().name())));
        }
        return saved;
    }

    /**
     * Checks that the tenant carried by an access token exists and is ACTIVE. Missing, unknown and suspended
     * tenants are rejected identically so callers cannot probe which tenants exist.
     */
    @Transactional(readOnly = true)
    public TenantId resolveActiveTenant(TenantId tenantId) {
        if (tenantId == null) {
            throw new TenantAccessDeniedException();
        }
        return tenants.findById(tenantId)
                .filter(Tenant::isActive)
                .map(Tenant::id)
                .orElseThrow(TenantAccessDeniedException::new);
    }

    /** Microsecond precision, matching PostgreSQL timestamps, so returned values equal stored ones. */
    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
