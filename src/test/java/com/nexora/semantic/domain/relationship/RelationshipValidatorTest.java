package com.nexora.semantic.domain.relationship;

import com.nexora.semantic.domain.catalog.DefinitionIssue;
import com.nexora.semantic.domain.catalog.DefinitionIssue.Code;
import com.nexora.semantic.domain.source.PhysicalObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Physical validation and evidence (S3.1): nothing is assumed from names, {@code id} columns, primary or foreign
 * keys; cardinality is declared and only primary keys count as uniqueness evidence.
 */
class RelationshipValidatorTest {

    private final RelationshipValidator validator = new RelationshipValidator();
    private final Metadata m = new Metadata();

    private final PhysicalObject equipment = m.table("plant", "equipment", "id");
    private final PhysicalObject equipmentId = m.column(equipment, "id", "int8");
    private final PhysicalObject equipmentDbid = m.column(equipment, "dbid", "int8");
    private final PhysicalObject measurements = m.table("plant", "measurements", "id");
    private final PhysicalObject measurementDbid = m.column(measurements, "equipment_dbid", "int8");
    private final PhysicalObject measuredAt = m.column(measurements, "measured_at", "timestamptz");

    private RelationshipValidator.Evaluation semantic(PhysicalObject source, PhysicalObject target,
                                                      DeclaredCardinality cardinality, ColumnPair... pairs) {
        return validator.evaluate(Metadata.POSTGRESQL, m.objects, RelationshipOrigin.SEMANTIC, source.id(),
                target.id(), List.of(pairs), null, cardinality);
    }

    private static ColumnPair pair(PhysicalObject source, PhysicalObject target) {
        return new ColumnPair(source.id(), target.id());
    }

    private static List<Code> codes(RelationshipValidator.Evaluation evaluation) {
        return evaluation.issues().stream().map(DefinitionIssue::code).toList();
    }

    @Test
    void equipmentDbidIsValidWithoutPrimaryKeyUniqueOrForeignKeyAndItsUniquenessIsNotPresentedAsGuaranteed() {
        RelationshipValidator.Evaluation evaluation = semantic(measurements, equipment,
                DeclaredCardinality.MANY_TO_ONE, pair(measurementDbid, equipmentDbid));

        assertThat(evaluation.valid()).isTrue();
        RelationshipEvidence evidence = evaluation.evidence();
        assertThat(evidence.targetUniqueness()).isEqualTo(UniquenessEvidence.UNKNOWN);
        assertThat(evidence.sourceUniqueness()).isEqualTo(UniquenessEvidence.UNKNOWN);
        assertThat(evidence.foreignKey()).isEqualTo(ForeignKeyEvidence.NOT_DECLARED);
        assertThat(evidence.cardinalityAssurance()).isEqualTo(CardinalityAssurance.DECLARED_ONLY);
        assertThat(evidence.sourceToTargetRisk()).isEqualTo(RowMultiplicationRisk.POSSIBLE);
        assertThat(evidence.targetToSourceRisk()).isEqualTo(RowMultiplicationRisk.POSSIBLE);
    }

    @Test
    void uniquenessEvidenceRequiresAnExactPrimaryKeyMatch() {
        RelationshipValidator.Evaluation byId = semantic(measurements, equipment, DeclaredCardinality.MANY_TO_ONE,
                pair(measurementDbid, equipmentId));
        assertThat(byId.evidence().targetUniqueness()).isEqualTo(UniquenessEvidence.PRIMARY_KEY);
        assertThat(byId.evidence().cardinalityAssurance()).isEqualTo(CardinalityAssurance.BACKED_BY_PRIMARY_KEY);
        assertThat(byId.evidence().sourceToTargetRisk()).isEqualTo(RowMultiplicationRisk.NONE_BY_PRIMARY_KEY);
        assertThat(byId.evidence().targetToSourceRisk()).isEqualTo(RowMultiplicationRisk.POSSIBLE);

        PhysicalObject documents = m.table("docs", "documents", "company_code", "number");
        PhysicalObject company = m.column(documents, "company_code", "bpchar");
        PhysicalObject number = m.column(documents, "number", "varchar");
        PhysicalObject transactions = m.table("finance", "transactions", "id");
        PhysicalObject txCompany = m.column(transactions, "company_code", "bpchar");
        PhysicalObject txNumber = m.column(transactions, "document_number", "varchar");

        RelationshipValidator.Evaluation composite = semantic(transactions, documents,
                DeclaredCardinality.MANY_TO_ONE, pair(txCompany, company), pair(txNumber, number));
        assertThat(composite.valid()).isTrue();
        assertThat(composite.evidence().targetUniqueness()).isEqualTo(UniquenessEvidence.PRIMARY_KEY);

        RelationshipValidator.Evaluation partial = semantic(transactions, documents,
                DeclaredCardinality.MANY_TO_ONE, pair(txCompany, company));
        assertThat(partial.evidence().targetUniqueness())
                .as("one column of a composite primary key is not unique")
                .isEqualTo(UniquenessEvidence.UNKNOWN);
        assertThat(partial.evidence().cardinalityAssurance()).isEqualTo(CardinalityAssurance.DECLARED_ONLY);
    }

