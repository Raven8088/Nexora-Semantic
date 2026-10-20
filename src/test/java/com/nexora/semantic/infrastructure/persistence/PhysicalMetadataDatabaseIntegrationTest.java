package com.nexora.semantic.infrastructure.persistence;

import com.nexora.semantic.application.source.DataSourceService;
import com.nexora.semantic.application.source.MetadataImportService;
import com.nexora.semantic.application.source.RegisterDataSourceCommand;
import com.nexora.semantic.domain.shared.CallerContext;
import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.PrincipalType;
import com.nexora.semantic.domain.source.DataSource;
import com.nexora.semantic.domain.source.MetadataSnapshot;
import com.nexora.semantic.domain.tenant.Tenant;
import com.nexora.semantic.support.IntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

import static com.nexora.semantic.support.DiscoveryFixtures.ALL_SCHEMAS;
import static com.nexora.semantic.support.DiscoveryFixtures.bytes;
import static com.nexora.semantic.support.DiscoveryFixtures.envelope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Database-level guarantees of migration V5: identity columns of physical objects never change, snapshots are
 * append-only, nothing can be deleted, and composite foreign keys make cross-tenant or cross-data-source references
 * impossible.
 */
class PhysicalMetadataDatabaseIntegrationTest extends IntegrationTest {

    private static final PrincipalRef STEWARD = new PrincipalRef("db-steward", PrincipalType.HUMAN);

    @Autowired
    private DataSourceService dataSourceService;

    @Autowired
    private MetadataImportService importService;

    private Tenant tenantA;
    private DataSource sourceA;
    private MetadataSnapshot snapshotA;
    private UUID ordersId;

    @BeforeEach
    void setUp() {
        tenantA = newTenant();
        CallerContext caller = new CallerContext(tenantA.id(), STEWARD, null);
        sourceA = dataSourceService.register(caller,
                new RegisterDataSourceCommand(unique("erp"), "ERP", "postgresql", "erp", null));
        snapshotA = importService.importSnapshot(caller, sourceA.id(), bytes(envelope(ALL_SCHEMAS)));
        ordersId = appJdbc.sql("SELECT id FROM physical_object WHERE tenant_id = :t AND natural_key = :k")
                .param("t", tenantA.id().value()).param("k", "\"sales\".\"orders\"")
                .query(UUID.class).single();
    }

    @ParameterizedTest
    @ValueSource(strings = {"natural_key = 'x'", "object_type = 'VIEW'", "tenant_id = gen_random_uuid()",
            "data_source_id = gen_random_uuid()", "schema_name = 'x'", "object_name = 'x'",
            "first_seen_snapshot_id = last_seen_snapshot_id", "created_at = now()", "id = gen_random_uuid()"})
    void applicationRoleCannotChangeIdentityColumns(String assignment) {
        assertThatThrownBy(() -> appJdbc.sql("UPDATE physical_object SET " + assignment + " WHERE id = :id")
                .param("id", ordersId).update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void evenTheOwnerCannotChangeIdentityColumns() {
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE physical_object SET natural_key = 'x' WHERE id = :id")
                .param("id", ordersId).update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("immutable columns of physical_object");
    }

    @Test
    void snapshotsAreAppendOnlyAndNothingCanBeDeleted() {
        assertThatThrownBy(() -> appJdbc.sql("UPDATE metadata_snapshot SET outcome = 'UNCHANGED' WHERE id = :id")
                .param("id", snapshotA.id()).update())
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE metadata_snapshot SET outcome = 'UNCHANGED' WHERE id = :id")
                .param("id", snapshotA.id()).update())
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> appJdbc.sql("DELETE FROM physical_object WHERE id = :id").param("id", ordersId).update())
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> appJdbc.sql("DELETE FROM metadata_snapshot WHERE id = :id")
                .param("id", snapshotA.id()).update())
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> appJdbc.sql("TRUNCATE physical_object").update())
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void objectsCannotReferenceAnotherTenantsDataSource() {
        Tenant tenantB = newTenant();
        assertThatThrownBy(() -> insertObject(UUID.randomUUID(), tenantB.id().value(), sourceA.id().value(), null,
                snapshotA.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void objectsCannotHaveAParentFromAnotherDataSource() {
        CallerContext caller = new CallerContext(tenantA.id(), STEWARD, null);
        DataSource sourceB = dataSourceService.register(caller,
                new RegisterDataSourceCommand(unique("erp"), "ERP 2", "postgresql", "erp", null));
        MetadataSnapshot snapshotB = importService.importSnapshot(caller, sourceB.id(), bytes(envelope(ALL_SCHEMAS)));

        assertThatThrownBy(() -> insertObject(UUID.randomUUID(), tenantA.id().value(), sourceB.id().value(), ordersId,
                snapshotB.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertObject(UUID.randomUUID(), tenantA.id().value(), sourceB.id().value(), null,
                snapshotA.id()))
                .as("snapshot of another data source")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void checkConstraintsRejectInconsistentRows() {
        assertThatThrownBy(() -> appJdbc.sql("UPDATE physical_object SET status = 'MISSING' WHERE id = :id")
                .param("id", ordersId).update())
                .as("MISSING requires missing_since_snapshot_id")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> appJdbc.sql("UPDATE physical_object SET status = 'UNAVAILABLE' WHERE id = :id")
                .param("id", ordersId).update())
                .as("only schemas can be UNAVAILABLE")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void applicationRoleRemainsLeastPrivileged() {
        assertThat(new LeastPrivilegeVerifier(appJdbc, "semantic").violations()).isEmpty();
    }

    private void insertObject(UUID id, UUID tenantId, UUID dataSourceId, UUID parentId, UUID snapshotId) {
        String type = parentId == null ? "SCHEMA" : "TABLE";
        appJdbc.sql("""
                        INSERT INTO physical_object (id, tenant_id, data_source_id, object_type, parent_id, schema_name,
                               object_name, natural_key, attributes, attributes_sha256, status, first_seen_snapshot_id,
                               last_seen_snapshot_id, created_at, updated_at)
                        VALUES (:id, :tenantId, :dataSourceId, :type, :parentId, 'intruder',
                                CASE WHEN :type = 'SCHEMA' THEN NULL ELSE 'x' END, :key, '{}'::jsonb, repeat('0', 64),
                                'ACTIVE', :snapshotId, :snapshotId, now(), now())""")
                .param("id", id).param("tenantId", tenantId).param("dataSourceId", dataSourceId)
                .param("type", type).param("parentId", parentId).param("key", "\"intruder\"." + id)
                .param("snapshotId", snapshotId)
                .update();
    }
}
