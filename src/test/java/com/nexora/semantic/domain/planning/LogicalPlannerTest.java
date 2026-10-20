package com.nexora.semantic.domain.planning;

import com.nexora.semantic.domain.catalog.Aggregation;
import com.nexora.semantic.domain.catalog.DefinitionKind;
import com.nexora.semantic.domain.catalog.DefinitionStatus;
import com.nexora.semantic.domain.catalog.DimensionType;
import com.nexora.semantic.domain.catalog.TimeGranularity;
import com.nexora.semantic.domain.catalog.TypeCategory;
import com.nexora.semantic.domain.planning.LogicalPlan.AggregationSafety;
import com.nexora.semantic.domain.planning.LogicalPlan.Navigation;
import com.nexora.semantic.domain.planning.LogicalPlan.OperationKind;
import com.nexora.semantic.domain.planning.LogicalPlan.RowPreservation;
import com.nexora.semantic.domain.planning.PlanDiagnostic.Code;
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
import com.nexora.semantic.domain.resolution.FilterOperator;
import com.nexora.semantic.domain.resolution.FilterValue;
import com.nexora.semantic.domain.resolution.ResolutionRequest;
import com.nexora.semantic.domain.resolution.SemanticResolution;
import com.nexora.semantic.domain.resolution.SemanticResolution.FilterStage;
import com.nexora.semantic.domain.resolution.SemanticResolution.ResolvedConcept;
import com.nexora.semantic.domain.resolution.SemanticResolution.ResolvedDimension;
import com.nexora.semantic.domain.resolution.SemanticResolution.ResolvedFilter;
import com.nexora.semantic.domain.resolution.SemanticResolution.ResolvedMetric;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DataSourceId;
import com.nexora.semantic.domain.source.NaturalKey;
import com.nexora.semantic.domain.source.ObjectAttributes;
import com.nexora.semantic.domain.source.PhysicalObject;
import com.nexora.semantic.domain.source.PhysicalObjectStatus;
import com.nexora.semantic.domain.source.PhysicalObjectType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** S4.2 planning rules over an in-memory catalog: routes, grain, fan-out, JOIN types and filters. */
class LogicalPlannerTest {

    private static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");
    private static final TenantId TENANT = TenantId.newId();
    private static final DataSourceId SOURCE = DataSourceId.newId();
    private static final UUID SNAPSHOT = UUID.randomUUID();

    private final LogicalPlanner planner = new LogicalPlanner();
    private final Map<UUID, PhysicalObject> objects = new HashMap<>();
    private final List<LogicalPlanner.Edge> edges = new ArrayList<>();
    private final Map<UUID, ResolvedConcept> concepts = new HashMap<>();

    // plant.measurements (PK id) — plant.equipment (PK id; dbid not unique) — finance.transactions — docs.documents
    private final PhysicalObject measurements = table("plant", "measurements", "id");
    private final PhysicalObject mId = column(measurements, "id", "int8", false);
    private final PhysicalObject mDbid = column(measurements, "equipment_dbid", "int8", false);
    private final PhysicalObject mValue = column(measurements, "value", "numeric", true);
    private final PhysicalObject mNote = column(measurements, "note", "varchar", true);
    private final PhysicalObject mAt = column(measurements, "measured_at", "timestamptz", false);
    private final PhysicalObject equipment = table("plant", "equipment", "id");
    private final PhysicalObject eId = column(equipment, "id", "int8", false);
    private final PhysicalObject eDbid = column(equipment, "dbid", "int8", false);
    private final PhysicalObject eName = column(equipment, "name", "varchar", true);
    private final PhysicalObject transactions = table("finance", "transactions", "id");
    private final PhysicalObject tCompany = column(transactions, "company_code", "bpchar", false);
    private final PhysicalObject tNumber = column(transactions, "document_number", "varchar", false);
    private final PhysicalObject tAmount = column(transactions, "amount", "numeric", false);
    private final PhysicalObject documents = table("docs", "documents", "company_code", "number");
    private final PhysicalObject dCompany = column(documents, "company_code", "bpchar", false);
    private final PhysicalObject dNumber = column(documents, "number", "varchar", false);
    private final PhysicalObject dTitle = column(documents, "title", "varchar", true);

