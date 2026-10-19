package com.nexora.semantic.application.source;

import com.nexora.semantic.application.InMemoryAdapters;
import com.nexora.semantic.domain.audit.AuditAction;
import com.nexora.semantic.domain.audit.AuditEvent;
import com.nexora.semantic.domain.audit.AuditTargetType;
import com.nexora.semantic.domain.shared.CallerContext;
import com.nexora.semantic.domain.shared.DuplicateResourceException;
import com.nexora.semantic.domain.shared.InvalidValueException;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.PrincipalType;
import com.nexora.semantic.domain.shared.ResourceNotFoundException;
import com.nexora.semantic.domain.shared.StaleResourceException;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DataSource;
import com.nexora.semantic.domain.source.DataSourceChanges;
import com.nexora.semantic.domain.source.DataSourceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSourceServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-19T08:00:00.123456789Z");
    private static final PrincipalRef STEWARD = new PrincipalRef("steward-1", PrincipalType.HUMAN);

    private InMemoryAdapters.DataSources repository;
    private InMemoryAdapters.RecordingAuditLog auditLog;
    private DataSourceService service;
    private CallerContext caller;
    private CallerContext otherTenantCaller;

    @BeforeEach
    void setUp() {
        repository = new InMemoryAdapters.DataSources();
        auditLog = new InMemoryAdapters.RecordingAuditLog();
        service = new DataSourceService(repository, auditLog, Clock.fixed(NOW, ZoneOffset.UTC));
        caller = new CallerContext(TenantId.newId(), STEWARD, "cid-123");
        otherTenantCaller = new CallerContext(TenantId.newId(), STEWARD, "cid-456");
    }

    private DataSource registerErp() {
        return service.register(caller, new RegisterDataSourceCommand("erp", "ERP", "PostgreSQL", "erp", null));
    }

    @Test
    void registerPersistsInCallerTenantAndAudits() {
        DataSource ds = registerErp();

        assertThat(ds.tenantId()).isEqualTo(caller.tenantId());
        assertThat(ds.engine().value()).isEqualTo("postgresql");
        assertThat(ds.createdAt()).isEqualTo(Instant.parse("2026-10-19T08:00:00.123456Z"));
        assertThat(repository.findById(caller.tenantId(), ds.id())).contains(ds);

        AuditEvent event = auditLog.events().getFirst();
        assertThat(event.action()).isEqualTo(AuditAction.DATA_SOURCE_REGISTERED);
        assertThat(event.tenantId()).isEqualTo(caller.tenantId());
        assertThat(event.actor()).isEqualTo(STEWARD);
        assertThat(event.correlationId()).isEqualTo("cid-123");
        assertThat(event.targetType()).isEqualTo(AuditTargetType.DATA_SOURCE);
        assertThat(event.targetId()).isEqualTo(ds.id().value());
        assertThat(event.details()).isEqualTo(Map.of("key", "erp", "engine", "postgresql"));
    }

    @Test
    void registerRejectsInvalidInputWithoutSideEffects() {
        assertThatThrownBy(() -> service.register(caller,
                new RegisterDataSourceCommand("ERP!", "ERP", "postgresql", "erp", null)))
                .isInstanceOf(InvalidValueException.class);
        assertThat(auditLog.events()).isEmpty();
    }

    @Test
    void duplicateKeyInSameTenantIsRejected() {
        registerErp();
        assertThatThrownBy(this::registerErp).isInstanceOf(DuplicateResourceException.class);
        assertThat(auditLog.events()).hasSize(1);
    }

    @Test
    void sameKeyIsAllowedInAnotherTenant() {
        registerErp();
        DataSource other = service.register(otherTenantCaller,
                new RegisterDataSourceCommand("erp", "ERP", "postgresql", "erp", null));
        assertThat(other.tenantId()).isEqualTo(otherTenantCaller.tenantId());
    }

    @Test
    void otherTenantCannotReadOrModify() {
        DataSource ds = registerErp();

        assertThatThrownBy(() -> service.get(otherTenantCaller, ds.id())).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.update(otherTenantCaller, ds.id(), 0, new DataSourceChanges("x", null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(service.list(otherTenantCaller, null, new PageQuery(0, 10)).items()).isEmpty();
    }

    @Test
    void updateWithStaleVersionIsRejected() {
        DataSource ds = registerErp();
        service.update(caller, ds.id(), 0, new DataSourceChanges("ERP v2", null, null));

        assertThatThrownBy(() -> service.update(caller, ds.id(), 0, new DataSourceChanges("ERP v3", null, null)))
                .isInstanceOf(StaleResourceException.class);
    }

    @Test
    void updateIncrementsVersionAndAuditsChangedFields() {
        DataSource ds = registerErp();

        DataSource updated = service.update(caller, ds.id(), 0, new DataSourceChanges("ERP v2", "Main", null));

        assertThat(updated.version()).isEqualTo(1);
        assertThat(updated.name()).isEqualTo("ERP v2");
        AuditEvent event = auditLog.events().getLast();
        assertThat(event.action()).isEqualTo(AuditAction.DATA_SOURCE_UPDATED);
        assertThat(event.details()).containsEntry("changedFields", List.of("name", "description"));
    }

    @Test
    void archivingAuditsStatusTransitionOnly() {
        DataSource ds = registerErp();

        DataSource archived = service.update(caller, ds.id(), 0, new DataSourceChanges(null, null, DataSourceStatus.ARCHIVED));

        assertThat(archived.status()).isEqualTo(DataSourceStatus.ARCHIVED);
        assertThat(auditLog.events()).extracting(AuditEvent::action)
                .containsExactly(AuditAction.DATA_SOURCE_REGISTERED, AuditAction.DATA_SOURCE_ARCHIVED);
        assertThat(auditLog.events().getLast().details()).isEqualTo(Map.of("from", "ACTIVE", "to", "ARCHIVED"));

        service.update(caller, ds.id(), 1, new DataSourceChanges(null, null, DataSourceStatus.ACTIVE));
        assertThat(auditLog.events().getLast().action()).isEqualTo(AuditAction.DATA_SOURCE_REACTIVATED);
    }

    @Test
    void updateWithoutChangesKeepsVersionAndDoesNotAudit() {
        DataSource ds = registerErp();

        DataSource result = service.update(caller, ds.id(), 0, new DataSourceChanges("ERP", null, DataSourceStatus.ACTIVE));

        assertThat(result.version()).isZero();
        assertThat(auditLog.events()).hasSize(1);
    }

    @Test
    void listFiltersByStatusAndPaginates() {
        registerErp();
        DataSource crm = service.register(caller, new RegisterDataSourceCommand("crm", "CRM", "postgresql", "crm", null));
        service.register(caller, new RegisterDataSourceCommand("wms", "WMS", "postgresql", "wms", null));
        service.update(caller, crm.id(), 0, new DataSourceChanges(null, null, DataSourceStatus.ARCHIVED));

        assertThat(service.list(caller, DataSourceStatus.ARCHIVED, new PageQuery(0, 10)).items())
                .extracting(d -> d.key().value()).containsExactly("crm");
        var firstPage = service.list(caller, null, new PageQuery(0, 2));
        assertThat(firstPage.totalItems()).isEqualTo(3);
        assertThat(firstPage.items()).extracting(d -> d.key().value()).containsExactly("crm", "erp");
    }
}
