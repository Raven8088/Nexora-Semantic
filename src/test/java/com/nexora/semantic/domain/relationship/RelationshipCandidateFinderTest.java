package com.nexora.semantic.domain.relationship;

import com.nexora.semantic.domain.catalog.DefinitionIssue.Code;
import com.nexora.semantic.domain.relationship.RelationshipCandidate.SignalCode;
import com.nexora.semantic.domain.source.PhysicalObject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Foreign key resolution by exact natural key and deterministic, bounded, explainable suggestions. */
class RelationshipCandidateFinderTest {

    private final Metadata m = new Metadata();
    private final RelationshipCandidateFinder finder = new RelationshipCandidateFinder();

    private final PhysicalObject equipment = m.table("plant", "equipment", "id");
    private final PhysicalObject equipmentId = m.column(equipment, "id", "int8");
    private final PhysicalObject equipmentDbid = m.column(equipment, "dbid", "int8");
    private final PhysicalObject measurements = m.table("plant", "measurements", "id");
    private final PhysicalObject measurementId = m.column(measurements, "id", "int8");
    private final PhysicalObject measurementDbid = m.column(measurements, "equipment_dbid", "int8",
            "Equipment that produced the reading");
    private final PhysicalObject sensors = m.table("plant", "sensors", "id");
    private final PhysicalObject sensorId = m.column(sensors, "id", "int8");
    private final PhysicalObject sensorEquipment = m.column(sensors, "equipment_id", "int8");
    private final PhysicalObject vendorCode = m.column(sensors, "vendor_code", "varchar");
    private final PhysicalObject fk = m.foreignKey(sensors, "sensors_equipment_fk", List.of("equipment_id"),
            "plant", "equipment", List.of("id"));
    private final PhysicalObject notImported = m.foreignKey(sensors, "sensors_vendor_fk", List.of("vendor_code"),
            "procurement", "vendors", List.of("code"));
    private final PhysicalObject wrongCase = m.foreignKey(sensors, "sensors_case_fk", List.of("equipment_id"),
            "plant", "Equipment", List.of("id"));

    private RelationshipCandidateFinder.Result find(Set<String> catalogued, int limit) {
        return finder.find(Metadata.POSTGRESQL, m.objects.values(), catalogued, null, limit);
    }

    @Test
    void foreignKeysResolveByExactNaturalKeyAndUnresolvableOnesAreReportedNotGuessed() {
        ForeignKeyResolver resolver = new ForeignKeyResolver(m.objects.values());
        ForeignKeyResolver.Resolution resolved = resolver.resolve(fk.id());
        assertThat(resolved.resolved()).isTrue();
        assertThat(resolved.sourceObjectId()).isEqualTo(sensors.id());
        assertThat(resolved.targetObjectId()).isEqualTo(equipment.id());
        assertThat(resolved.pairs()).containsExactly(new ColumnPair(sensorEquipment.id(), equipmentId.id()));

        ForeignKeyResolver.Resolution schemaNotImported = resolver.resolve(notImported.id());
        assertThat(schemaNotImported.resolved()).isFalse();
        assertThat(schemaNotImported.issue().code()).isEqualTo(Code.REFERENCE_UNRESOLVABLE);
        assertThat(schemaNotImported.issue().field()).isEqualTo("foreignKey.target");

        ForeignKeyResolver.Resolution caseMismatch = resolver.resolve(wrongCase.id());
        assertThat(caseMismatch.resolved()).as("\"Equipment\" is not \"equipment\"").isFalse();
        assertThat(caseMismatch.pairs()).isEmpty();
    }

    @Test
    void suggestsForeignKeysAndBusinessIdentifiersButNeverAssumesIdColumns() {
        RelationshipCandidateFinder.Result result = find(Set.of(), 50);

        RelationshipCandidate fromFk = result.candidates().getFirst();
        assertThat(fromFk.suggestedOrigin()).isEqualTo(RelationshipOrigin.DECLARED_FK);
        assertThat(fromFk.foreignKeyId()).isEqualTo(fk.id());
        assertThat(fromFk.classification()).isEqualTo(RelationshipCandidate.Classification.STRONG);
        assertThat(fromFk.targetUniqueness()).isEqualTo(UniquenessEvidence.PRIMARY_KEY);

        RelationshipCandidate dbid = result.candidates().stream()
                .filter(c -> c.pairs().getFirst().sourceColumnId().equals(measurementDbid.id()))
                .findFirst().orElseThrow();
        assertThat(dbid.pairs().getFirst().targetColumnId())
                .as("equipment_dbid points to equipment.dbid, not to equipment.id")
                .isEqualTo(equipmentDbid.id());
        assertThat(dbid.suggestedOrigin()).isEqualTo(RelationshipOrigin.SEMANTIC);
        assertThat(dbid.targetUniqueness()).isEqualTo(UniquenessEvidence.UNKNOWN);
        assertThat(dbid.signals()).extracting(RelationshipCandidate.Signal::code).containsExactly(
                SignalCode.COLUMN_NAME_REFERENCES_TABLE, SignalCode.COMPATIBLE_TYPES,
                SignalCode.TARGET_BUSINESS_IDENTIFIER, SignalCode.COMMENT_MENTIONS_TABLE);
        assertThat(dbid.heuristicScore()).isBetween(0.0, 0.79);

        assertThat(result.candidates()).noneMatch(c -> c.pairs().stream().anyMatch(p ->
                        p.sourceColumnId().equals(measurementId.id()) || p.sourceColumnId().equals(sensorId.id())))
                .as("columns named id are never matched by name alone");
        assertThat(result.unresolvedForeignKeys()).extracting(u -> u.foreignKeyId())
                .containsExactlyInAnyOrder(notImported.id(), wrongCase.id());
        assertThat(result.candidates()).noneMatch(c -> c.pairs().getFirst().sourceColumnId().equals(vendorCode.id()));
    }

    @Test
    void neverSuggestsBetweenIncompatibleTypes() {
        PhysicalObject logs = m.table("plant", "logs", "id");
        PhysicalObject textDbid = m.column(logs, "equipment_dbid", "text");
        assertThat(find(Set.of(), 50).candidates())
                .noneMatch(c -> c.pairs().getFirst().sourceColumnId().equals(textDbid.id()));
    }

    @Test
    void omitsAlreadyCatalogedRelationshipsAndRespectsLimits() {
        String fkSignature = RelationshipSignature.of(sensors.id(), equipment.id(),
                List.of(new ColumnPair(sensorEquipment.id(), equipmentId.id())));
        RelationshipCandidateFinder.Result result = find(Set.of(fkSignature), 50);
        assertThat(result.alreadyCataloged()).isEqualTo(1);
        assertThat(result.candidates()).noneMatch(c -> fk.id().equals(c.foreignKeyId()));

        RelationshipCandidateFinder.Result limited = find(Set.of(), 1);
        assertThat(limited.candidates()).hasSize(1);
        assertThat(limited.truncated()).isTrue();
        assertThat(find(Set.of(), 10_000).candidates()).hasSizeLessThanOrEqualTo(RelationshipCandidateFinder.MAX_RESULTS);
    }

    @Test
    void resultsAreDeterministic() {
        assertThat(find(Set.of(), 50)).isEqualTo(find(Set.of(), 50));
    }
}