    private final ResolvedConcept lecturas = concept(measurements);
    private final ResolvedConcept equipos = concept(equipment);
    private final ResolvedConcept movimientos = concept(transactions);
    private final ResolvedConcept documentos = concept(documents);

    private final ResolvedMetric sumaValor = metric(lecturas, Aggregation.SUM, mValue);
    private final ResolvedMetric numLecturas = metric(lecturas, Aggregation.COUNT, null);
    private final ResolvedMetric numEquipos = metric(equipos, Aggregation.COUNT, null);
    private final ResolvedMetric importe = metric(movimientos, Aggregation.SUM, tAmount);
    private final ResolvedDimension nota = categorical(lecturas, mNote);
    private final ResolvedDimension fecha = new ResolvedDimension(UUID.randomUUID(), "fecha", "fecha", 1,
            lecturas.id(), mAt.id(), DimensionType.TEMPORAL, TypeCategory.TIMESTAMP_TZ,
            List.of(TimeGranularity.DAY, TimeGranularity.MONTH), "UTC");
    private final ResolvedDimension nombreEquipo = categorical(equipos, eName);
    private final ResolvedDimension titulo = categorical(documentos, dTitle);

    // --- fixtures ---

    private PhysicalObject table(String schema, String name, String... primaryKey) {
        PhysicalObject table = new PhysicalObject(UUID.randomUUID(), TENANT, SOURCE, PhysicalObjectType.TABLE,
                UUID.randomUUID(), schema, name, null, NaturalKey.relation(schema, name),
                ObjectAttributes.of(Map.of("primaryKey", List.of(primaryKey))), null, "a".repeat(64),
                PhysicalObjectStatus.ACTIVE, SNAPSHOT, SNAPSHOT, null, NOW, NOW);
        objects.put(table.id(), table);
        return table;
    }

    private PhysicalObject column(PhysicalObject table, String name, String type, boolean nullable) {
        PhysicalObject column = new PhysicalObject(UUID.randomUUID(), TENANT, SOURCE, PhysicalObjectType.COLUMN,
                table.id(), table.schemaName(), table.objectName(), name,
                NaturalKey.column(table.schemaName(), table.objectName(), name),
                ObjectAttributes.of(Map.of("dataType", type, "position", 1, "nullable", nullable)), null,
                "b".repeat(64), PhysicalObjectStatus.ACTIVE, SNAPSHOT, SNAPSHOT, null, NOW, NOW);
        objects.put(column.id(), column);
        return column;
    }

    private ResolvedConcept concept(PhysicalObject table) {
        ResolvedConcept concept = new ResolvedConcept(UUID.randomUUID(), table.objectName(), table.objectName(), 1,
                table.id());
        concepts.put(concept.id(), concept);
        return concept;
    }

    private static ResolvedMetric metric(ResolvedConcept concept, Aggregation aggregation, PhysicalObject column) {
        return new ResolvedMetric(UUID.randomUUID(), "m" + aggregation, "m", 1, concept.id(), aggregation,
                column == null ? null : column.id());
    }

    private static ResolvedDimension categorical(ResolvedConcept concept, PhysicalObject column) {
        return new ResolvedDimension(UUID.randomUUID(), column.childName(), column.childName(), 1, concept.id(),
                column.id(), DimensionType.CATEGORICAL, TypeCategory.TEXT, List.of(), null);
    }

    /** Builds a relationship contract with the evidence S3.1 would compute from the tables' primary keys. */
    private RelationshipContract relationship(PhysicalObject source, PhysicalObject target,
                                              DeclaredCardinality cardinality, Set<JoinType> joins, boolean usable,
                                              PhysicalObject... pairColumns) {
        List<ColumnPair> pairs = new ArrayList<>();
        for (int i = 0; i < pairColumns.length; i += 2) {
            pairs.add(new ColumnPair(pairColumns[i].id(), pairColumns[i + 1].id()));
        }
        UniquenessEvidence sourceUnique = unique(source, pairs.stream().map(ColumnPair::sourceColumnId).toList());
        UniquenessEvidence targetUnique = unique(target, pairs.stream().map(ColumnPair::targetColumnId).toList());
        RelationshipContract contract = new RelationshipContract(UUID.randomUUID(), 1,
                usable ? DefinitionStatus.APPROVED : DefinitionStatus.APPROVED, usable, List.of(),
                RelationshipOrigin.SEMANTIC, source.id(), target.id(), pairs, cardinality,
                new RelationshipEvidence(sourceUnique, targetUnique, ForeignKeyEvidence.NOT_DECLARED,
                        CardinalityAssurance.DECLARED_ONLY, risk(targetUnique), risk(sourceUnique)), null, joins);
        edges.add(new LogicalPlanner.Edge(contract, "rel-" + edges.size()));
        return contract;
    }

