package com.nexora.semantic.domain.resolution;

import com.nexora.semantic.domain.catalog.Aggregation;
import com.nexora.semantic.domain.catalog.DefinitionKey;
import com.nexora.semantic.domain.catalog.DefinitionKind;
import com.nexora.semantic.domain.catalog.DefinitionSpec;
import com.nexora.semantic.domain.catalog.DefinitionStatus;
import com.nexora.semantic.domain.catalog.DefinitionValidator;
import com.nexora.semantic.domain.catalog.DimensionType;
import com.nexora.semantic.domain.catalog.SemanticDefinition;
import com.nexora.semantic.domain.catalog.TypeCategory;
import com.nexora.semantic.domain.relationship.CardinalityAssurance;
import com.nexora.semantic.domain.relationship.ColumnPair;
import com.nexora.semantic.domain.relationship.DeclaredCardinality;
import com.nexora.semantic.domain.relationship.ForeignKeyEvidence;
import com.nexora.semantic.domain.relationship.JoinType;
import com.nexora.semantic.domain.relationship.RelationshipContract;
import com.nexora.semantic.domain.relationship.RelationshipEvidence;
import com.nexora.semantic.domain.relationship.RelationshipOrigin;
import com.nexora.semantic.domain.relationship.RowMultiplicationRisk;
import com.nexora.semantic.domain.relationship.UniquenessEvidence;
import com.nexora.semantic.domain.resolution.ResolutionDiagnostic.Code;
import com.nexora.semantic.domain.resolution.ResolutionRequest.FilterRequest;
import com.nexora.semantic.domain.resolution.SemanticResolution.FilterStage;
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

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S4.1 resolution rules over an in-memory catalog: no database, no SQL, no route. */
class SemanticResolverTest {

    private static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");
    private static final EngineCode POSTGRESQL = new EngineCode("postgresql");
    private static final TenantId TENANT = TenantId.newId();
    private static final DataSourceId SOURCE = DataSourceId.newId();
    private static final UUID SNAPSHOT = UUID.randomUUID();
    private static final PrincipalRef EDITOR = new PrincipalRef("editor", PrincipalType.HUMAN);
    private static final PrincipalRef APPROVER = new PrincipalRef("approver", PrincipalType.HUMAN);

    private final SemanticResolver resolver = new SemanticResolver();
    private final Map<UUID, PhysicalObject> objects = new HashMap<>();
    private final Map<UUID, SemanticDefinition> definitions = new HashMap<>();
    private final Map<UUID, RelationshipContract> relationships = new HashMap<>();

    private final PhysicalObject measurements = table("measurements");
    private final PhysicalObject equipment = table("equipment");
    private final PhysicalObject value = column(measurements, "value", "numeric");
    private final PhysicalObject measuredAt = column(measurements, "measured_at", "timestamptz");
    private final PhysicalObject note = column(measurements, "note", "varchar");
    private final PhysicalObject dbid = column(measurements, "equipment_dbid", "int8");
    private final PhysicalObject equipmentName = column(equipment, "name", "varchar");
    private final PhysicalObject active = column(equipment, "active", "bool");
    private final PhysicalObject installedOn = column(equipment, "installed_on", "date");

    private final SemanticDefinition lecturas = approved(concept("lecturas", measurements));
    private final SemanticDefinition equipos = approved(concept("equipos", equipment));
    private final SemanticDefinition numLecturas = approved(metric("num_lecturas", lecturas, Aggregation.COUNT, null));
    private final SemanticDefinition sumaValor = approved(metric("suma_valor", lecturas, Aggregation.SUM, value));
    private final SemanticDefinition fecha = approved(temporal("fecha", lecturas, measuredAt));
    private final SemanticDefinition nota = approved(categorical("nota", lecturas, note));
    private final SemanticDefinition nombreEquipo = approved(categorical("nombre_equipo", equipos, equipmentName));
    private final SemanticDefinition activo = approved(categorical("activo", equipos, active));
    private final SemanticDefinition instalacion = approved(temporal("instalacion", equipos, installedOn));

    // --- fixtures ---

    private PhysicalObject table(String name) {
        return put(new PhysicalObject(UUID.randomUUID(), TENANT, SOURCE, PhysicalObjectType.TABLE, UUID.randomUUID(),
                "plant", name, null, NaturalKey.relation("plant", name), ObjectAttributes.NONE, null, "f".repeat(64),
                PhysicalObjectStatus.ACTIVE, SNAPSHOT, SNAPSHOT, null, NOW, NOW));
    }

