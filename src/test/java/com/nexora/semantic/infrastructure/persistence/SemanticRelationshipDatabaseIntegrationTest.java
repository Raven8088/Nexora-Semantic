package com.nexora.semantic.infrastructure.persistence;

import com.nexora.semantic.application.catalog.SemanticRelationshipService;
import com.nexora.semantic.application.source.DataSourceService;
import com.nexora.semantic.application.source.MetadataImportService;
import com.nexora.semantic.application.source.RegisterDataSourceCommand;
import com.nexora.semantic.domain.relationship.ColumnPair;
import com.nexora.semantic.domain.relationship.DeclaredCardinality;
import com.nexora.semantic.domain.relationship.JoinType;
import com.nexora.semantic.domain.relationship.SemanticRelationship;
import com.nexora.semantic.domain.shared.CallerContext;
import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.PrincipalType;
import com.nexora.semantic.domain.source.DataSource;
import com.nexora.semantic.domain.tenant.Tenant;
import com.nexora.semantic.support.IntegrationTest;
import com.nexora.semantic.support.SyntheticCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.nexora.semantic.support.DiscoveryFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Database-level guarantees of migration V7: composite foreign keys keep relationships and their pairs inside one
 * tenant and data source, pairs are append-only, endpoints/origin/evidence are immutable, nothing is deleted, and two
 * equivalent relationships can never be APPROVED at the same time.
 */
class SemanticRelationshipDatabaseIntegrationTest extends IntegrationTest {

    private static final PrincipalRef EDITOR = new PrincipalRef("db-editor", PrincipalType.HUMAN);
    private static final PrincipalRef APPROVER = new PrincipalRef("db-approver", PrincipalType.HUMAN);

    @Autowired
    private DataSourceService dataSourceService;

    @Autowired
    private MetadataImportService importService;

    @Autowired
    private SemanticRelationshipService relationships;

    private Tenant tenantA;
    private DataSource sourceA;
    private SemanticRelationship relationship;

    @BeforeEach
    void setUp() {
        tenantA = newTenant();
        sourceA = importedSource(tenantA);
        relationship = relationships.create(caller(tenantA, EDITOR), sourceA.id(), draft(sourceA)).relationship();
    }

    private static CallerContext caller(Tenant tenant, PrincipalRef principal) {
        return new CallerContext(tenant.id(), principal, null);
    }

    private DataSource importedSource(Tenant tenant) {
        CallerContext caller = caller(tenant, EDITOR);
        DataSource source = dataSourceService.register(caller,
                new RegisterDataSourceCommand(unique("erp"), "ERP", "postgresql", "erp", null));
        importService.importSnapshot(caller, source.id(), bytes(SyntheticCatalog.standard().envelope()));
        return source;
    }

    private SemanticRelationshipService.SemanticDraft draft(DataSource source) {
        return new SemanticRelationshipService.SemanticDraft("Lecturas", null,
                objectId(source, "\"plant\".\"measurements\""), objectId(source, "\"plant\".\"equipment\""),
                List.of(new ColumnPair(objectId(source, "\"plant\".\"measurements\".\"equipment_dbid\""),
                        objectId(source, "\"plant\".\"equipment\".\"dbid\""))),
                DeclaredCardinality.MANY_TO_ONE, Set.of(JoinType.INNER), null);
    }

    private UUID objectId(DataSource source, String naturalKey) {
        return appJdbc.sql("SELECT id FROM physical_object WHERE tenant_id = :t AND data_source_id = :d "
                        + "AND natural_key = :k")
                .param("t", source.tenantId().value()).param("d", source.id().value()).param("k", naturalKey)
                .query(UUID.class).single();
    }

    private void insertRelationship(UUID id, UUID tenantId, UUID dataSourceId, UUID sourceId, UUID targetId,
                                    String signature) {
        appJdbc.sql("""
                        INSERT INTO semantic_relationship (id, tenant_id, data_source_id, name, origin,
                               source_object_id, target_object_id, pair_count, signature, declared_cardinality,
                               allow_inner_join, allow_left_join, status, created_by, created_by_type, created_at,
                               updated_by, updated_by_type, updated_at)
                        VALUES (:id, :t, :d, 'x', 'SEMANTIC', :s, :g, 1, :sig, 'MANY_TO_ONE', true, false, 'DRAFT',
                                'x', 'HUMAN', now(), 'x', 'HUMAN', now())""")
                .param("id", id).param("t", tenantId).param("d", dataSourceId).param("s", sourceId)
                .param("g", targetId).param("sig", signature).update();
    }

    private void insertPair(UUID tenantId, UUID dataSourceId, UUID relationshipId, int ordinal, UUID sourceColumn,
                            UUID targetColumn) {
        appJdbc.sql("""
                        INSERT INTO semantic_relationship_pair (tenant_id, data_source_id, relationship_id, ordinal,
                               source_column_id, target_column_id)
                        VALUES (:t, :d, :r, :o, :s, :g)""")
                .param("t", tenantId).param("d", dataSourceId).param("r", relationshipId).param("o", ordinal)
                .param("s", sourceColumn).param("g", targetColumn).update();
    }