    private UniquenessEvidence unique(PhysicalObject table, List<UUID> columnIds) {
        Object pk = table.attributes().values().get("primaryKey");
        List<String> names = columnIds.stream().map(objects::get).map(PhysicalObject::childName).sorted().toList();
        return pk instanceof List<?> list && list.stream().map(String::valueOf).sorted().toList().equals(names)
                ? UniquenessEvidence.PRIMARY_KEY : UniquenessEvidence.UNKNOWN;
    }

    private static RowMultiplicationRisk risk(UniquenessEvidence joinedSide) {
        return joinedSide == UniquenessEvidence.PRIMARY_KEY ? RowMultiplicationRisk.NONE_BY_PRIMARY_KEY
                : RowMultiplicationRisk.POSSIBLE;
    }

    private RelationshipContract byId(Set<JoinType> joins) {
        return relationship(measurements, equipment, DeclaredCardinality.MANY_TO_ONE, joins, true, mDbid, eId);
    }

    private RelationshipContract byDbid() {
        return relationship(measurements, equipment, DeclaredCardinality.MANY_TO_ONE,
                Set.of(JoinType.INNER, JoinType.LEFT), true, mDbid, eDbid);
    }

    private SemanticResolution resolution(List<ResolvedMetric> metrics, List<ResolvedDimension> dimensions,
                                          List<ResolvedFilter> filters, List<RelationshipContract> preferred) {
        List<ResolvedConcept> used = new ArrayList<>();
        metrics.forEach(m -> addConcept(used, m.conceptId()));
        dimensions.forEach(d -> addConcept(used, d.conceptId()));
        filters.stream().filter(f -> f.stage() == FilterStage.PRE_AGGREGATION).forEach(f ->
                addConcept(used, concepts.values().stream()
                        .filter(c -> c.relationObjectId().equals(objects.get(f.columnObjectId()).parentId()))
                        .findFirst().orElseThrow().id()));
        return new SemanticResolution(TENANT, SOURCE, SNAPSHOT, metrics, dimensions, List.of(), used, filters,
                List.of(), preferred, "f".repeat(64));
    }

    private void addConcept(List<ResolvedConcept> used, UUID id) {
        if (used.stream().noneMatch(c -> c.id().equals(id))) {
            used.add(concepts.get(id));
        }
    }

    private static PlanRequest request(SemanticResolution resolution,
                                       List<PlanRequest.GranularityChoice> granularities) {
        return new PlanRequest(new ResolutionRequest(resolution.metrics().stream().map(ResolvedMetric::id).toList(),
                resolution.dimensions().stream().map(ResolvedDimension::id).toList(), List.of(),
                resolution.preferredRelationships().stream().map(RelationshipContract::relationshipId).toList()),
                granularities);
    }

    private PlanningOutcome plan(SemanticResolution resolution, List<PlanRequest.GranularityChoice> granularities) {
        return planner.plan(request(resolution, granularities), resolution,
                new LogicalPlanner.Context(objects, edges));
    }

    private LogicalPlan planned(SemanticResolution resolution, PlanRequest.GranularityChoice... granularities) {
        PlanningOutcome outcome = plan(resolution, List.of(granularities));
        assertThat(outcome).isInstanceOf(PlanningOutcome.Planned.class);
        return ((PlanningOutcome.Planned) outcome).plan();
    }

    private List<PlanDiagnostic> rejected(SemanticResolution resolution,
                                          PlanRequest.GranularityChoice... granularities) {
        PlanningOutcome outcome = plan(resolution, List.of(granularities));
        assertThat(outcome).isInstanceOf(PlanningOutcome.Rejected.class);
        return ((PlanningOutcome.Rejected) outcome).diagnostics();
    }

