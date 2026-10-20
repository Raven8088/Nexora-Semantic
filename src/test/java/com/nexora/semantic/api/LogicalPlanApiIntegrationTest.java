package com.nexora.semantic.api;

import com.nexora.semantic.domain.tenant.Tenant;
import com.nexora.semantic.support.IntegrationTest;
import com.nexora.semantic.support.SyntheticCatalog;
import com.nexora.semantic.support.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static com.nexora.semantic.support.DiscoveryFixtures.ALL_SCHEMAS;
import static com.nexora.semantic.support.DiscoveryFixtures.envelope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S4.2 end to end over PostgreSQL with the synthetic plant/finance/docs metadata: the catalog and relationships are
 * built and approved through the real API, plans are created by a REPORTS_CLIENT service account (reusing S4.1),
 * persisted immutably, read back and checked for validity after catalog and metadata changes.
 */
class LogicalPlanApiIntegrationTest extends IntegrationTest {

    private static final String MEASUREMENTS = "\"plant\".\"measurements\"";
    private static final String EQUIPMENT = "\"plant\".\"equipment\"";
    private static final String SENSORS = "\"plant\".\"sensors\"";
    private static final String TRANSACTIONS = "\"finance\".\"transactions\"";
    private static final String DOCUMENTS = "\"docs\".\"documents\"";

    private Tenant tenant;
    private String steward;
    private String editor;
    private String approver;
    private String reports;
    private String plant;
    private Map<String, String> objects;
    private String lecturas;
    private String numLecturas;
    private String sumaValor;
    private String numEquipos;
    private String numSensores;
    private String importe;
    private String fecha;
    private String nota;
    private String nombreEquipo;
    private String titulo;

    @BeforeEach
    void setUp() throws Exception {
        tenant = newTenant();
        steward = TestJwt.human(tenant, "STEWARD").bearer();
        editor = TestJwt.human(tenant, "EDITOR").bearer();
        approver = TestJwt.human(tenant, "APPROVER").bearer();
        reports = TestJwt.principal("SERVICE", tenant.id().toString(), "REPORTS_CLIENT").bearer();
        plant = createDataSource(steward);
        importDocument(plant, SyntheticCatalog.standard().envelope());
        objects = objectIds(plant);

        lecturas = approvedDefinition(concept("lecturas", MEASUREMENTS));
        String equipos = approvedDefinition(concept("equipos", EQUIPMENT));
        String sensores = approvedDefinition(concept("sensores", SENSORS));
        String movimientos = approvedDefinition(concept("movimientos", TRANSACTIONS));
        String documentos = approvedDefinition(concept("documentos", DOCUMENTS));
        numLecturas = approvedDefinition(metric("num_lecturas", lecturas, "COUNT", null));
        sumaValor = approvedDefinition(metric("suma_valor", lecturas, "SUM", column(MEASUREMENTS, "value")));
        numEquipos = approvedDefinition(metric("num_equipos", equipos, "COUNT", null));
        numSensores = approvedDefinition(metric("num_sensores", sensores, "COUNT", null));
        importe = approvedDefinition(metric("importe", movimientos, "SUM", column(TRANSACTIONS, "amount")));
        fecha = approvedDefinition(dimension("fecha", lecturas, column(MEASUREMENTS, "measured_at"), "TEMPORAL"));
        nota = approvedDefinition(dimension("nota", lecturas, column(MEASUREMENTS, "note"), "CATEGORICAL"));
        nombreEquipo = approvedDefinition(dimension("nombre_equipo", equipos, column(EQUIPMENT, "name"),
                "CATEGORICAL"));
        titulo = approvedDefinition(dimension("titulo", documentos, column(DOCUMENTS, "title"), "CATEGORICAL"));
    }

    // --- helpers ---