    private PhysicalObject column(PhysicalObject relation, String name, String dataType) {
        return put(new PhysicalObject(UUID.randomUUID(), TENANT, SOURCE, PhysicalObjectType.COLUMN, relation.id(),
                "plant", relation.objectName(), name, NaturalKey.column("plant", relation.objectName(), name),
                ObjectAttributes.of(Map.of("dataType", dataType, "position", 1, "nullable", true)), null,
                "c".repeat(64), PhysicalObjectStatus.ACTIVE, SNAPSHOT, SNAPSHOT, null, NOW, NOW));
    }

    private PhysicalObject put(PhysicalObject object) {
        objects.put(object.id(), object);
        return object;
    }

    private void missing(PhysicalObject object) {
        put(new PhysicalObject(object.id(), TENANT, SOURCE, object.type(), object.parentId(), object.schemaName(),
                object.objectName(), object.childName(), object.naturalKey(), object.attributes(), null,
                object.fingerprint(), PhysicalObjectStatus.MISSING, SNAPSHOT, SNAPSHOT, UUID.randomUUID(), NOW, NOW));
    }

    private static SemanticDefinition create(String key, DefinitionSpec spec) {
        return SemanticDefinition.create(UUID.randomUUID(), TENANT, SOURCE, new DefinitionKey(key), key, null, spec,
                EDITOR, NOW);
    }

    private static SemanticDefinition concept(String key, PhysicalObject relation) {
        return create(key, new DefinitionSpec.ConceptSpec(relation.id()));
    }

    private static SemanticDefinition metric(String key, SemanticDefinition concept, Aggregation aggregation,
                                             PhysicalObject column) {
        return create(key, new DefinitionSpec.MetricSpec(concept.id(), aggregation,
                column == null ? null : column.id()));
    }

    private static SemanticDefinition categorical(String key, SemanticDefinition concept, PhysicalObject column) {
        return create(key, new DefinitionSpec.DimensionSpec(concept.id(), column.id(), DimensionType.CATEGORICAL,
                Set.of(), null));
    }

    private static SemanticDefinition temporal(String key, SemanticDefinition concept, PhysicalObject column) {
        return create(key, new DefinitionSpec.DimensionSpec(concept.id(), column.id(), DimensionType.TEMPORAL,
                EnumSet.allOf(com.nexora.semantic.domain.catalog.TimeGranularity.class),
                "timestamptz".equals(column.attributes().values().get("dataType")) ? ZoneId.of("UTC") : null));
    }

    private SemanticDefinition approved(SemanticDefinition draft) {
        return store(draft.approve(APPROVER, NOW));
    }

    private SemanticDefinition store(SemanticDefinition definition) {
        definitions.put(definition.id(), definition);
        return definition;
    }

    private RelationshipContract relationship(DefinitionStatus status, boolean valid) {
        RelationshipContract contract = new RelationshipContract(UUID.randomUUID(), 1, status,
                status == DefinitionStatus.APPROVED && valid, List.of(), RelationshipOrigin.SEMANTIC,
                measurements.id(), equipment.id(), List.of(new ColumnPair(dbid.id(), UUID.randomUUID())),
                DeclaredCardinality.MANY_TO_ONE, new RelationshipEvidence(UniquenessEvidence.UNKNOWN,
                UniquenessEvidence.UNKNOWN, ForeignKeyEvidence.NOT_DECLARED, CardinalityAssurance.DECLARED_ONLY,
                RowMultiplicationRisk.POSSIBLE, RowMultiplicationRisk.POSSIBLE), null, Set.of(JoinType.INNER));
        relationships.put(contract.relationshipId(), contract);
        return contract;
    }

    private SemanticResolver.Context context() {
        return new SemanticResolver.Context(TENANT, SOURCE, SNAPSHOT,
                new DefinitionValidator.Context(POSTGRESQL, objects, definitions), relationships);
    }

    private ResolutionOutcome resolve(List<SemanticDefinition> metrics, List<SemanticDefinition> dimensions,
                                      List<FilterRequest> filters, List<UUID> preferred) {
        return resolver.resolve(new ResolutionRequest(metrics.stream().map(SemanticDefinition::id).toList(),
                dimensions.stream().map(SemanticDefinition::id).toList(), filters, preferred), context());
    }