    private static ResolvedFilter pre(ResolvedDimension dimension, FilterOperator operator, String value) {
        return new ResolvedFilter(dimension.id(), DefinitionKind.DIMENSION, FilterStage.PRE_AGGREGATION, operator,
                dimension.valueType(), dimension.columnObjectId(), List.of(new FilterValue.TextValue(value)));
    }

    private static ResolvedFilter post(ResolvedMetric metric, String value) {
        return new ResolvedFilter(metric.id(), DefinitionKind.METRIC, FilterStage.POST_AGGREGATION,
                FilterOperator.GT, TypeCategory.NUMERIC, metric.columnObjectId(),
                List.of(new FilterValue.NumberValue(new BigDecimal(value))));
    }

    // --- single object ---

    @Test
    void plansOneMetricOnOneTableWithoutJoins() {
        LogicalPlan plan = planned(resolution(List.of(sumaValor), List.of(), List.of(), List.of()));

        assertThat(plan.formatVersion()).isEqualTo("nexora-logical-plan/1");
        assertThat(plan.base().objectId()).isEqualTo(measurements.id());
        assertThat(plan.inputGrain().primaryKeyColumns()).containsExactly("id");
        assertThat(plan.inputGrain().rowIdentity()).isEqualTo(UniquenessEvidence.PRIMARY_KEY);
        assertThat(plan.outputGrain()).isEmpty();
        assertThat(plan.joins()).isEmpty();
        assertThat(plan.safety()).isEqualTo(AggregationSafety.SINGLE_OBJECT);
        assertThat(plan.aggregations()).singleElement()
                .satisfies(a -> assertThat(a.columnObjectId()).isEqualTo(mValue.id()));
        assertThat(plan.operations()).extracting(LogicalPlan.PlanOperation::kind)
                .containsExactly(OperationKind.SCAN, OperationKind.GROUP_AND_AGGREGATE);
        assertThat(plan.fingerprint()).matches("[0-9a-f]{64}");
    }

    @Test
    void plansCompatibleMetricsWithDimensionsAndFiltersInLogicalOrder() {
        LogicalPlan plan = planned(resolution(List.of(sumaValor, numLecturas), List.of(fecha, nota),
                        List.of(pre(nota, FilterOperator.EQ, "ok"), post(sumaValor, "10")), List.of()),
                new PlanRequest.GranularityChoice(fecha.id(), TimeGranularity.MONTH));

        assertThat(plan.aggregations()).extracting(LogicalPlan.PlanAggregation::metricId)
                .containsExactly(sumaValor.id(), numLecturas.id());
        assertThat(plan.outputGrain()).extracting(LogicalPlan.GroupingKey::dimensionId)
                .containsExactly(fecha.id(), nota.id());
        assertThat(plan.outputGrain().getFirst().granularity()).isEqualTo(TimeGranularity.MONTH);
        assertThat(plan.outputGrain().getFirst().timeZone()).isEqualTo("UTC");
        assertThat(plan.preAggregationFilters()).singleElement()
                .satisfies(f -> assertThat(f.objectId()).isEqualTo(measurements.id()));
        assertThat(plan.postAggregationFilters()).singleElement()
                .satisfies(f -> assertThat(f.values()).containsExactly("10"));
        assertThat(plan.operations()).extracting(LogicalPlan.PlanOperation::kind).containsExactly(
                OperationKind.SCAN, OperationKind.FILTER_ROWS, OperationKind.GROUP_AND_AGGREGATE,
                OperationKind.FILTER_GROUPS);
    }

    @Test
    void temporalDimensionsNeedAnAllowedGranularity() {
        SemanticResolution resolution = resolution(List.of(numLecturas), List.of(fecha), List.of(), List.of());
        assertThat(rejected(resolution)).extracting(PlanDiagnostic::code).containsExactly(Code.GRANULARITY_REQUIRED);
        assertThat(rejected(resolution, new PlanRequest.GranularityChoice(fecha.id(), TimeGranularity.YEAR)))
                .extracting(PlanDiagnostic::code).contains(Code.GRANULARITY_INVALID);
        assertThat(rejected(resolution, new PlanRequest.GranularityChoice(nota.id(), TimeGranularity.DAY)))
                .extracting(PlanDiagnostic::code).contains(Code.GRANULARITY_INVALID);
    }