    @Test
    void declaredCardinalityWithoutEvidenceIsReportedAsDeclaredOnly() {
        RelationshipValidator.Evaluation oneToOne = semantic(measurements, equipment, DeclaredCardinality.ONE_TO_ONE,
                pair(measurementDbid, equipmentId));
        assertThat(oneToOne.evidence().cardinalityAssurance())
                .as("the source end is declared unique but has no primary key evidence")
                .isEqualTo(CardinalityAssurance.DECLARED_ONLY);
        RelationshipValidator.Evaluation manyToMany = semantic(measurements, equipment,
                DeclaredCardinality.MANY_TO_MANY, pair(measurementDbid, equipmentDbid));
        assertThat(manyToMany.evidence().cardinalityAssurance()).isEqualTo(CardinalityAssurance.NO_UNIQUENESS_CLAIM);
    }

    @Test
    void columnsMustExistAndBelongToTheirSide() {
        RelationshipValidator.Evaluation unknown = semantic(measurements, equipment, DeclaredCardinality.MANY_TO_ONE,
                new ColumnPair(UUID.randomUUID(), equipmentDbid.id()));
        assertThat(codes(unknown)).containsExactly(Code.REFERENCE_NOT_FOUND);
        assertThat(unknown.issues().getFirst().field()).isEqualTo("pairs[0].sourceColumnId");

        RelationshipValidator.Evaluation swapped = semantic(measurements, equipment, DeclaredCardinality.MANY_TO_ONE,
                pair(equipmentDbid, measurementDbid));
        assertThat(codes(swapped)).containsExactly(Code.COLUMN_NOT_IN_RELATION, Code.COLUMN_NOT_IN_RELATION);

        RelationshipValidator.Evaluation columnAsTable = semantic(measurementDbid, equipment,
                DeclaredCardinality.MANY_TO_ONE, pair(measurementDbid, equipmentDbid));
        assertThat(codes(columnAsTable)).contains(Code.REFERENCE_WRONG_TYPE);
    }

    @Test
    void incompatibleTypesAreRejected() {
        RelationshipValidator.Evaluation evaluation = semantic(measurements, equipment,
                DeclaredCardinality.MANY_TO_ONE, pair(measuredAt, equipmentDbid));
        assertThat(codes(evaluation)).containsExactly(Code.INCOMPATIBLE_TYPES);

        PhysicalObject jsonA = m.column(measurements, "payload", "jsonb");
        PhysicalObject jsonB = m.column(equipment, "spec", "jsonb");
        PhysicalObject xml = m.column(equipment, "doc", "xml");
        assertThat(semantic(measurements, equipment, DeclaredCardinality.MANY_TO_ONE, pair(jsonA, jsonB)).valid())
                .as("uncategorized types are compatible only with the exact same type")
                .isTrue();
        assertThat(codes(semantic(measurements, equipment, DeclaredCardinality.MANY_TO_ONE, pair(jsonA, xml))))
                .containsExactly(Code.INCOMPATIBLE_TYPES);
    }