    private SemanticResolution resolved(List<SemanticDefinition> metrics, List<SemanticDefinition> dimensions,
                                        List<FilterRequest> filters, List<UUID> preferred) {
        ResolutionOutcome outcome = resolve(metrics, dimensions, filters, preferred);
        assertThat(outcome).isInstanceOf(ResolutionOutcome.Resolved.class);
        return ((ResolutionOutcome.Resolved) outcome).resolution();
    }

    private List<ResolutionDiagnostic> diagnostics(ResolutionOutcome outcome) {
        assertThat(outcome).isInstanceOf(ResolutionOutcome.Unresolvable.class);
        return ((ResolutionOutcome.Unresolvable) outcome).diagnostics();
    }

    private static FilterRequest filter(SemanticDefinition field, FilterOperator operator, FilterLiteral... values) {
        return new FilterRequest(field.id(), operator, List.of(values));
    }

    private static FilterLiteral text(String value) {
        return new FilterLiteral.Text(value);
    }

    private static FilterLiteral number(String value) {
        return new FilterLiteral.Number(new BigDecimal(value));
    }

    // --- definitions ---

    @Test
    void resolvesOneApprovedMetricWithItsConceptAndPhysicalBinding() {
        SemanticResolution resolution = resolved(List.of(sumaValor), List.of(), List.of(), List.of());

        assertThat(resolution.tenantId()).isEqualTo(TENANT);
        assertThat(resolution.dataSourceId()).isEqualTo(SOURCE);
        assertThat(resolution.metadataSnapshotId()).isEqualTo(SNAPSHOT);
        assertThat(resolution.metrics()).singleElement().satisfies(m -> {
            assertThat(m.id()).isEqualTo(sumaValor.id());
            assertThat(m.aggregation()).isEqualTo(Aggregation.SUM);
            assertThat(m.columnObjectId()).isEqualTo(value.id());
            assertThat(m.conceptId()).isEqualTo(lecturas.id());
            assertThat(m.version()).isEqualTo(sumaValor.version());
        });
        assertThat(resolution.concepts()).singleElement()
                .satisfies(c -> assertThat(c.relationObjectId()).isEqualTo(measurements.id()));
        assertThat(resolution.physicalDependencies()).extracting(SemanticResolution.PhysicalDependency::objectId)
                .containsExactly(measurements.id(), value.id());
        assertThat(resolution.fingerprint()).matches("[0-9a-f]{64}");
    }

    @Test
    void resolvesSeveralMetricsAndDimensionsInRequestOrderWithoutAssumingAnyJoin() {
        SemanticResolution resolution = resolved(List.of(sumaValor, numLecturas),
                List.of(nombreEquipo, fecha, nota), List.of(), List.of());

        assertThat(resolution.metrics()).extracting(SemanticResolution.ResolvedMetric::id)
                .containsExactly(sumaValor.id(), numLecturas.id());
        assertThat(resolution.metrics().get(1).columnObjectId()).as("COUNT of rows").isNull();
        assertThat(resolution.dimensions()).extracting(SemanticResolution.ResolvedDimension::id)
                .containsExactly(nombreEquipo.id(), fecha.id(), nota.id());
        assertThat(resolution.dimensions().get(1).valueType()).isEqualTo(TypeCategory.TIMESTAMP_TZ);
        assertThat(resolution.dimensions().get(1).timeZone()).isEqualTo("UTC");
        assertThat(resolution.concepts()).extracting(SemanticResolution.ResolvedConcept::id)
                .containsExactly(lecturas.id(), equipos.id());
        assertThat(resolution.preferredRelationships())
                .as("two concepts on two tables: S4.1 reports both, it does not choose how to join them")
                .isEmpty();
    }

    @Test
    void rejectsDraftDeactivatedAndInvalidatedDefinitionsDistinctly() {
        SemanticDefinition draft = store(metric("borrador", lecturas, Aggregation.COUNT, null));
        SemanticDefinition deactivated = store(approved(metric("baja", lecturas, Aggregation.COUNT, null))
                .deactivate(APPROVER, "Ya no se usa", NOW));
        SemanticDefinition invalidated = store(approved(metric("rota", lecturas, Aggregation.SUM, value))
                .invalidate(SemanticDefinition.IMPORT_INVALIDATION_PREFIX + ": REFERENCE_MISSING", APPROVER, NOW));
        missing(value);

        List<ResolutionDiagnostic> diagnostics = diagnostics(resolve(List.of(draft, deactivated, invalidated),
                List.of(), List.of(), List.of()));

        assertThat(diagnostics).extracting(ResolutionDiagnostic::code).containsExactly(
                Code.DEFINITION_NOT_APPROVED, Code.DEFINITION_NOT_APPROVED, Code.DEFINITION_INVALIDATED);
        assertThat(diagnostics).extracting(ResolutionDiagnostic::field)
                .containsExactly("metrics[0]", "metrics[1]", "metrics[2]");
        assertThat(diagnostics.get(2).causes()).containsExactly("REFERENCE_MISSING");
    }