    @Test
    void metricsOverDifferentTablesHaveIncompatibleGrains() {
        assertThat(rejected(resolution(List.of(sumaValor, importe), List.of(), List.of(), List.of())))
                .singleElement().satisfies(d -> {
                    assertThat(d.code()).isEqualTo(Code.INCOMPATIBLE_GRAIN);
                    assertThat(d.field()).isEqualTo("metrics[1]");
                });
    }

    // --- joins ---

    @Test
    void joinsToAPrimaryKeyWithAnAuthorizedInnerJoin() {
        RelationshipContract inner = byId(Set.of(JoinType.INNER));
        LogicalPlan plan = planned(resolution(List.of(sumaValor), List.of(nombreEquipo), List.of(), List.of()));

        assertThat(plan.safety()).isEqualTo(AggregationSafety.JOINS_TO_PRIMARY_KEY_ONLY);
        assertThat(plan.joins()).singleElement().satisfies(j -> {
            assertThat(j.relationshipId()).isEqualTo(inner.relationshipId());
            assertThat(j.joinType()).isEqualTo(JoinType.INNER);
            assertThat(j.navigation()).isEqualTo(Navigation.FORWARD);
            assertThat(j.conditions()).containsExactly(new LogicalPlan.JoinCondition(mDbid.id(), eId.id()));
            assertThat(j.joinedSideUniqueness()).isEqualTo(UniquenessEvidence.PRIMARY_KEY);
            assertThat(j.rowPreservation()).isEqualTo(RowPreservation.UNMATCHED_ROWS_EXCLUDED);
            assertThat(j.optional()).isFalse();
        });
        assertThat(plan.relationships()).singleElement()
                .satisfies(r -> assertThat(r.pairs().getFirst().targetNaturalKey()).endsWith("\"id\""));
        assertThat(plan.operations()).extracting(LogicalPlan.PlanOperation::kind)
                .containsExactly(OperationKind.SCAN, OperationKind.JOIN, OperationKind.GROUP_AND_AGGREGATE);
    }

    @Test
    void prefersAnAuthorizedLeftJoinWhichKeepsEveryAggregatedRow() {
        byId(Set.of(JoinType.INNER, JoinType.LEFT));
        LogicalPlan plan = planned(resolution(List.of(numLecturas), List.of(nombreEquipo), List.of(), List.of()));
        assertThat(plan.joins()).singleElement().satisfies(j -> {
            assertThat(j.joinType()).isEqualTo(JoinType.LEFT);
            assertThat(j.rowPreservation()).isEqualTo(RowPreservation.PRESERVED);
            assertThat(j.optional()).isTrue();
        });
    }

    @Test
    void joinsCompositeCrossSchemaPairsThatAreNotIdColumns() {
        RelationshipContract composite = relationship(transactions, documents, DeclaredCardinality.MANY_TO_ONE,
                Set.of(JoinType.INNER), true, tCompany, dCompany, tNumber, dNumber);
        LogicalPlan plan = planned(resolution(List.of(importe), List.of(titulo), List.of(), List.of()));

        assertThat(plan.joins()).singleElement().satisfies(j -> {
            assertThat(j.relationshipId()).isEqualTo(composite.relationshipId());
            assertThat(j.conditions()).containsExactly(new LogicalPlan.JoinCondition(tCompany.id(), dCompany.id()),
                    new LogicalPlan.JoinCondition(tNumber.id(), dNumber.id()));
            assertThat(j.joinedSideUniqueness()).isEqualTo(UniquenessEvidence.PRIMARY_KEY);
        });
        assertThat(plan.physicalObjects()).extracting(LogicalPlan.ObjectSnapshot::naturalKey)
                .contains("\"docs\".\"documents\".\"number\"", "\"finance\".\"transactions\".\"document_number\"");
    }

    @Test
    void approvedButUnusableRelationshipsAreNeverUsed() {
        RelationshipContract unusable = relationship(measurements, equipment, DeclaredCardinality.MANY_TO_ONE,
                Set.of(JoinType.INNER), false, mDbid, eId);
        assertThat(rejected(resolution(List.of(numLecturas), List.of(nombreEquipo), List.of(), List.of())))
                .singleElement().satisfies(d -> {
                    assertThat(d.code()).isEqualTo(Code.RELATIONSHIP_NOT_USABLE);
                    assertThat(d.relationshipIds()).containsExactly(unusable.relationshipId());
                });
    }