    @Test
    void relationshipsAndPairsCannotReferenceObjectsOfAnotherTenantOrDataSource() {
        Tenant tenantB = newTenant();
        DataSource sourceB = importedSource(tenantB);
        DataSource sourceA2 = importedSource(tenantA);
        UUID t = tenantA.id().value();
        UUID d = sourceA.id().value();
        UUID equipmentA = objectId(sourceA, "\"plant\".\"equipment\"");

        assertThatThrownBy(() -> insertRelationship(UUID.randomUUID(), t, d,
                objectId(sourceB, "\"plant\".\"measurements\""), equipmentA, "a".repeat(64)))
                .as("object of another tenant").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRelationship(UUID.randomUUID(), t, d,
                objectId(sourceA2, "\"plant\".\"measurements\""), equipmentA, "b".repeat(64)))
                .as("object of another data source").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertPair(t, d, relationship.id(), 1,
                objectId(sourceA2, "\"plant\".\"measurements\".\"id\""), objectId(sourceA, "\"plant\".\"equipment\".\"id\"")))
                .as("pair column of another data source").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertPair(tenantB.id().value(), sourceB.id().value(), relationship.id(), 1,
                objectId(sourceB, "\"plant\".\"measurements\".\"id\""), objectId(sourceB, "\"plant\".\"equipment\".\"id\"")))
                .as("pair of a relationship of another tenant").isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void pairsAreAppendOnlyForEveryRole() {
        assertThatThrownBy(() -> appJdbc.sql("UPDATE semantic_relationship_pair SET ordinal = 3 "
                        + "WHERE relationship_id = :id").param("id", relationship.id()).update())
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> appJdbc.sql("DELETE FROM semantic_relationship_pair WHERE relationship_id = :id")
                .param("id", relationship.id()).update())
                .rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE semantic_relationship_pair SET target_column_id = "
                        + "source_column_id WHERE relationship_id = :id").param("id", relationship.id()).update())
                .rootCause().hasMessageContaining("append-only");
        assertThatThrownBy(() -> ownerJdbc().sql("DELETE FROM semantic_relationship_pair WHERE relationship_id = :id")
                .param("id", relationship.id()).update())
                .rootCause().hasMessageContaining("append-only");
    }

    @ParameterizedTest
    @ValueSource(strings = {"source_object_id = target_object_id", "origin = 'DECLARED_FK'",
            "signature = repeat('c', 64)", "pair_count = 2", "replaces_id = id", "tenant_id = gen_random_uuid()",
            "created_by = 'forger'"})
    void applicationRoleCannotChangeImmutableColumns(String assignment) {
        assertThatThrownBy(() -> appJdbc.sql("UPDATE semantic_relationship SET " + assignment + " WHERE id = :id")
                .param("id", relationship.id()).update())
                .isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void evenTheOwnerCannotChangeEndpointsAndNothingCanBeDeleted() {
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE semantic_relationship SET target_object_id = "
                        + "source_object_id WHERE id = :id").param("id", relationship.id()).update())
                .rootCause().hasMessageContaining("immutable columns of semantic_relationship");
        assertThatThrownBy(() -> appJdbc.sql("DELETE FROM semantic_relationship WHERE id = :id")
                .param("id", relationship.id()).update())
                .rootCause().hasMessageContaining("permission denied");
    }

    @Test
    void twoEquivalentRelationshipsCanNeverBeApprovedAtTheSameTime() {
        relationships.approve(caller(tenantA, APPROVER), sourceA.id(), relationship.id(), 0);
        UUID duplicate = UUID.randomUUID();
        insertRelationship(duplicate, tenantA.id().value(), sourceA.id().value(), relationship.sourceObjectId(),
                relationship.targetObjectId(), relationship.signature());

        assertThatThrownBy(() -> appJdbc.sql("UPDATE semantic_relationship SET status = 'APPROVED', "
                        + "approved_by = 'x', approved_at = now() WHERE id = :id").param("id", duplicate).update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shapeAndLifecycleConstraintsAreEnforced() {
        assertThatThrownBy(() -> appJdbc.sql("UPDATE semantic_relationship SET status = 'APPROVED' WHERE id = :id")
                .param("id", relationship.id()).update())
                .as("approved relationships record their approver")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> appJdbc.sql("UPDATE semantic_relationship SET status = 'INACTIVE' WHERE id = :id")
                .param("id", relationship.id()).update())
                .as("inactive relationships record a reason")
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> appJdbc.sql("UPDATE semantic_relationship SET allow_inner_join = false, "
                        + "allow_left_join = false WHERE id = :id").param("id", relationship.id()).update())
                .as("at least one JOIN type").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertRelationship(UUID.randomUUID(), tenantA.id().value(), sourceA.id().value(),
                relationship.sourceObjectId(), relationship.targetObjectId(), "not-a-signature"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void applicationRoleRemainsLeastPrivileged() {
        assertThat(new LeastPrivilegeVerifier(appJdbc, "semantic").violations()).isEmpty();
    }
}
