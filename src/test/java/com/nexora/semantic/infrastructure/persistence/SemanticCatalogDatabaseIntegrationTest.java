package com.nexora.semantic.infrastructure.persistence;

import com.nexora.semantic.application.catalog.SemanticCatalogService;
import com.nexora.semantic.application.source.DataSourceService;
import com.nexora.semantic.application.source.MetadataImportService;
import com.nexora.semantic.application.source.RegisterDataSourceCommand;
import com.nexora.semantic.domain.catalog.DefinitionSpec;
import com.nexora.semantic.domain.shared.CallerContext;
import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.PrincipalType;
import com.nexora.semantic.domain.source.DataSource;
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
 * Database-level guarantees of migration V6: composite foreign keys make references to objects or concepts of another
 * tenant or data source impossible, identity columns are immutable, nothing can be deleted, and the shape of each
 * kind is enforced.
 */
class SemanticCatalogDatabaseIntegrationTest extends IntegrationTest {

    private static final PrincipalRef EDITOR = new PrincipalRef("db-editor", PrincipalType.HUMAN);

    @Autowired
    private DataSourceService dataSourceService;

    @Autowired
    private MetadataImportService importService;

    @Autowired
    private SemanticCatalogService catalog;

    private Tenant tenantA;
    private DataSource sourceA;
    private UUID ordersA;
    private UUID conceptA;

    @BeforeEach
    void setUp() {
        tenantA = newTenant();
        sourceA = importedSource(tenantA);
        ordersA = objectId(sourceA, "\"sales\".\"orders\"");
        conceptA = catalog.create(new CallerContext(tenantA.id(), EDITOR, null), sourceA.id(), "pedido", "Pedido", null,
                new DefinitionSpec.ConceptSpec(ordersA)).definition().id();
    }

    private DataSource importedSource(Tenant tenant) {
        CallerContext caller = new CallerContext(tenant.id(), EDITOR, null);
        DataSource source = dataSourceService.register(caller,
                new RegisterDataSourceCommand(unique("erp"), "ERP", "postgresql", "erp", null));
        importService.importSnapshot(caller, source.id(), bytes(envelope(ALL_SCHEMAS)));
        return source;
    }

    private UUID objectId(DataSource source, String naturalKey) {
        return appJdbc.sql("SELECT id FROM physical_object WHERE tenant_id = :t AND data_source_id = :d AND natural_key = :k")
                .param("t", source.tenantId().value()).param("d", source.id().value()).param("k", naturalKey)
                .query(UUID.class).single();
    }

    private void insertConcept(UUID tenantId, UUID dataSourceId, UUID relationId) {
        appJdbc.sql("""
                        INSERT INTO semantic_definition (id, tenant_id, data_source_id, kind, key, name, status, origin,
                               relation_object_id, created_by, created_by_type, created_at, updated_by, updated_by_type,
                               updated_at)
                        VALUES (gen_random_uuid(), :t, :d, 'CONCEPT', :key, 'x', 'DRAFT', 'MANUAL', :r,
                                'x', 'HUMAN', now(), 'x', 'HUMAN', now())""")
                .param("t", tenantId).param("d", dataSourceId).param("r", relationId).param("key", unique("k"))
                .update();
    }

    @Test
    void definitionsCannotReferenceObjectsOfAnotherTenant() {
        Tenant tenantB = newTenant();
        DataSource sourceB = importedSource(tenantB);
        UUID ordersB = objectId(sourceB, "\"sales\".\"orders\"");

        assertThatThrownBy(() -> insertConcept(tenantA.id().value(), sourceA.id().value(), ordersB))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertConcept(tenantB.id().value(), sourceA.id().value(), ordersA))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void definitionsCannotReferenceObjectsOrConceptsOfAnotherDataSource() {
        DataSource sourceA2 = importedSource(tenantA);
        UUID ordersA2 = objectId(sourceA2, "\"sales\".\"orders\"");

        assertThatThrownBy(() -> insertConcept(tenantA.id().value(), sourceA.id().value(), ordersA2))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> appJdbc.sql("""
                        INSERT INTO semantic_definition (id, tenant_id, data_source_id, kind, key, name, status, origin,
                               concept_id, aggregation, created_by, created_by_type, created_at, updated_by,
                               updated_by_type, updated_at)
                        VALUES (gen_random_uuid(), :t, :d, 'METRIC', 'm', 'x', 'DRAFT', 'MANUAL', :c, 'COUNT',
                                'x', 'HUMAN', now(), 'x', 'HUMAN', now())""")
                .param("t", tenantA.id().value()).param("d", sourceA2.id().value()).param("c", conceptA).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"key = 'otro'", "kind = 'METRIC'", "tenant_id = gen_random_uuid()",
            "data_source_id = gen_random_uuid()", "origin = 'AI'", "created_by = 'forger'", "created_at = now()"})
    void applicationRoleCannotChangeIdentityColumns(String assignment) {
        assertThatThrownBy(() -> appJdbc.sql("UPDATE semantic_definition SET " + assignment + " WHERE id = :id")
                .param("id", conceptA).update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void evenTheOwnerCannotChangeIdentityColumnsAndNothingCanBeDeleted() {
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE semantic_definition SET key = 'otro' WHERE id = :id")
                .param("id", conceptA).update())
                .rootCause().hasMessageContaining("immutable columns of semantic_definition");
        assertThatThrownBy(() -> appJdbc.sql("DELETE FROM semantic_definition WHERE id = :id").param("id", conceptA).update())
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void shapeAndLifecycleConstraintsAreEnforced() {
        assertThatThrownBy(() -> appJdbc.sql("UPDATE semantic_definition SET aggregation = 'SUM' WHERE id = :id")
                .param("id", conceptA).update())
                .as("a concept has no aggregation")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> appJdbc.sql("UPDATE semantic_definition SET status = 'APPROVED' WHERE id = :id")
                .param("id", conceptA).update())
                .as("approved definitions record their approver")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> appJdbc.sql("UPDATE semantic_definition SET status = 'INACTIVE' WHERE id = :id")
                .param("id", conceptA).update())
                .as("inactive definitions record a reason")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> appJdbc.sql("UPDATE semantic_definition SET relation_object_id = NULL WHERE id = :id")
                .param("id", conceptA).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void applicationRoleRemainsLeastPrivileged() {
        assertThat(new LeastPrivilegeVerifier(appJdbc, "semantic").violations()).isEmpty();
    }
}
