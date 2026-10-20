package com.nexora.semantic.domain.source;

import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DiscoveredDatabase.Column;
import com.nexora.semantic.domain.source.DiscoveredDatabase.ForeignKey;
import com.nexora.semantic.domain.source.DiscoveredDatabase.Relation;
import com.nexora.semantic.domain.source.DiscoveredDatabase.Schema;
import com.nexora.semantic.domain.source.DiscoveredDatabase.SnapshotScope;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Pure reconciliation rules; a map plays the role of the persisted objects between imports. */
class MetadataReconcilerTest {

    private static final TenantId TENANT = TenantId.newId();
    private static final DataSourceId SOURCE = DataSourceId.newId();
    private static final Instant NOW = Instant.parse("2026-10-19T10:00:00Z");

    private final MetadataReconciler reconciler = new MetadataReconciler();
    private final Map<UUID, PhysicalObject> store = new LinkedHashMap<>();

    // --- builders ---

    private static DiscoveredDatabase db(SnapshotScope scope, Schema... schemas) {
        return new DiscoveredDatabase("discovery-api/0.1", "erp", new EngineCode("postgresql"), "17.6", null, scope,
                List.of(schemas));
    }

    private static DiscoveredDatabase db(Schema... schemas) {
        return db(SnapshotScope.ALL, schemas);
    }

    private static Schema schema(String name, Relation... relations) {
        return new Schema(name, List.of(relations));
    }

    private static Relation table(String name, List<Column> columns, ForeignKey... foreignKeys) {
        return new Relation(PhysicalObjectType.TABLE, name, null, columns, List.of(columns.getFirst().name()),
                List.of(foreignKeys));
    }

    private static Relation view(String name, List<Column> columns) {
        return new Relation(PhysicalObjectType.VIEW, name, null, columns, List.of(), List.of());
    }

    private static Column column(String name, int position, String type) {
        return new Column(name, position, type, null, null, false, null);
    }

    private static Schema sales() {
        return schema("sales",
                table("customers", List.of(column("id", 1, "int8"), column("email", 2, "varchar"))),
                table("orders", List.of(column("id", 1, "int8"), column("customer_id", 2, "int8")),
                        new ForeignKey("orders_customer_fk", List.of("customer_id"), "sales", "customers",
                                List.of("id"))));
    }

    private static Schema inventory() {
        return schema("inventory", table("products", List.of(column("sku", 1, "varchar"))));
    }

    /** Runs an import and applies its result to the in-memory store, like the repository does. */
    private MetadataReconciler.Result importing(DiscoveredDatabase database) {
        MetadataReconciler.Result result = reconciler.reconcile(TENANT, SOURCE, database, List.copyOf(store.values()),
                UUID.randomUUID(), "steward", NOW, UUID::randomUUID);
        result.toInsert().forEach(o -> store.put(o.id(), o));
        result.toUpdate().forEach(o -> store.put(o.id(), o));
        return result;
    }

    private PhysicalObject find(PhysicalObjectType type, NaturalKey key) {
        return store.values().stream().filter(o -> o.type() == type && o.naturalKey().equals(key)).findFirst()
                .orElseThrow();
    }

    // --- tests ---

    @Test
    void firstImportCreatesActiveObjectsWithParentsBeforeChildren() {
        MetadataReconciler.Result result = importing(db(sales()));

        MetadataSnapshot.Summary summary = result.snapshot().summary();
        // 1 schema + 2 tables + 4 columns + 1 foreign key
        assertThat(summary.observed()).isEqualTo(8);
        assertThat(summary.added()).isEqualTo(8);
        assertThat(result.snapshot().outcome()).isEqualTo(MetadataSnapshot.Outcome.APPLIED);
        assertThat(result.toInsert()).extracting(PhysicalObject::type).containsExactly(
                PhysicalObjectType.SCHEMA, PhysicalObjectType.TABLE, PhysicalObjectType.TABLE,
                PhysicalObjectType.COLUMN, PhysicalObjectType.COLUMN, PhysicalObjectType.COLUMN,
                PhysicalObjectType.COLUMN, PhysicalObjectType.FOREIGN_KEY);
        assertThat(result.toInsert()).allMatch(PhysicalObject::isActive);

        PhysicalObject orders = find(PhysicalObjectType.TABLE, NaturalKey.relation("sales", "orders"));
        assertThat(orders.parentId()).isEqualTo(find(PhysicalObjectType.SCHEMA, NaturalKey.schema("sales")).id());
        assertThat(orders.attributes().values()).containsEntry("primaryKey", List.of("id"));
        PhysicalObject fk = find(PhysicalObjectType.FOREIGN_KEY,
                NaturalKey.foreignKey("sales", "orders", "orders_customer_fk"));
        assertThat(fk.parentId()).isEqualTo(orders.id());
        assertThat(fk.attributes().values()).containsEntry("targetTable", "customers")
                .containsEntry("columns", List.of("customer_id"));
        PhysicalObject email = find(PhysicalObjectType.COLUMN, NaturalKey.column("sales", "customers", "email"));
        assertThat(email.attributes().values()).containsEntry("dataType", "varchar").containsEntry("position", 2L)
                .containsEntry("nullable", false).doesNotContainKey("size");
        assertThat(email.firstSeenSnapshotId()).isEqualTo(result.snapshot().id());
    }