    @Test
    void repeatedOrContradictoryPairsAreRejected() {
        assertThat(codes(semantic(measurements, equipment, DeclaredCardinality.MANY_TO_ONE,
                pair(measurementDbid, equipmentDbid), pair(measurementDbid, equipmentDbid))))
                .containsExactly(Code.DUPLICATE_PAIR);
        assertThat(codes(semantic(measurements, equipment, DeclaredCardinality.MANY_TO_ONE,
                pair(measurementDbid, equipmentDbid), pair(measurementDbid, equipmentId))))
                .containsExactly(Code.CONTRADICTORY_PAIR);
        assertThat(codes(semantic(equipment, equipment, DeclaredCardinality.ONE_TO_ONE,
                pair(equipmentDbid, equipmentDbid))))
                .containsExactly(Code.CONTRADICTORY_PAIR);
    }

    @Test
    void missingObjectsAndRetypedColumnsInvalidate() {
        m.missing(equipmentDbid);
        assertThat(codes(semantic(measurements, equipment, DeclaredCardinality.MANY_TO_ONE,
                pair(measurementDbid, equipmentDbid)))).containsExactly(Code.REFERENCE_MISSING);
        m.missing(equipment);
        assertThat(codes(semantic(measurements, equipment, DeclaredCardinality.MANY_TO_ONE,
                pair(measurementDbid, equipmentId)))).containsExactly(Code.REFERENCE_MISSING);
    }

    @Test
    void retypedColumnInvalidates() {
        m.retyped(measurementDbid, "varchar");
        assertThat(codes(semantic(measurements, equipment, DeclaredCardinality.MANY_TO_ONE,
                pair(measurementDbid, equipmentDbid)))).containsExactly(Code.INCOMPATIBLE_TYPES);
    }

    @Test
    void declaredForeignKeyEvidenceIsPresentOnlyWhileTheForeignKeyStillJoinsExactlyTheseColumns() {
        PhysicalObject sensors = m.table("plant", "sensors", "id");
        PhysicalObject sensorEquipment = m.column(sensors, "equipment_id", "int8");
        PhysicalObject fk = m.foreignKey(sensors, "sensors_equipment_fk", List.of("equipment_id"), "plant",
                "equipment", List.of("id"));
        List<ColumnPair> pairs = List.of(pair(sensorEquipment, equipmentId));

        RelationshipValidator.Evaluation present = validator.evaluate(Metadata.POSTGRESQL, m.objects,
                RelationshipOrigin.DECLARED_FK, sensors.id(), equipment.id(), pairs, fk.id(),
                DeclaredCardinality.MANY_TO_ONE);
        assertThat(present.valid()).isTrue();
        assertThat(present.evidence().foreignKey()).isEqualTo(ForeignKeyEvidence.PRESENT);

        RelationshipValidator.Evaluation otherColumns = validator.evaluate(Metadata.POSTGRESQL, m.objects,
                RelationshipOrigin.DECLARED_FK, sensors.id(), equipment.id(),
                List.of(pair(sensorEquipment, equipmentDbid)), fk.id(), DeclaredCardinality.MANY_TO_ONE);
        assertThat(codes(otherColumns)).containsExactly(Code.FOREIGN_KEY_LOST);

        m.missing(fk);
        RelationshipValidator.Evaluation lost = validator.evaluate(Metadata.POSTGRESQL, m.objects,
                RelationshipOrigin.DECLARED_FK, sensors.id(), equipment.id(), pairs, fk.id(),
                DeclaredCardinality.MANY_TO_ONE);
        assertThat(codes(lost)).as("columns are still there, but the physical backing is gone")
                .containsExactly(Code.FOREIGN_KEY_LOST);
        assertThat(lost.evidence().foreignKey()).isEqualTo(ForeignKeyEvidence.LOST);
    }

    @Test
    void objectsOfAnotherTenantOrDataSourceAreNotFound() {
        Metadata other = new Metadata();
        PhysicalObject foreignTable = other.table("plant", "equipment", "id");
        PhysicalObject foreignColumn = other.column(foreignTable, "dbid", "int8");
        RelationshipValidator.Evaluation evaluation = validator.evaluate(Metadata.POSTGRESQL, m.objects,
                RelationshipOrigin.SEMANTIC, measurements.id(), foreignTable.id(),
                List.of(pair(measurementDbid, foreignColumn)), null, DeclaredCardinality.MANY_TO_ONE);
        assertThat(codes(evaluation)).containsExactly(Code.REFERENCE_NOT_FOUND, Code.REFERENCE_NOT_FOUND);
    }
}
