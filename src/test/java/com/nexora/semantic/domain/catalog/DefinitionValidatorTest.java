package com.nexora.semantic.domain.catalog;

import com.nexora.semantic.domain.catalog.DefinitionIssue.Code;
import com.nexora.semantic.domain.catalog.DefinitionSpec.ConceptSpec;
import com.nexora.semantic.domain.catalog.DefinitionSpec.DimensionSpec;
import com.nexora.semantic.domain.catalog.DefinitionSpec.MetricSpec;
import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.PrincipalType;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DataSourceId;
import com.nexora.semantic.domain.source.EngineCode;
import com.nexora.semantic.domain.source.NaturalKey;
import com.nexora.semantic.domain.source.ObjectAttributes;
import com.nexora.semantic.domain.source.PhysicalObject;
import com.nexora.semantic.domain.source.PhysicalObjectStatus;
import com.nexora.semantic.domain.source.PhysicalObjectType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefinitionValidatorTest {

    private static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");
    private static final TenantId TENANT = TenantId.newId();
    private static final DataSourceId SOURCE = DataSourceId.newId();
    private static final UUID SNAPSHOT = UUID.randomUUID();
    private static final PrincipalRef EDITOR = new PrincipalRef("editor", PrincipalType.HUMAN);
    private static final PrincipalRef APPROVER = new PrincipalRef("approver", PrincipalType.HUMAN);

    private final DefinitionValidator validator = new DefinitionValidator();
    private final Map<UUID, PhysicalObject> objects = new HashMap<>();
    private final Map<UUID, SemanticDefinition> definitions = new HashMap<>();

    private final PhysicalObject orders = relation("orders");
    private final PhysicalObject customers = relation("customers");
    private final PhysicalObject total = column(orders, "total", "numeric");
    private final PhysicalObject notes = column(orders, "notes", "text");
    private final PhysicalObject orderDate = column(orders, "order_date", "date");
    private final PhysicalObject createdAt = column(customers, "created_at", "timestamptz");
    private final PhysicalObject attributes = column(orders, "attributes", "jsonb");
    private final SemanticDefinition pedido = concept("pedido", orders, true);

    private PhysicalObject relation(String name) {
        PhysicalObject relation = new PhysicalObject(UUID.randomUUID(), TENANT, SOURCE, PhysicalObjectType.TABLE,
                UUID.randomUUID(), "sales", name, null, NaturalKey.relation("sales", name), ObjectAttributes.NONE,
                null, "0".repeat(64), PhysicalObjectStatus.ACTIVE, SNAPSHOT, SNAPSHOT, null, NOW, NOW);
        objects.put(relation.id(), relation);
        return relation;
    }

    private PhysicalObject column(PhysicalObject relation, String name, String dataType) {
        PhysicalObject column = new PhysicalObject(UUID.randomUUID(), TENANT, SOURCE, PhysicalObjectType.COLUMN,
                relation.id(), "sales", relation.objectName(), name,
                NaturalKey.column("sales", relation.objectName(), name),
                ObjectAttributes.of(Map.of("dataType", dataType, "position", 1, "nullable", true)), null,
                "0".repeat(64), PhysicalObjectStatus.ACTIVE, SNAPSHOT, SNAPSHOT, null, NOW, NOW);
        objects.put(column.id(), column);
        return column;
    }

    private SemanticDefinition concept(String key, PhysicalObject relation, boolean approved) {
        SemanticDefinition concept = SemanticDefinition.create(UUID.randomUUID(), TENANT, SOURCE,
                new DefinitionKey(key), key, null, new ConceptSpec(relation.id()), EDITOR, NOW);
        if (approved) {
            concept = concept.approve(APPROVER, NOW);
        }
        definitions.put(concept.id(), concept);
        return concept;
    }

    private void markMissing(PhysicalObject object) {
        objects.put(object.id(), new PhysicalObject(object.id(), TENANT, SOURCE, object.type(), object.parentId(),
                object.schemaName(), object.objectName(), object.childName(), object.naturalKey(), object.attributes(),
                null, object.fingerprint(), PhysicalObjectStatus.MISSING, SNAPSHOT, SNAPSHOT, UUID.randomUUID(),
                NOW, NOW));
    }

    private DefinitionValidator.Context context() {
        return new DefinitionValidator.Context(new EngineCode("postgresql"), objects, definitions);
    }

    private List<Code> codes(DefinitionSpec spec) {
        return validator.issues(spec, context()).stream().map(DefinitionIssue::code).toList();
    }

    private List<Code> usageCodes(DefinitionSpec spec) {
        return validator.usageIssues(spec, context()).stream().map(DefinitionIssue::code).toList();
    }

    @Test
    void validDefinitions() {
        assertThat(codes(new ConceptSpec(orders.id()))).isEmpty();
        assertThat(codes(new MetricSpec(pedido.id(), Aggregation.SUM, total.id()))).isEmpty();
        assertThat(codes(new MetricSpec(pedido.id(), Aggregation.AVG, total.id()))).isEmpty();
        assertThat(codes(new MetricSpec(pedido.id(), Aggregation.COUNT, null))).isEmpty();
        assertThat(codes(new MetricSpec(pedido.id(), Aggregation.COUNT, notes.id()))).isEmpty();
        assertThat(codes(new DimensionSpec(pedido.id(), orderDate.id(), DimensionType.TEMPORAL, null, null))).isEmpty();
        assertThat(codes(new DimensionSpec(pedido.id(), notes.id(), DimensionType.CATEGORICAL, null, null))).isEmpty();
        assertThat(usageCodes(new MetricSpec(pedido.id(), Aggregation.SUM, total.id()))).isEmpty();
    }

    @Test
    void referencesMustExistInTheDataSourceAndHaveTheRightType() {
        assertThat(codes(new ConceptSpec(UUID.randomUUID()))).containsExactly(Code.REFERENCE_NOT_FOUND);
        assertThat(codes(new ConceptSpec(total.id()))).containsExactly(Code.REFERENCE_WRONG_TYPE);
        assertThat(codes(new MetricSpec(pedido.id(), Aggregation.SUM, orders.id()))).containsExactly(Code.REFERENCE_WRONG_TYPE);
        assertThat(codes(new MetricSpec(UUID.randomUUID(), Aggregation.COUNT, null))).containsExactly(Code.REFERENCE_NOT_FOUND);
        SemanticDefinition metric = SemanticDefinition.create(UUID.randomUUID(), TENANT, SOURCE,
                new DefinitionKey("m1"), "m", null, new MetricSpec(pedido.id(), Aggregation.COUNT, null), EDITOR, NOW);
        definitions.put(metric.id(), metric);
        assertThat(codes(new MetricSpec(metric.id(), Aggregation.COUNT, null))).containsExactly(Code.REFERENCE_WRONG_TYPE);
    }

    @Test
    void columnsMustBelongToTheConceptRelation() {
        assertThat(codes(new MetricSpec(pedido.id(), Aggregation.COUNT, createdAt.id())))
                .containsExactly(Code.COLUMN_NOT_IN_CONCEPT);
    }

    @Test
    void missingObjectsAreReported() {
        markMissing(total);
        assertThat(codes(new MetricSpec(pedido.id(), Aggregation.SUM, total.id()))).containsExactly(Code.REFERENCE_MISSING);
        markMissing(orders);
        assertThat(codes(new ConceptSpec(orders.id()))).containsExactly(Code.REFERENCE_MISSING);
        assertThat(usageCodes(new MetricSpec(pedido.id(), Aggregation.COUNT, null)))
                .containsExactly(Code.CONCEPT_NOT_USABLE);
    }

    @Test
    void aggregationsMustMatchTheColumnType() {
        assertThat(codes(new MetricSpec(pedido.id(), Aggregation.SUM, notes.id())))
                .containsExactly(Code.INCOMPATIBLE_AGGREGATION);
        assertThat(codes(new MetricSpec(pedido.id(), Aggregation.AVG, orderDate.id())))
                .containsExactly(Code.INCOMPATIBLE_AGGREGATION);
        assertThat(codes(new MetricSpec(pedido.id(), Aggregation.SUM, null)))
                .containsExactly(Code.INCOMPATIBLE_AGGREGATION);
    }

    @Test
    void dimensionsMustMatchTheColumnType() {
        assertThat(codes(new DimensionSpec(pedido.id(), notes.id(), DimensionType.TEMPORAL, null, null)))
                .containsExactly(Code.INCOMPATIBLE_DIMENSION);
        assertThat(codes(new DimensionSpec(pedido.id(), attributes.id(), DimensionType.CATEGORICAL, null, null)))
                .containsExactly(Code.INCOMPATIBLE_DIMENSION);
        assertThat(codes(new DimensionSpec(pedido.id(), notes.id(), DimensionType.CATEGORICAL,
                Set.of(TimeGranularity.DAY), null))).containsExactly(Code.NOT_APPLICABLE);
    }

    @Test
    void timeZonesApplyOnlyToTimestampWithTimeZone() {
        SemanticDefinition cliente = concept("cliente", customers, true);
        assertThat(codes(new DimensionSpec(pedido.id(), orderDate.id(), DimensionType.TEMPORAL, null,
                ZoneId.of("Europe/Madrid")))).containsExactly(Code.NOT_APPLICABLE);
        assertThat(codes(new DimensionSpec(cliente.id(), createdAt.id(), DimensionType.TEMPORAL, null,
                ZoneId.of("Europe/Madrid")))).isEmpty();
    }

    @Test
    void normalizationFillsTemporalDefaults() {
        SemanticDefinition cliente = concept("cliente", customers, true);
        DimensionSpec tz = (DimensionSpec) validator.normalize(
                new DimensionSpec(cliente.id(), createdAt.id(), DimensionType.TEMPORAL, null, null), context());
        assertThat(tz.timeZone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(tz.granularities()).isEqualTo(EnumSet.allOf(TimeGranularity.class));

        DimensionSpec date = (DimensionSpec) validator.normalize(new DimensionSpec(pedido.id(), orderDate.id(),
                DimensionType.TEMPORAL, Set.of(TimeGranularity.MONTH), null), context());
        assertThat(date.timeZone()).isNull();
        assertThat(date.granularities()).containsExactly(TimeGranularity.MONTH);
    }

    @Test
    void usageRequiresAnApprovedConcept() {
        SemanticDefinition draftConcept = concept("borrador", orders, false);
        assertThat(codes(new MetricSpec(draftConcept.id(), Aggregation.COUNT, null))).isEmpty();
        assertThat(usageCodes(new MetricSpec(draftConcept.id(), Aggregation.COUNT, null)))
                .containsExactly(Code.CONCEPT_NOT_APPROVED);
    }

    @Test
    void messagesNeverContainNames() {
        markMissing(total);
        validator.issues(new MetricSpec(pedido.id(), Aggregation.SUM, total.id()), context())
                .forEach(issue -> assertThat(issue.message()).doesNotContain("total").doesNotContain("orders"));
    }
}
