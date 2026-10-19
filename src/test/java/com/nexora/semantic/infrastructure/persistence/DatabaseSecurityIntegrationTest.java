package com.nexora.semantic.infrastructure.persistence;

import com.nexora.semantic.application.port.AuditLog;
import com.nexora.semantic.application.port.DataSourceRepository;
import com.nexora.semantic.domain.audit.AuditAction;
import com.nexora.semantic.domain.audit.AuditEvent;
import com.nexora.semantic.domain.audit.AuditTargetType;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.PrincipalType;
import com.nexora.semantic.domain.shared.StaleResourceException;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DataSource;
import com.nexora.semantic.domain.source.DataSourceId;
import com.nexora.semantic.domain.source.DataSourceKey;
import com.nexora.semantic.domain.source.EngineCode;
import com.nexora.semantic.domain.tenant.Tenant;
import com.nexora.semantic.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Database-level guarantees: tenant isolation of repositories, append-only audit (privileges and triggers),
 * constraints and the absence of credential columns.
 */
class DatabaseSecurityIntegrationTest extends IntegrationTest {

    private static final PrincipalRef ACTOR = new PrincipalRef("db-test", PrincipalType.HUMAN);

    @Autowired
    private DataSourceRepository dataSources;

    @Autowired
    private AuditLog auditLog;

    private Tenant tenantA;
    private Tenant tenantB;
    private DataSource dsA;

    @BeforeEach
    void setUp() {
        tenantA = newTenant();
        tenantB = newTenant();
        dsA = DataSource.register(DataSourceId.newId(), tenantA.id(), new DataSourceKey("erp"), "ERP",
                new EngineCode("postgresql"), "erp", null, ACTOR.id(), Instant.now().truncatedTo(ChronoUnit.MICROS));
        dataSources.insert(dsA);
        auditLog.append(AuditEvent.of(tenantA.id(), Instant.now(), ACTOR, null, AuditAction.DATA_SOURCE_REGISTERED,
                AuditTargetType.DATA_SOURCE, dsA.id().value(), Map.of("key", "erp")));
    }

    @Test
    void repositoryNeverCrossesTenants() {
        assertThat(dataSources.findById(tenantA.id(), dsA.id())).contains(dsA);
        assertThat(dataSources.findById(tenantB.id(), dsA.id())).isEmpty();
        assertThat(dataSources.findPage(tenantB.id(), null, new PageQuery(0, 50)).items()).isEmpty();
        assertThat(auditLog.find(tenantB.id(), null, dsA.id().value(), new PageQuery(0, 50)).items()).isEmpty();

        DataSource forged = new DataSource(dsA.id(), tenantB.id(), dsA.key(), "hijacked", dsA.engine(),
                dsA.databaseName(), null, dsA.status(), dsA.createdAt(), dsA.createdBy(), dsA.updatedAt(),
                dsA.updatedBy(), 0);
        assertThatThrownBy(() -> dataSources.update(forged, 0)).isInstanceOf(StaleResourceException.class);
        assertThat(dataSources.findById(tenantA.id(), dsA.id()).orElseThrow().name()).isEqualTo("ERP");
    }

    @Test
    void storedTimestampsRoundTripExactly() {
        DataSource stored = dataSources.findById(tenantA.id(), dsA.id()).orElseThrow();
        assertThat(stored).isEqualTo(dsA);
    }

