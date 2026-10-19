package com.nexora.semantic.application.tenant;

import com.nexora.semantic.application.InMemoryAdapters;
import com.nexora.semantic.domain.audit.AuditAction;
import com.nexora.semantic.domain.audit.AuditEvent;
import com.nexora.semantic.domain.shared.DuplicateResourceException;
import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.PrincipalType;
import com.nexora.semantic.domain.shared.StaleResourceException;
import com.nexora.semantic.domain.shared.TenantAccessDeniedException;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.tenant.Tenant;
import com.nexora.semantic.domain.tenant.TenantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantServiceTest {

    private static final PrincipalRef ADMIN = new PrincipalRef("platform-admin", PrincipalType.HUMAN);

    private InMemoryAdapters.RecordingAuditLog auditLog;
    private TenantService service;

    @BeforeEach
    void setUp() {
        auditLog = new InMemoryAdapters.RecordingAuditLog();
        service = new TenantService(new InMemoryAdapters.Tenants(), auditLog,
                Clock.fixed(Instant.parse("2026-10-19T08:00:00Z"), ZoneOffset.UTC));
    }

    @Test
    void registerAuditsInTheNewTenant() {
        Tenant tenant = service.register("acme", "Acme", ADMIN, "cid-1");

        AuditEvent event = auditLog.events().getFirst();
        assertThat(event.action()).isEqualTo(AuditAction.TENANT_REGISTERED);
        assertThat(event.tenantId()).isEqualTo(tenant.id());
        assertThat(event.targetId()).isEqualTo(tenant.id().value());
        assertThat(event.actor()).isEqualTo(ADMIN);
        assertThat(event.correlationId()).isEqualTo("cid-1");
    }

    @Test
    void duplicateKeyIsRejected() {
        service.register("acme", "Acme", ADMIN, null);
        assertThatThrownBy(() -> service.register("acme", "Other", ADMIN, null))
                .isInstanceOf(DuplicateResourceException.class);
    }

    @Test
    void resolvesActiveTenantById() {
        Tenant tenant = service.register("acme", "Acme", ADMIN, null);
        assertThat(service.resolveActiveTenant(tenant.id())).isEqualTo(tenant.id());
    }

    @Test
    void rejectsMissingOrUnknownTenantIds() {
        service.register("acme", "Acme", ADMIN, null);
        assertThatThrownBy(() -> service.resolveActiveTenant(null)).isInstanceOf(TenantAccessDeniedException.class);
        assertThatThrownBy(() -> service.resolveActiveTenant(TenantId.newId()))
                .isInstanceOf(TenantAccessDeniedException.class);
    }

    @Test
    void suspendedTenantIsRejectedUntilReactivated() {
        Tenant tenant = service.register("acme", "Acme", ADMIN, null);

        Tenant suspended = service.update(tenant.id(), 0, null, TenantStatus.SUSPENDED, ADMIN, null);
        assertThat(suspended.version()).isEqualTo(1);
        assertThatThrownBy(() -> service.resolveActiveTenant(tenant.id()))
                .isInstanceOf(TenantAccessDeniedException.class);

        service.update(tenant.id(), 1, null, TenantStatus.ACTIVE, ADMIN, null);
        assertThat(service.resolveActiveTenant(tenant.id())).isEqualTo(tenant.id());
        assertThat(auditLog.events()).extracting(AuditEvent::action).containsExactly(
                AuditAction.TENANT_REGISTERED, AuditAction.TENANT_SUSPENDED, AuditAction.TENANT_REACTIVATED);
    }

    @Test
    void updateWithStaleVersionIsRejected() {
        Tenant tenant = service.register("acme", "Acme", ADMIN, null);
        service.update(tenant.id(), 0, "Acme Corp", null, ADMIN, null);
        assertThatThrownBy(() -> service.update(tenant.id(), 0, "Acme SA", null, ADMIN, null))
                .isInstanceOf(StaleResourceException.class);
    }
}