    @Test
    void reportsMissingRoutes() {
        assertThat(rejected(resolution(List.of(importe), List.of(nota), List.of(), List.of())))
                .singleElement().satisfies(d -> {
                    assertThat(d.code()).isEqualTo(Code.NO_PATH);
                    assertThat(d.field()).isEqualTo("dimensions[0]");
                });
    }

    // --- ambiguity ---

    @Test
    void neverChoosesBetweenTwoRoutesEvenWhenOnlyOneIsSafe() {
        RelationshipContract dbid = byDbid();
        RelationshipContract id = byId(Set.of(JoinType.INNER));

        List<PlanDiagnostic> ambiguous = rejected(resolution(List.of(numLecturas), List.of(nombreEquipo), List.of(),
                List.of()));
        assertThat(ambiguous).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo(Code.AMBIGUOUS_PATH);
            assertThat(d.alternatives()).containsExactlyInAnyOrder(List.of(dbid.relationshipId()),
                    List.of(id.relationshipId()));
        });

        LogicalPlan chosen = planned(resolution(List.of(numLecturas), List.of(nombreEquipo), List.of(),
                List.of(id)));
        assertThat(chosen.joins()).singleElement()
                .satisfies(j -> assertThat(j.relationshipId()).isEqualTo(id.relationshipId()));
    }

    // --- grain and fan-out ---

    @Test
    void equipmentDbidIsApprovedButAggregatingAfterItIsNotProvenSafe() {
        RelationshipContract dbid = byDbid();
        assertThat(rejected(resolution(List.of(sumaValor), List.of(nombreEquipo), List.of(), List.of(dbid))))
                .singleElement().satisfies(d -> {
                    assertThat(d.code()).isEqualTo(Code.UNIQUENESS_NOT_PROVEN);
                    assertThat(d.relationshipIds()).containsExactly(dbid.relationshipId());
                    assertThat(d.message()).contains("not proven safe");
                });
    }

    @Test
    void aDeclaredOneToOneWithoutEvidenceIsNotProof() {
        relationship(equipment, measurements, DeclaredCardinality.ONE_TO_ONE, Set.of(JoinType.INNER), true,
                eDbid, mDbid);
        assertThat(rejected(resolution(List.of(numLecturas), List.of(nombreEquipo), List.of(), List.of())))
                .extracting(PlanDiagnostic::code).containsExactly(Code.UNIQUENESS_NOT_PROVEN);
    }

    @Test
    void joiningTowardsTheManySideIsFanOut() {
        byId(Set.of(JoinType.INNER, JoinType.LEFT));
        assertThat(rejected(resolution(List.of(numEquipos), List.of(nota), List.of(), List.of())))
                .singleElement().satisfies(d -> assertThat(d.code()).isEqualTo(Code.FANOUT_RISK));
    }

    // --- JOIN types and filters ---

    @Test
    void neverReversesALeftJoinNorPutsAnInnerJoinAfterALeftJoin() {
        relationship(measurements, equipment, DeclaredCardinality.ONE_TO_ONE, Set.of(JoinType.LEFT), true, mId, eId);
        assertThat(rejected(resolution(List.of(numEquipos), List.of(nota), List.of(), List.of())))
                .extracting(PlanDiagnostic::code).containsExactly(Code.JOIN_NOT_AUTHORIZED);
    }

    @Test
    void anInnerJoinAfterALeftJoinIsRejected() {
        relationship(measurements, equipment, DeclaredCardinality.MANY_TO_ONE, Set.of(JoinType.LEFT), true, mDbid,
                eId);
        PhysicalObject sites = table("plant", "sites", "code");
        PhysicalObject sCode = column(sites, "code", "varchar", false);
        PhysicalObject sName = column(sites, "name", "varchar", true);
        PhysicalObject eSite = column(equipment, "site_code", "varchar", true);
        relationship(equipment, sites, DeclaredCardinality.MANY_TO_ONE, Set.of(JoinType.INNER), true, eSite, sCode);
        ResolvedDimension site = categorical(concept(sites), sName);

        assertThat(rejected(resolution(List.of(numLecturas), List.of(site), List.of(), List.of())))
                .extracting(PlanDiagnostic::code).containsExactly(Code.JOIN_NOT_AUTHORIZED);
    }

    @Test
    void aRowFilterOnTheOptionalSideOfALeftJoinIsUnsafe() {
        byId(Set.of(JoinType.LEFT));
        List<PlanDiagnostic> diagnostics = rejected(resolution(List.of(numLecturas), List.of(),
                List.of(pre(nombreEquipo, FilterOperator.EQ, "Bomba")), List.of()));
        assertThat(diagnostics).singleElement().satisfies(d -> {
            assertThat(d.code()).isEqualTo(Code.UNSAFE_FILTER);
            assertThat(d.field()).isEqualTo("filters[0]");
        });

        edges.clear();
        byId(Set.of(JoinType.INNER));
        LogicalPlan inner = planned(resolution(List.of(numLecturas), List.of(),
                List.of(pre(nombreEquipo, FilterOperator.EQ, "Bomba")), List.of()));
        assertThat(inner.preAggregationFilters()).singleElement()
                .satisfies(f -> assertThat(f.objectId()).isEqualTo(equipment.id()));
    }

    // --- limits, determinism, immutability, no SQL ---

    @Test
    void boundsTheRouteSearch() {
        for (int i = 0; i <= LogicalPlanner.MAX_ROUTES; i++) {
            byId(Set.of(JoinType.INNER));
        }
        assertThat(rejected(resolution(List.of(numLecturas), List.of(nombreEquipo), List.of(), List.of())))
                .extracting(PlanDiagnostic::code).containsExactly(Code.COMPLEXITY_LIMIT_EXCEEDED);
    }

    @Test
    void boundsTheNumberOfObjects() {
        List<ResolvedDimension> dimensions = new ArrayList<>();
        for (int i = 0; i < LogicalPlanner.MAX_OBJECTS; i++) {
            PhysicalObject t = table("plant", "t" + i, "id");
            dimensions.add(categorical(concept(t), column(t, "name", "varchar", true)));
        }
        assertThat(rejected(resolution(List.of(numLecturas), dimensions, List.of(), List.of())))
                .extracting(PlanDiagnostic::code).containsExactly(Code.COMPLEXITY_LIMIT_EXCEEDED);
    }

    @Test
    void theFingerprintIsDeterministicAndContentSensitive() {
        RelationshipContract id = byId(Set.of(JoinType.INNER));
        SemanticResolution resolution = resolution(List.of(sumaValor), List.of(nombreEquipo), List.of(),
                List.of(id));
        String first = planned(resolution).fingerprint();
        assertThat(planned(resolution).fingerprint()).isEqualTo(first);
        assertThat(planned(resolution(List.of(sumaValor, numLecturas), List.of(nombreEquipo), List.of(),
                List.of(id))).fingerprint()).isNotEqualTo(first);
    }

    @Test
    void thePlanIsImmutableSelfContainedAndHasNoSql() {
        byId(Set.of(JoinType.INNER));
        LogicalPlan plan = planned(resolution(List.of(sumaValor), List.of(nombreEquipo),
                List.of(pre(nombreEquipo, FilterOperator.EQ, "x' OR 1=1 --")), List.of()));

        assertThatThrownBy(() -> plan.joins().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.definitions().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> plan.joins().getFirst().conditions().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(plan.definitions()).extracting(LogicalPlan.DefinitionSnapshot::kind)
                .contains(DefinitionKind.METRIC, DefinitionKind.DIMENSION, DefinitionKind.CONCEPT);
        assertThat(plan.relationships()).singleElement()
                .satisfies(r -> assertThat(r.targetUniqueness()).isEqualTo(UniquenessEvidence.PRIMARY_KEY));
        assertThat(plan.dependencies()).extracting(PlanDependency::kind).contains(PlanDependency.Kind.DEFINITION,
                PlanDependency.Kind.RELATIONSHIP, PlanDependency.Kind.PHYSICAL_OBJECT);

        assertThat(Arrays.stream(LogicalPlan.class.getRecordComponents()).map(RecordComponent::getName)
                .map(n -> n.toLowerCase(Locale.ROOT))).noneMatch(n -> n.contains("sql"));
        assertThat(plan.toString().toUpperCase(Locale.ROOT)).doesNotContain("SELECT ", " FROM ", " WHERE ");
        assertThat(plan.preAggregationFilters().getFirst().values()).containsExactly("x' OR 1=1 --");
    }
}