    @Test
    void identicalReimportIsUnchangedAndKeepsIds() {
        importing(db(sales()));
        Map<UUID, PhysicalObject> before = new LinkedHashMap<>(store);

        MetadataReconciler.Result result = importing(db(sales()));

        assertThat(result.toInsert()).isEmpty();
        assertThat(result.snapshot().outcome()).isEqualTo(MetadataSnapshot.Outcome.UNCHANGED);
        assertThat(result.snapshot().summary().unchanged()).isEqualTo(8);
        assertThat(store.keySet()).isEqualTo(before.keySet());
        assertThat(store.values()).allMatch(o -> o.lastSeenSnapshotId().equals(result.snapshot().id()));
        assertThat(store.values()).allMatch(o -> before.get(o.id()).firstSeenSnapshotId().equals(o.firstSeenSnapshotId()));
    }

    @Test
    void changedAttributesOrCommentCountAsUpdated() {
        importing(db(sales()));
        Schema changed = schema("sales",
                new Relation(PhysicalObjectType.TABLE, "customers", "Customer master data",
                        List.of(column("id", 1, "int8"), column("email", 2, "text")), List.of("id"), List.of()),
                sales().relations().get(1));

        MetadataReconciler.Result result = importing(db(changed));

        assertThat(result.snapshot().summary().updated()).isEqualTo(2);
        assertThat(find(PhysicalObjectType.COLUMN, NaturalKey.column("sales", "customers", "email"))
                .attributes().values()).containsEntry("dataType", "text");
        assertThat(find(PhysicalObjectType.TABLE, NaturalKey.relation("sales", "customers")).comment())
                .isEqualTo("Customer master data");
    }

    @Test
    void absentObjectsOfAPresentSchemaBecomeMissingButAreNeverRemoved() {
        importing(db(sales()));
        Schema withoutOrders = schema("sales", sales().relations().getFirst());

        MetadataReconciler.Result result = importing(db(withoutOrders));

        // orders + 2 columns + 1 foreign key
        assertThat(result.snapshot().summary().missing()).isEqualTo(4);
        assertThat(store).hasSize(8);
        PhysicalObject orders = find(PhysicalObjectType.TABLE, NaturalKey.relation("sales", "orders"));
        assertThat(orders.status()).isEqualTo(PhysicalObjectStatus.MISSING);
        assertThat(orders.missingSinceSnapshotId()).isEqualTo(result.snapshot().id());

        MetadataReconciler.Result again = importing(db(withoutOrders));
        assertThat(again.snapshot().summary().missing()).isZero();
        assertThat(find(PhysicalObjectType.TABLE, NaturalKey.relation("sales", "orders")).missingSinceSnapshotId())
                .isEqualTo(result.snapshot().id());
    }

    @Test
    void reappearingObjectsKeepTheirIds() {
        importing(db(sales()));
        UUID ordersId = find(PhysicalObjectType.TABLE, NaturalKey.relation("sales", "orders")).id();
        importing(db(schema("sales", sales().relations().getFirst())));

        MetadataReconciler.Result result = importing(db(sales()));

        assertThat(result.snapshot().summary().reappeared()).isEqualTo(4);
        PhysicalObject orders = find(PhysicalObjectType.TABLE, NaturalKey.relation("sales", "orders"));
        assertThat(orders.id()).isEqualTo(ordersId);
        assertThat(orders.status()).isEqualTo(PhysicalObjectStatus.ACTIVE);
        assertThat(orders.missingSinceSnapshotId()).isNull();
    }

    @Test
    void schemasOutsideTheRequestedScopeAreUntouched() {
        MetadataReconciler.Result first = importing(db(sales(), inventory()));
        PhysicalObject products = find(PhysicalObjectType.TABLE, NaturalKey.relation("inventory", "products"));

        MetadataReconciler.Result result = importing(db(new SnapshotScope(Set.of("sales")), sales()));

        assertThat(result.snapshot().summary().missing()).isZero();
        assertThat(result.snapshot().summary().unavailableSchemas()).isEmpty();
        assertThat(find(PhysicalObjectType.TABLE, NaturalKey.relation("inventory", "products")))
                .isEqualTo(products)
                .extracting(PhysicalObject::lastSeenSnapshotId).isEqualTo(first.snapshot().id());
    }