    @Test
    void approvedDefinitionsWithMissingObjectsOrColumnsAreNotUsable() {
        missing(value);
        List<ResolutionDiagnostic> column = diagnostics(resolve(List.of(sumaValor), List.of(), List.of(), List.of()));
        assertThat(column).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo(Code.PHYSICAL_BINDING_INVALID);
            assertThat(d.causes()).containsExactly("REFERENCE_MISSING");
        });

        missing(equipment);
        List<ResolutionDiagnostic> table = diagnostics(resolve(List.of(numLecturas), List.of(nombreEquipo),
                List.of(), List.of()));
        assertThat(table).singleElement().satisfies(d -> {
            assertThat(d.field()).isEqualTo("dimensions[0]");
            assertThat(d.code()).as("the concept's table is missing").isEqualTo(Code.CONCEPT_NOT_USABLE);
        });
    }

    @Test
    void unknownWrongKindAndForeignIdsAreNotFoundWithoutDisclosure() {
        SemanticDefinition otherSource = SemanticDefinition.create(UUID.randomUUID(), TENANT, DataSourceId.newId(),
                new DefinitionKey("ajena"), "ajena", null, new DefinitionSpec.MetricSpec(lecturas.id(),
                        Aggregation.COUNT, null), EDITOR, NOW).approve(APPROVER, NOW);
        store(otherSource);
        ResolutionOutcome outcome = resolver.resolve(new ResolutionRequest(
                List.of(UUID.randomUUID(), nota.id(), otherSource.id()), List.of(numLecturas.id(), lecturas.id()),
                List.of(), List.of()), context());

        assertThat(diagnostics(outcome)).extracting(ResolutionDiagnostic::code).containsExactly(
                Code.METRIC_NOT_FOUND, Code.METRIC_NOT_FOUND, Code.METRIC_NOT_FOUND, Code.DIMENSION_NOT_FOUND,
                Code.DIMENSION_NOT_FOUND);
        assertThat(diagnostics(outcome)).allSatisfy(d -> assertThat(d.message()).doesNotContain("suma", "ajena"));
    }

    @Test
    void requiresMetricsAndRejectsDuplicates() {
        assertThat(diagnostics(resolve(List.of(), List.of(fecha), List.of(), List.of())))
                .extracting(ResolutionDiagnostic::code).containsExactly(Code.NO_METRICS);

        RelationshipContract usable = relationship(DefinitionStatus.APPROVED, true);
        List<ResolutionDiagnostic> duplicates = diagnostics(resolve(List.of(sumaValor, sumaValor),
                List.of(fecha, fecha), List.of(filter(nota, FilterOperator.EQ, text("a")),
                        filter(nota, FilterOperator.EQ, text("a"))),
                List.of(usable.relationshipId(), usable.relationshipId())));
        assertThat(duplicates).extracting(ResolutionDiagnostic::code).containsOnly(Code.DUPLICATE_REFERENCE);
        assertThat(duplicates).extracting(ResolutionDiagnostic::field).containsExactly("metrics[1]", "dimensions[1]",
                "preferredRelationships[1]", "filters[1]");
    }

    // --- filters ---

    @Test
    void typesValidFiltersAndPlacesThemBeforeOrAfterAggregation() {
        SemanticResolution resolution = resolved(List.of(sumaValor), List.of(fecha), List.of(
                filter(fecha, FilterOperator.GTE, text("2026-01-01T00:00:00+02:00")),
                filter(nota, FilterOperator.IN, text("ok"), text("revisar")),
                filter(activo, FilterOperator.EQ, new FilterLiteral.Bool(true)),
                filter(instalacion, FilterOperator.LT, text("2020-06-30")),
                filter(nombreEquipo, FilterOperator.IS_NOT_NULL),
                filter(sumaValor, FilterOperator.GT, number("100.50"))), List.of());

        List<SemanticResolution.ResolvedFilter> filters = resolution.filters();
        assertThat(filters).extracting(SemanticResolution.ResolvedFilter::stage).containsExactly(
                FilterStage.PRE_AGGREGATION, FilterStage.PRE_AGGREGATION, FilterStage.PRE_AGGREGATION,
                FilterStage.PRE_AGGREGATION, FilterStage.PRE_AGGREGATION, FilterStage.POST_AGGREGATION);
        assertThat(filters.get(0).values()).singleElement().extracting(FilterValue::canonical)
                .as("instants are normalized to UTC").isEqualTo("2025-12-31T22:00Z");
        assertThat(filters.get(0).columnObjectId()).isEqualTo(measuredAt.id());
        assertThat(filters.get(1).valueType()).isEqualTo(TypeCategory.TEXT);
        assertThat(filters.get(3).values().getFirst()).isInstanceOf(FilterValue.DateValue.class);
        assertThat(filters.get(4).values()).isEmpty();
        assertThat(filters.get(5).definitionKind()).isEqualTo(DefinitionKind.METRIC);
        assertThat(filters.get(5).values().getFirst().canonical()).isEqualTo("100.5");
        assertThat(resolution.physicalDependencies()).extracting(SemanticResolution.PhysicalDependency::objectId)
                .as("filtered columns are dependencies too").contains(note.id(), active.id(), installedOn.id());
        assertThat(resolution.concepts()).extracting(SemanticResolution.ResolvedConcept::id)
                .containsExactly(lecturas.id(), equipos.id());
    }

    @Test
    void rejectsValuesOfTheWrongType() {
        List<ResolutionDiagnostic> diagnostics = diagnostics(resolve(List.of(sumaValor), List.of(), List.of(
                filter(fecha, FilterOperator.GT, text("ayer")),
                filter(instalacion, FilterOperator.EQ, number("20200101")),
                filter(activo, FilterOperator.EQ, text("true")),
                filter(sumaValor, FilterOperator.GT, text("100")),
                filter(nota, FilterOperator.EQ, new FilterLiteral.Null()),
                filter(nota, FilterOperator.EQ),
                filter(nota, FilterOperator.IS_NULL, text("x")),
                filter(nota, FilterOperator.IN, text("a"), text("a")),
                filter(fecha, FilterOperator.GT, text("2026-01-01T00:00:00"))), List.of()));

        assertThat(diagnostics).extracting(ResolutionDiagnostic::code).containsExactly(Code.FILTER_INVALID,
                Code.FILTER_INVALID, Code.FILTER_INVALID, Code.FILTER_INVALID, Code.FILTER_INVALID,
                Code.FILTER_INVALID, Code.FILTER_INVALID, Code.DUPLICATE_REFERENCE, Code.FILTER_INVALID);
        assertThat(diagnostics.get(0).field()).isEqualTo("filters[0].values[0]");
        assertThat(diagnostics).allSatisfy(d -> assertThat(d.message()).doesNotContain("ayer"));
    }

    @Test
    void rejectsOperatorsThatCannotBeValidatedForTheType() {
        List<ResolutionDiagnostic> diagnostics = diagnostics(resolve(List.of(sumaValor), List.of(), List.of(
                filter(nota, FilterOperator.GT, text("m")),
                filter(activo, FilterOperator.IN, new FilterLiteral.Bool(true)),
                filter(sumaValor, FilterOperator.IN, number("1")),
                filter(sumaValor, FilterOperator.IS_NULL)), List.of()));

        assertThat(diagnostics).extracting(ResolutionDiagnostic::code).containsOnly(Code.OPERATOR_INCOMPATIBLE);
        assertThat(diagnostics).hasSize(4);
        assertThat(diagnostics.getFirst().field()).isEqualTo("filters[0].operator");
    }

    @Test
    void filtersOnlyReferenceUsableFieldsWhoseStageIsDetermined() {
        SemanticDefinition draftDimension = store(categorical("borrador", lecturas, note));
        List<ResolutionDiagnostic> diagnostics = diagnostics(resolve(List.of(numLecturas), List.of(), List.of(
                filter(lecturas, FilterOperator.IS_NULL),
                new FilterRequest(UUID.randomUUID(), FilterOperator.IS_NULL, List.of()),
                filter(draftDimension, FilterOperator.IS_NULL),
                filter(sumaValor, FilterOperator.GT, number("1"))), List.of()));

        assertThat(diagnostics).extracting(ResolutionDiagnostic::code).containsExactly(Code.FILTER_FIELD_NOT_FOUND,
                Code.FILTER_FIELD_NOT_FOUND, Code.DEFINITION_NOT_APPROVED, Code.FILTER_STAGE_UNDETERMINED);
        assertThat(diagnostics).extracting(ResolutionDiagnostic::field).containsExactly("filters[0].definitionId",
                "filters[1].definitionId", "filters[2].definitionId", "filters[3].definitionId");
    }

    // --- preferred relationships ---

    @Test
    void preferredRelationshipsAreValidatedButNeverSelected() {
        RelationshipContract byDbid = relationship(DefinitionStatus.APPROVED, true);
        RelationshipContract byId = relationship(DefinitionStatus.APPROVED, true);

        SemanticResolution resolution = resolved(List.of(numLecturas), List.of(nombreEquipo), List.of(),
                List.of(byId.relationshipId(), byDbid.relationshipId()));
        assertThat(resolution.preferredRelationships()).extracting(RelationshipContract::relationshipId)
                .as("both ambiguous candidates are echoed in request order; none is chosen")
                .containsExactly(byId.relationshipId(), byDbid.relationshipId());

        RelationshipContract draft = relationship(DefinitionStatus.DRAFT, true);
        RelationshipContract invalid = relationship(DefinitionStatus.APPROVED, false);
        List<ResolutionDiagnostic> diagnostics = diagnostics(resolve(List.of(numLecturas), List.of(), List.of(),
                List.of(draft.relationshipId(), invalid.relationshipId(), UUID.randomUUID())));
        assertThat(diagnostics).extracting(ResolutionDiagnostic::code).containsExactly(
                Code.PREFERRED_RELATIONSHIP_NOT_USABLE, Code.PREFERRED_RELATIONSHIP_NOT_USABLE,
                Code.PREFERRED_RELATIONSHIP_NOT_FOUND);
        assertThat(diagnostics.getFirst().causes()).contains("STATUS_DRAFT");
    }

    // --- result ---

    @Test
    void theResultCarriesNoSqlJoinOrRoute() {
        List<String> components = Arrays.stream(SemanticResolution.class.getRecordComponents())
                .map(RecordComponent::getName).map(n -> n.toLowerCase(Locale.ROOT)).toList();
        assertThat(components).noneMatch(n -> n.contains("sql") || n.contains("join") || n.contains("route")
                || n.contains("path") || n.contains("plan"));
        SemanticResolution resolution = resolved(List.of(sumaValor), List.of(nota),
                List.of(filter(nota, FilterOperator.EQ, text("x' OR 1=1 --"))), List.of());
        assertThat(resolution.filters().getFirst().values().getFirst())
                .as("text is a typed value, kept verbatim and never interpreted")
                .isEqualTo(new FilterValue.TextValue("x' OR 1=1 --"));
        assertThat(resolution.toString().toUpperCase(Locale.ROOT)).doesNotContain("SELECT ", " JOIN ", " WHERE ");
    }

    @Test
    void theResultIsImmutable() {
        SemanticResolution resolution = resolved(List.of(sumaValor), List.of(fecha),
                List.of(filter(nota, FilterOperator.IN, text("a"), text("b"))), List.of());
        assertThatThrownBy(() -> resolution.metrics().add(null)).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> resolution.dimensions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> resolution.filters().getFirst().values().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> resolution.dimensions().getFirst().granularities().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> resolution.physicalDependencies().removeFirst())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theFingerprintIsDeterministicAndCapturesEveryRevision() {
        List<FilterRequest> filters = List.of(filter(nota, FilterOperator.EQ, text("ok")));
        String first = resolved(List.of(sumaValor), List.of(fecha), filters, List.of()).fingerprint();
        assertThat(resolved(List.of(sumaValor), List.of(fecha), filters, List.of()).fingerprint()).isEqualTo(first);

        assertThat(resolved(List.of(sumaValor), List.of(fecha), List.of(filter(nota, FilterOperator.EQ, text("ko"))),
                List.of()).fingerprint()).as("another filter value").isNotEqualTo(first);

        SemanticDefinition d = sumaValor;
        store(new SemanticDefinition(d.id(), d.tenantId(), d.dataSourceId(), d.key(), d.name(), d.description(),
                d.spec(), d.status(), d.origin(), d.createdBy(), d.createdAt(), d.updatedBy(), d.updatedAt(),
                d.approvedBy(), d.approvedAt(), d.inactiveReason(), d.version() + 2));
        assertThat(resolved(List.of(sumaValor), List.of(fecha), filters, List.of()).fingerprint())
                .as("a new revision of the metric").isNotEqualTo(first);
    }
}