    @Test
    void applicationRoleCannotModifyOrDeleteAuditEvents() {
        UUID tenant = tenantA.id().value();
        assertThatThrownBy(() -> appJdbc.sql("UPDATE audit_event SET action = 'TAMPERED' WHERE tenant_id = :t")
                .param("t", tenant).update()).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> appJdbc.sql("DELETE FROM audit_event WHERE tenant_id = :t")
                .param("t", tenant).update()).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> appJdbc.sql("TRUNCATE audit_event").update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void triggersProtectAuditEventsEvenFromTheOwner() {
        JdbcClient owner = ownerJdbc();
        UUID tenant = tenantA.id().value();
        assertThatThrownBy(() -> owner.sql("UPDATE audit_event SET action = 'TAMPERED' WHERE tenant_id = :t")
                .param("t", tenant).update()).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> owner.sql("DELETE FROM audit_event WHERE tenant_id = :t")
                .param("t", tenant).update()).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> owner.sql("TRUNCATE audit_event").update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("append-only");
    }

    @Test
    void applicationRoleCannotDeleteTenantsOrDataSources() {
        assertThatThrownBy(() -> appJdbc.sql("DELETE FROM data_source WHERE id = :id")
                .param("id", dsA.id().value()).update()).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> appJdbc.sql("DELETE FROM tenant WHERE id = :id")
                .param("id", tenantB.id().value()).update()).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void applicationRoleCannotChangeTheSchema() {
        assertThatThrownBy(() -> appJdbc.sql("ALTER TABLE data_source ADD COLUMN password text").update())
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> appJdbc.sql("DROP TRIGGER audit_event_no_update_delete ON audit_event").update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void dataSourceRequiresExistingTenant() {
        DataSource orphan = DataSource.register(DataSourceId.newId(), TenantId.newId(), new DataSourceKey("orphan"),
                "Orphan", new EngineCode("postgresql"), "db", null, ACTOR.id(), Instant.now());
        assertThatThrownBy(() -> dataSources.insert(orphan)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void checkConstraintsRejectInvalidRowsWrittenOutsideTheDomain() {
        assertThatThrownBy(() -> appJdbc.sql("UPDATE data_source SET status = 'DELETED' WHERE id = :id")
                .param("id", dsA.id().value()).update()).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> appJdbc.sql("""
                        INSERT INTO tenant (id, key, name, status, created_at, created_by, updated_at, updated_by)
                        VALUES (:id, 'Not Valid', 'x', 'ACTIVE', now(), 'x', now(), 'x')""")
                .param("id", UUID.randomUUID()).update()).isInstanceOf(DataIntegrityViolationException.class);
    }

    // --- Audit finding I1: immutable columns (migration V4) ---

    @ParameterizedTest
    @ValueSource(strings = {"tenant_id = :otherTenant", "key = 'renamed'", "engine = 'oracle'",
            "database_name = 'other_db'", "created_by = 'forger'", "created_at = now()", "id = gen_random_uuid()"})
    void applicationRoleCannotUpdateImmutableDataSourceColumns(String assignment) {
        assertThatThrownBy(() -> appJdbc.sql("UPDATE data_source SET " + assignment + " WHERE id = :id")
                .param("id", dsA.id().value())
                .param("otherTenant", tenantB.id().value())
                .update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
    }

    @ParameterizedTest
    @ValueSource(strings = {"key = 'renamed'", "created_by = 'forger'", "created_at = now()", "id = gen_random_uuid()"})
    void applicationRoleCannotUpdateImmutableTenantColumns(String assignment) {
        assertThatThrownBy(() -> appJdbc.sql("UPDATE tenant SET " + assignment + " WHERE id = :id")
                .param("id", tenantA.id().value()).update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void evenTheOwnerCannotMoveADataSourceToAnotherTenant() {
        JdbcClient owner = ownerJdbc();
        assertThatThrownBy(() -> owner.sql("UPDATE data_source SET tenant_id = :other WHERE id = :id")
                .param("other", tenantB.id().value()).param("id", dsA.id().value()).update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("immutable columns of data_source");
        assertThatThrownBy(() -> owner.sql("UPDATE data_source SET engine = 'oracle' WHERE id = :id")
                .param("id", dsA.id().value()).update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("immutable columns of data_source");
        assertThatThrownBy(() -> owner.sql("UPDATE tenant SET key = 'renamed' WHERE id = :id")
                .param("id", tenantA.id().value()).update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("immutable columns of tenant");
        assertThat(dataSources.findById(tenantA.id(), dsA.id())).isPresent();
    }

    @Test
    void mutableColumnsRemainUpdatable() {
        int updated = appJdbc.sql("""
                        UPDATE data_source SET name = 'ERP renamed', description = 'd', status = 'ARCHIVED',
                               updated_at = now(), updated_by = 'x', row_version = row_version + 1
                         WHERE tenant_id = :t AND id = :id""")
                .param("t", tenantA.id().value()).param("id", dsA.id().value()).update();
        assertThat(updated).isEqualTo(1);
        int tenantUpdated = appJdbc.sql("""
                        UPDATE tenant SET name = 'Renamed', status = 'SUSPENDED', updated_at = now(), updated_by = 'x',
                               row_version = row_version + 1 WHERE id = :id""")
                .param("id", tenantB.id().value()).update();
        assertThat(tenantUpdated).isEqualTo(1);
    }

    @Test
    void sameValueAssignmentOfImmutableColumnIsNotRejectedByTriggerForOwner() {
        int updated = ownerJdbc().sql("UPDATE data_source SET tenant_id = tenant_id, name = 'Same tenant' WHERE id = :id")
                .param("id", dsA.id().value()).update();
        assertThat(updated).isEqualTo(1);
    }

    @Test
    void compositeTenantKeyExistsForChildTables() {
        List<String> uniqueConstraints = ownerJdbc().sql("""
                        SELECT conname FROM pg_constraint
                         WHERE conrelid = 'semantic.data_source'::regclass AND contype = 'u'""")
                .query(String.class).list();
        assertThat(uniqueConstraints).contains("data_source_tenant_id_uk", "data_source_key_uk");
    }

    @Test
    void noCredentialColumnsExist() {
        List<String> columns = ownerJdbc().sql("""
                        SELECT table_name || '.' || column_name FROM information_schema.columns
                         WHERE table_schema = 'semantic'
                           AND column_name ~* '(password|passwd|secret|credential|host|username)'""")
                .query(String.class).list();
        assertThat(columns).isEmpty();
    }
}