    private String createDataSource(String token) throws Exception {
        return body(mvc.perform(post("/api/v1/data-sources").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("key", unique("erp"), "name", "ERP", "engine", "postgresql",
                                "databaseName", "erp"))))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
    }

    private void importDocument(String dsId, String document) throws Exception {
        mvc.perform(post("/api/v1/data-sources/" + dsId + "/snapshots").header(HttpHeaders.AUTHORIZATION, steward)
                        .contentType(MediaType.APPLICATION_JSON).content(document))
                .andExpect(status().isCreated());
    }

    private Map<String, String> objectIds(String dsId) throws Exception {
        Map<String, String> ids = new HashMap<>();
        body(mvc.perform(get("/api/v1/data-sources/" + dsId + "/objects?size=200")
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.human(tenant, "VIEWER").bearer())).andReturn())
                .get("items").forEach(i -> ids.put(i.get("naturalKey").asString(), i.get("id").asString()));
        return ids;
    }

    private String column(String relation, String column) {
        return objects.get(relation + ".\"" + column + "\"");
    }

    private Map<String, Object> concept(String key, String relation) {
        return Map.of("kind", "CONCEPT", "key", key, "name", key, "concept", Map.of("relationId", objects.get(relation)));
    }

    private static Map<String, Object> metric(String key, String conceptId, String aggregation, String columnId) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("conceptId", conceptId);
        block.put("aggregation", aggregation);
        if (columnId != null) {
            block.put("columnId", columnId);
        }
        return Map.of("kind", "METRIC", "key", key, "name", key, "metric", block);
    }

    private static Map<String, Object> dimension(String key, String conceptId, String columnId, String type) {
        return Map.of("kind", "DIMENSION", "key", key, "name", key,
                "dimension", Map.of("conceptId", conceptId, "columnId", columnId, "type", type));
    }

    private String approvedDefinition(Map<String, Object> definition) throws Exception {
        JsonNode created = body(mvc.perform(post("/api/v1/data-sources/" + plant + "/definitions")
                        .header(HttpHeaders.AUTHORIZATION, editor).contentType(MediaType.APPLICATION_JSON)
                        .content(json(definition)))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(post("/api/v1/data-sources/" + plant + "/definitions/" + created.get("id").asString()
                        + "/approve").header(HttpHeaders.AUTHORIZATION, approver).header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());
        return created.get("id").asString();
    }

    /** {@code pairs} alternates source and target column names. */
    private String relationship(String source, String target, String cardinality, List<String> joins,
                                String... pairs) throws Exception {
        List<Map<String, String>> list = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            list.add(Map.of("sourceColumnId", column(source, pairs[i]), "targetColumnId", column(target, pairs[i + 1])));
        }
        JsonNode created = body(mvc.perform(post("/api/v1/data-sources/" + plant + "/relationships")
                        .header(HttpHeaders.AUTHORIZATION, editor).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", source + "->" + target, "sourceObjectId", objects.get(source),
                                "targetObjectId", objects.get(target), "pairs", list,
                                "declaredCardinality", cardinality, "allowedJoinTypes", joins))))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(post("/api/v1/data-sources/" + plant + "/relationships/" + created.get("id").asString()
                        + "/approve").header(HttpHeaders.AUTHORIZATION, approver).header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());
        return created.get("id").asString();
    }

    private String byEquipmentId() throws Exception {
        return relationship(MEASUREMENTS, EQUIPMENT, "MANY_TO_ONE", List.of("INNER", "LEFT"), "equipment_dbid", "id");
    }

    private String byEquipmentDbid() throws Exception {
        return relationship(MEASUREMENTS, EQUIPMENT, "MANY_TO_ONE", List.of("INNER", "LEFT"), "equipment_dbid",
                "dbid");
    }

    private static Map<String, Object> request(List<String> metrics, List<String> dimensions,
                                               List<Map<String, Object>> filters, List<String> preferred,
                                               List<Map<String, Object>> granularities) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metrics", metrics);
        body.put("dimensions", dimensions);
        body.put("filters", filters);
        body.put("preferredRelationships", preferred);
        body.put("timeGranularities", granularities);
        return body;
    }

    private static Map<String, Object> request(List<String> metrics, List<String> dimensions, List<String> preferred) {
        return request(metrics, dimensions, List.of(), preferred, List.of());
    }

    private static Map<String, Object> filter(String definitionId, String operator, Object value) {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("definitionId", definitionId);
        filter.put("operator", operator);
        filter.put(value instanceof List<?> ? "values" : "value", value);
        return filter;
    }

    private ResultActions create(String token, String dsId, Map<String, Object> body) throws Exception {
        return mvc.perform(post("/api/v1/data-sources/" + dsId + "/logical-plans")
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private JsonNode created(Map<String, Object> body) throws Exception {
        return body(create(reports, plant, body).andExpect(status().isCreated()).andReturn());
    }

    private JsonNode rejected(Map<String, Object> body) throws Exception {
        return body(create(reports, plant, body).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("logical_plan_rejected")).andReturn());
    }

    private JsonNode fetch(String token, String dsId, String planId) throws Exception {
        return body(mvc.perform(get("/api/v1/data-sources/" + dsId + "/logical-plans/" + planId)
                .header(HttpHeaders.AUTHORIZATION, token)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode validity(String planId) throws Exception {
        return body(mvc.perform(get("/api/v1/data-sources/" + plant + "/logical-plans/" + planId + "/validity")
                .header(HttpHeaders.AUTHORIZATION, reports)).andExpect(status().isOk()).andReturn());
    }

    private static List<String> codes(JsonNode node, String array) {
        List<String> codes = new ArrayList<>();
        node.get(array).forEach(e -> codes.add(e.get("code").asString()));
        return codes;
    }

    private static List<String> kinds(JsonNode plan) {
        List<String> kinds = new ArrayList<>();
        plan.get("operations").forEach(o -> kinds.add(o.get("kind").asString()));
        return kinds;
    }

    private long count(String sql) {
        return appJdbc.sql(sql).param("t", tenant.id().value()).query(Long.class).single();
    }

    // --- plans ---

    @Test
    void plansMetricsDimensionsAndFiltersOnOneTableAndPersistsTheSnapshot() throws Exception {
        JsonNode response = body(create(reports, plant, request(List.of(sumaValor, numLecturas), List.of(fecha, nota),
                        List.of(filter(nota, "IN", List.of("ok", "revisar")), filter(sumaValor, "GT", 100)),
                        List.of(), List.of(Map.of("dimensionId", fecha, "granularity", "MONTH"))))
                .andExpect(status().isCreated())
                .andExpect(header().exists(HttpHeaders.LOCATION))
                .andReturn());
        JsonNode plan = response.get("plan");

        assertThat(response.get("formatVersion").asString()).isEqualTo("nexora-logical-plan/1");
        assertThat(plan.get("fingerprint").asString()).isEqualTo(response.get("fingerprint").asString());
        assertThat(plan.get("safety").asString()).isEqualTo("SINGLE_OBJECT");
        assertThat(plan.get("joins")).isEmpty();
        assertThat(plan.get("base").get("naturalKey").asString()).isEqualTo(MEASUREMENTS);
        assertThat(plan.get("inputGrain").get("primaryKeyColumns").toString()).isEqualTo("[\"id\"]");
        assertThat(plan.get("outputGrain").get(0).get("granularity").asString()).isEqualTo("MONTH");
        assertThat(plan.get("aggregations")).hasSize(2);
        assertThat(kinds(plan)).containsExactly("SCAN", "FILTER_ROWS", "GROUP_AND_AGGREGATE", "FILTER_GROUPS");
        assertThat(plan.get("preAggregationFilters").get(0).get("values").toString())
                .isEqualTo("[\"ok\",\"revisar\"]");
        assertThat(plan.get("postAggregationFilters").get(0).get("definitionId").asString()).isEqualTo(sumaValor);
        assertThat(plan.get("definitions").toString()).contains("suma_valor", "lecturas", "\"version\":1");

        // Recovered exactly as persisted.
        String planId = response.get("id").asString();
        assertThat(fetch(reports, plant, planId)).isEqualTo(response);

        // Audited, without filter values.
        JsonNode audit = body(mvc.perform(get("/api/v1/audit-events").param("targetType", "LOGICAL_PLAN")
                .param("targetId", planId).header(HttpHeaders.AUTHORIZATION, TestJwt.human(tenant, "AUDITOR").bearer()))
                .andExpect(status().isOk()).andReturn());
        JsonNode event = audit.get("items").get(0);
        assertThat(event.get("action").asString()).isEqualTo("LOGICAL_PLAN_CREATED");
        assertThat(event.get("actorType").asString()).isEqualTo("SERVICE");
        assertThat(event.get("details").get("filters").asInt()).isEqualTo(2);
        assertThat(event.get("details").toString()).doesNotContain("revisar", "\"100\"");
    }

    @Test
    void anInnerJoinOverACompositeCrossSchemaRelationshipToAPrimaryKey() throws Exception {
        String composite = relationship(TRANSACTIONS, DOCUMENTS, "MANY_TO_ONE", List.of("INNER"),
                "company_code", "company_code", "document_number", "number");
        JsonNode plan = created(request(List.of(importe), List.of(titulo), List.of())).get("plan");

        assertThat(plan.get("safety").asString()).isEqualTo("JOINS_TO_PRIMARY_KEY_ONLY");
        JsonNode join = plan.get("joins").get(0);
        assertThat(join.get("relationshipId").asString()).isEqualTo(composite);
        assertThat(join.get("joinType").asString()).isEqualTo("INNER");
        assertThat(join.get("navigation").asString()).isEqualTo("FORWARD");
        assertThat(join.get("conditions")).hasSize(2);
        assertThat(join.get("conditions").get(1).get("toColumnId").asString()).isEqualTo(column(DOCUMENTS, "number"));
        assertThat(join.get("joinedSideUniqueness").asString()).isEqualTo("PRIMARY_KEY");
        assertThat(join.get("rowPreservation").asString()).isEqualTo("UNMATCHED_ROWS_EXCLUDED");
        JsonNode relationship = plan.get("relationships").get(0);
        assertThat(relationship.get("pairs").get(1).get("targetNaturalKey").asString())
                .isEqualTo(DOCUMENTS + ".\"number\"");
        assertThat(relationship.get("targetUniqueness").asString()).isEqualTo("PRIMARY_KEY");
        assertThat(kinds(plan)).containsExactly("SCAN", "JOIN", "GROUP_AND_AGGREGATE");
    }

    @Test
    void anAuthorizedLeftJoinKeepsRowsButARowFilterOnItsOptionalSideIsRejected() throws Exception {
        relationship(SENSORS, EQUIPMENT, "MANY_TO_ONE", List.of("INNER", "LEFT"), "equipment_id", "id");
        JsonNode join = created(request(List.of(numSensores), List.of(nombreEquipo), List.of()))
                .get("plan").get("joins").get(0);
        assertThat(join.get("joinType").asString()).isEqualTo("LEFT");
        assertThat(join.get("rowPreservation").asString()).isEqualTo("PRESERVED");
        assertThat(join.get("optional").asBoolean()).isTrue();

        JsonNode problem = rejected(request(List.of(numSensores), List.of(), List.of(filter(nombreEquipo, "EQ", "Bomba")),
                List.of(), List.of()));
        assertThat(codes(problem, "errors")).containsExactly("UNSAFE_FILTER");
    }

    @Test
    void twoApprovedRoutesAreAmbiguousUntilOneIsPreferred() throws Exception {
        String dbid = byEquipmentDbid();
        String id = byEquipmentId();

        JsonNode problem = rejected(request(List.of(sumaValor), List.of(nombreEquipo), List.of()));
        assertThat(codes(problem, "errors")).containsExactly("AMBIGUOUS_PATH");
        assertThat(problem.get("errors").get(0).get("alternatives").toString()).contains(dbid, id);

        JsonNode plan = created(request(List.of(sumaValor), List.of(nombreEquipo), List.of(id))).get("plan");
        assertThat(plan.get("joins").get(0).get("relationshipId").asString()).isEqualTo(id);
        assertThat(plan.get("joins").get(0).get("conditions").get(0).get("toColumnId").asString())
                .isEqualTo(column(EQUIPMENT, "id"));
    }

    @Test
    void equipmentDbidIsApprovedButAggregatingMeasurementsAfterItIsRejected() throws Exception {
        String dbid = byEquipmentDbid();
        JsonNode problem = rejected(request(List.of(sumaValor), List.of(nombreEquipo), List.of(dbid)));
        JsonNode error = problem.get("errors").get(0);
        assertThat(error.get("code").asString()).isEqualTo("UNIQUENESS_NOT_PROVEN");
        assertThat(error.get("message").asString()).contains("not proven safe");
        assertThat(error.get("relationshipIds").toString()).contains(dbid);
        assertThat(count("SELECT count(*) FROM logical_plan WHERE tenant_id = :t")).isZero();
    }

    @Test
    void joiningTowardsTheManySideIsFanOutAndMetricsOfDifferentTablesHaveIncompatibleGrains() throws Exception {
        String id = byEquipmentId();
        assertThat(codes(rejected(request(List.of(numEquipos), List.of(nota), List.of(id))), "errors"))
                .containsExactly("FANOUT_RISK");
        assertThat(codes(rejected(request(List.of(sumaValor, importe), List.of(), List.of())), "errors"))
                .containsExactly("INCOMPATIBLE_GRAIN");
        assertThat(codes(rejected(request(List.of(importe), List.of(nota), List.of())), "errors"))
                .containsExactly("NO_PATH");
        assertThat(codes(rejected(request(List.of(numLecturas), List.of(fecha), List.of())), "errors"))
                .containsExactly("GRANULARITY_REQUIRED");
    }

    @Test
    void semanticResolutionFailuresAreDistinctFromPlanningFailures() throws Exception {
        String draft = body(mvc.perform(post("/api/v1/data-sources/" + plant + "/definitions")
                        .header(HttpHeaders.AUTHORIZATION, editor).contentType(MediaType.APPLICATION_JSON)
                        .content(json(metric("borrador", lecturas, "COUNT", null))))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        create(reports, plant, request(List.of(draft), List.of(), List.of()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("semantic_resolution_failed"))
                .andExpect(jsonPath("$.errors[0].code").value("DEFINITION_NOT_APPROVED"));
        create(reports, plant, Map.of("metrics", List.of(sumaValor), "sql", "SELECT 1"))
                .andExpect(status().isBadRequest());
    }

    // --- persistence, history and validity ---

    @Test
    void persistedPlansAreImmutableEvenForTheOwner() throws Exception {
        String planId = created(request(List.of(sumaValor), List.of(nota), List.of())).get("id").asString();
        UUID id = UUID.fromString(planId);
        assertThatThrownBy(() -> appJdbc.sql("UPDATE logical_plan SET fingerprint = repeat('0', 64) WHERE id = :id")
                .param("id", id).update()).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> appJdbc.sql("DELETE FROM logical_plan_dependency WHERE plan_id = :id")
                .param("id", id).update()).rootCause().hasMessageContaining("permission denied");
        assertThatThrownBy(() -> ownerJdbc().sql("UPDATE logical_plan SET plan = '{}' WHERE id = :id")
                .param("id", id).update()).rootCause().hasMessageContaining("append-only");
        assertThat(count("SELECT count(*) FROM logical_plan_dependency WHERE tenant_id = :t")).isPositive();
    }

    @Test
    void aRevisedDefinitionMakesThePlanStaleWithoutRewritingItsSnapshot() throws Exception {
        JsonNode original = created(request(List.of(sumaValor), List.of(nota), List.of()));
        String planId = original.get("id").asString();
        assertThat(validity(planId).get("status").asString()).isEqualTo("CURRENT");

        JsonNode revised = body(mvc.perform(put("/api/v1/data-sources/" + plant + "/definitions/" + sumaValor)
                        .header(HttpHeaders.AUTHORIZATION, editor).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Suma revisada", "metric", Map.of("conceptId", lecturas,
                                "aggregation", "AVG", "columnId", column(MEASUREMENTS, "value"))))))
                .andExpect(status().isOk()).andReturn());
        mvc.perform(post("/api/v1/data-sources/" + plant + "/definitions/" + sumaValor + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, approver)
                        .header(HttpHeaders.IF_MATCH, "\"" + revised.get("version").asLong() + "\""))
                .andExpect(status().isOk());

        JsonNode validity = validity(planId);
        assertThat(validity.get("status").asString()).isEqualTo("STALE");
        assertThat(codes(validity, "issues")).containsExactly("DEFINITION_CHANGED");
        JsonNode history = fetch(reports, plant, planId);
        assertThat(history).as("the historical snapshot keeps its meaning").isEqualTo(original);
        assertThat(history.get("plan").get("aggregations").get(0).get("aggregation").asString()).isEqualTo("SUM");
    }

    @Test
    void aDeactivatedRelationshipMakesThePlanStale() throws Exception {
        String id = byEquipmentId();
        String planId = created(request(List.of(sumaValor), List.of(nombreEquipo), List.of())).get("id").asString();
        mvc.perform(post("/api/v1/data-sources/" + plant + "/relationships/" + id + "/deactivate")
                        .header(HttpHeaders.AUTHORIZATION, approver).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reason", "Obsoleta"))))
                .andExpect(status().isOk());
        JsonNode validity = validity(planId);
        assertThat(validity.get("status").asString()).isEqualTo("STALE");
        assertThat(codes(validity, "issues")).containsExactly("RELATIONSHIP_NOT_USABLE");
    }

    @Test
    void anIncompatiblePhysicalChangeMakesThePlanStale() throws Exception {
        String planId = created(request(List.of(sumaValor), List.of(nota), List.of())).get("id").asString();
        importDocument(plant, SyntheticCatalog.standard()
                .withColumnType("plant", "measurements", "value", "varchar").envelope());
        JsonNode validity = validity(planId);
        assertThat(validity.get("status").asString()).isEqualTo("STALE");
        assertThat(codes(validity, "issues")).contains("DEFINITION_NOT_USABLE", "PHYSICAL_TYPE_CHANGED");
    }

    @Test
    void anArchivedDataSourceIsNeitherPlannedNorCurrent() throws Exception {
        String planId = created(request(List.of(numLecturas), List.of(), List.of())).get("id").asString();
        String etag = mvc.perform(get("/api/v1/data-sources/" + plant).header(HttpHeaders.AUTHORIZATION, steward))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        mvc.perform(patch("/api/v1/data-sources/" + plant).header(HttpHeaders.AUTHORIZATION, steward)
                        .header(HttpHeaders.IF_MATCH, etag).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "ARCHIVED"))))
                .andExpect(status().isOk());

        create(reports, plant, request(List.of(numLecturas), List.of(), List.of()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("data_source_archived"));
        JsonNode validity = validity(planId);
        assertThat(validity.get("status").asString()).isEqualTo("STALE");
        assertThat(codes(validity, "issues")).containsExactly("DATA_SOURCE_ARCHIVED");
        fetch(reports, plant, planId);
    }

    // --- isolation, authorization, determinism ---

    @Test
    void plansAndRelationshipsOfOtherTenantsAndDataSourcesAreNotVisible() throws Exception {
        String planId = created(request(List.of(numLecturas), List.of(), List.of())).get("id").asString();

        Tenant other = newTenant();
        String otherReports = TestJwt.principal("SERVICE", other.id().toString(), "REPORTS_CLIENT").bearer();
        mvc.perform(get("/api/v1/data-sources/" + plant + "/logical-plans/" + planId)
                .header(HttpHeaders.AUTHORIZATION, otherReports)).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/data-sources/" + plant + "/logical-plans/" + planId + "/validity")
                .header(HttpHeaders.AUTHORIZATION, otherReports)).andExpect(status().isNotFound());
        create(otherReports, plant, request(List.of(numLecturas), List.of(), List.of()))
                .andExpect(status().isNotFound());

        // A relationship approved in another tenant is unknown here, even as an explicit preference.
        String otherSteward = TestJwt.human(other, "STEWARD").bearer();
        String otherPlant = createDataSource(otherSteward);
        mvc.perform(post("/api/v1/data-sources/" + otherPlant + "/snapshots")
                        .header(HttpHeaders.AUTHORIZATION, otherSteward).contentType(MediaType.APPLICATION_JSON)
                        .content(SyntheticCatalog.standard().envelope()))
                .andExpect(status().isCreated());
        Map<String, String> otherObjects = new HashMap<>();
        body(mvc.perform(get("/api/v1/data-sources/" + otherPlant + "/objects?size=200")
                        .header(HttpHeaders.AUTHORIZATION, otherSteward)).andReturn())
                .get("items").forEach(i -> otherObjects.put(i.get("naturalKey").asString(), i.get("id").asString()));
        JsonNode foreign = body(mvc.perform(post("/api/v1/data-sources/" + otherPlant + "/relationships")
                        .header(HttpHeaders.AUTHORIZATION, TestJwt.human(other, "EDITOR").bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "ajena", "sourceObjectId", otherObjects.get(MEASUREMENTS),
                                "targetObjectId", otherObjects.get(EQUIPMENT), "pairs", List.of(Map.of(
                                        "sourceColumnId", otherObjects.get(MEASUREMENTS + ".\"equipment_dbid\""),
                                        "targetColumnId", otherObjects.get(EQUIPMENT + ".\"id\""))),
                                "declaredCardinality", "MANY_TO_ONE", "allowedJoinTypes", List.of("INNER")))))
                .andExpect(status().isCreated()).andReturn());
        mvc.perform(post("/api/v1/data-sources/" + otherPlant + "/relationships/" + foreign.get("id").asString()
                        + "/approve").header(HttpHeaders.AUTHORIZATION, TestJwt.human(other, "APPROVER").bearer())
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isOk());
        create(reports, plant, request(List.of(sumaValor), List.of(nombreEquipo),
                List.of(foreign.get("id").asString())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("semantic_resolution_failed"))
                .andExpect(jsonPath("$.errors[0].code").value("PREFERRED_RELATIONSHIP_NOT_FOUND"));

        String erp = createDataSource(steward);
        importDocument(erp, envelope(ALL_SCHEMAS));
        mvc.perform(get("/api/v1/data-sources/" + erp + "/logical-plans/" + planId)
                .header(HttpHeaders.AUTHORIZATION, reports)).andExpect(status().isNotFound());
        String plantRelationship = byEquipmentId();
        create(reports, erp, request(List.of(numLecturas), List.of(), List.of(plantRelationship)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("semantic_resolution_failed"));
    }

    @Test
    void onlyTheResolvePermissionPlansAndReadingChangesNothing() throws Exception {
        Map<String, Object> body = request(List.of(numLecturas), List.of(), List.of());
        for (String role : List.of("VIEWER", "EDITOR", "APPROVER", "STEWARD", "AUDITOR")) {
            create(TestJwt.human(tenant, role).bearer(), plant, body).andExpect(status().isForbidden());
        }
        create(TestJwt.principal("AI", tenant.id().toString(), "AI_AGENT", "REPORTS_CLIENT").bearer(), plant, body)
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/data-sources/" + plant + "/logical-plans").contentType(MediaType.APPLICATION_JSON)
                .content(json(body))).andExpect(status().isUnauthorized());

        String planId = created(body).get("id").asString();
        mvc.perform(get("/api/v1/data-sources/" + plant + "/logical-plans/" + planId)
                .header(HttpHeaders.AUTHORIZATION, TestJwt.human(tenant, "APPROVER").bearer()))
                .andExpect(status().isForbidden());
        long audits = count("SELECT count(*) FROM audit_event WHERE tenant_id = :t");
        fetch(reports, plant, planId);
        validity(planId);
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = :t")).isEqualTo(audits);
    }

    @Test
    void identicalRequestsGiveIdenticalFingerprintsAndPlansCarryNoSql() throws Exception {
        String id = byEquipmentId();
        Map<String, Object> body = request(List.of(sumaValor), List.of(nombreEquipo), List.of(id));
        JsonNode first = created(body);
        JsonNode second = created(body);
        assertThat(second.get("id")).isNotEqualTo(first.get("id"));
        assertThat(second.get("fingerprint")).isEqualTo(first.get("fingerprint"));
        assertThat(second.get("plan")).isEqualTo(first.get("plan"));

        String text = first.toString().toUpperCase(Locale.ROOT);
        assertThat(text).doesNotContain("SELECT ", " WHERE ", "\"SQL");
    }
}