    @Test
    void requestedButAbsentSchemaBecomesUnavailableAndItsChildrenAreUntouched() {
        importing(db(sales(), inventory()));
        PhysicalObject products = find(PhysicalObjectType.TABLE, NaturalKey.relation("inventory", "products"));

        MetadataReconciler.Result result = importing(db(new SnapshotScope(Set.of("sales", "inventory")), sales()));

        assertThat(result.snapshot().summary().unavailableSchemas()).containsExactly("inventory");
        assertThat(result.snapshot().summary().missing()).isZero();
        assertThat(result.snapshot().outcome()).isEqualTo(MetadataSnapshot.Outcome.APPLIED);
        assertThat(find(PhysicalObjectType.SCHEMA, NaturalKey.schema("inventory")).status())
                .isEqualTo(PhysicalObjectStatus.UNAVAILABLE);
        assertThat(find(PhysicalObjectType.TABLE, NaturalKey.relation("inventory", "products"))).isEqualTo(products);

        MetadataReconciler.Result back = importing(db(sales(), inventory()));
        assertThat(back.snapshot().summary().reappeared()).isEqualTo(1);
        assertThat(find(PhysicalObjectType.SCHEMA, NaturalKey.schema("inventory")).status())
                .isEqualTo(PhysicalObjectStatus.ACTIVE);
    }

    @Test
    void fullImportWithoutAKnownSchemaMarksItUnavailableNotMissing() {
        importing(db(sales(), inventory()));

        MetadataReconciler.Result result = importing(db(sales()));

        assertThat(result.snapshot().summary().unavailableSchemas()).containsExactly("inventory");
        assertThat(store.values().stream().filter(o -> o.schemaName().equals("inventory")))
                .noneMatch(o -> o.status() == PhysicalObjectStatus.MISSING);
    }

    @Test
    void requestedSchemaNeverSeenIsReportedWithoutCreatingObjects() {
        MetadataReconciler.Result result = importing(db(new SnapshotScope(Set.of("sales", "restricted")), sales()));

        assertThat(result.snapshot().summary().unavailableSchemas()).containsExactly("restricted");
        assertThat(store.values()).noneMatch(o -> o.schemaName().equals("restricted"));
    }

    @Test
    void tableReplacedByAViewKeepsColumnIdsAndReparentsThem() {
        importing(db(schema("sales", table("summary", List.of(column("id", 1, "int8"))))));
        UUID columnId = find(PhysicalObjectType.COLUMN, NaturalKey.column("sales", "summary", "id")).id();

        MetadataReconciler.Result result = importing(db(schema("sales", view("summary", List.of(column("id", 1, "int8"))))));

        PhysicalObject view = find(PhysicalObjectType.VIEW, NaturalKey.relation("sales", "summary"));
        assertThat(find(PhysicalObjectType.TABLE, NaturalKey.relation("sales", "summary")).status())
                .isEqualTo(PhysicalObjectStatus.MISSING);
        PhysicalObject column = find(PhysicalObjectType.COLUMN, NaturalKey.column("sales", "summary", "id"));
        assertThat(column.id()).isEqualTo(columnId);
        assertThat(column.parentId()).isEqualTo(view.id());
        assertThat(result.snapshot().summary().added()).isEqualTo(1);
        assertThat(result.snapshot().summary().updated()).isEqualTo(1);
        assertThat(result.snapshot().summary().missing()).isEqualTo(1);
    }

    @Test
    void contentHashIgnoresOrdering() {
        Schema reordered = schema("sales", sales().relations().get(1), sales().relations().get(0));
        String first = reconciler.reconcile(TENANT, SOURCE, db(sales(), inventory()), List.of(), UUID.randomUUID(),
                "s", NOW, UUID::randomUUID).snapshot().contentSha256();
        String second = reconciler.reconcile(TENANT, SOURCE, db(inventory(), reordered), List.of(), UUID.randomUUID(),
                "s", NOW, UUID::randomUUID).snapshot().contentSha256();
        assertThat(first).isEqualTo(second).hasSize(64);
    }

    @Test
    void duplicateObjectsAreRejected() {
        assertThatThrownBy(() -> importing(db(sales(), sales())))
                .isInstanceOf(InvalidSnapshotException.class)
                .extracting("kind").isEqualTo(InvalidSnapshotException.Kind.INVALID);
        assertThat(store).isEmpty();
    }

    @Test
    void attributesAcceptOnlySimpleValues() {
        assertThatThrownBy(() -> ObjectAttributes.of(Map.of("nested", Map.of("a", 1))))
                .isInstanceOf(IllegalArgumentException.class);
        List<Object> mixed = new ArrayList<>(List.of("a"));
        mixed.add(1);
        assertThatThrownBy(() -> ObjectAttributes.of(Map.of("list", mixed))).isInstanceOf(IllegalArgumentException.class);
        assertThat(ObjectAttributes.of(Map.of("size", 40)).fingerprint(null))
                .isEqualTo(ObjectAttributes.of(Map.of("size", 40L)).fingerprint(null));
    }
}
