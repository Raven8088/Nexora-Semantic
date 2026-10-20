package com.nexora.semantic.api;

import com.nexora.semantic.application.resolution.SemanticResolutionService;
import com.nexora.semantic.domain.tenant.Tenant;
import com.nexora.semantic.support.IntegrationTest;
import com.nexora.semantic.support.SyntheticCatalog;
import com.nexora.semantic.support.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S4.1 end to end over PostgreSQL: a catalog built through the real API on the synthetic plant metadata
 * ({@code measurements}/{@code equipment}), resolved by a REPORTS_CLIENT service account. Covers resolution,
 * filters, lifecycle and invalidation, isolation, preferred relationships, consistency and authorization.
 */
class SemanticResolutionApiIntegrationTest extends IntegrationTest {

    private static final String MEASUREMENTS = "\"plant\".\"measurements\"";
    private static final String EQUIPMENT = "\"plant\".\"equipment\"";
    private static final String SENSORS = "\"plant\".\"sensors\"";

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
    private String fecha;
    private String nota;
    private String nombreEquipo;

    @BeforeEach
    void setUp() throws Exception {
        tenant = newTenant();
        steward = TestJwt.human(tenant, "STEWARD").bearer();
        editor = TestJwt.human(tenant, "EDITOR").bearer();
        approver = TestJwt.human(tenant, "APPROVER").bearer();
        reports = TestJwt.principal("SERVICE", tenant.id().toString(), "REPORTS_CLIENT").bearer();
        plant = createDataSource(steward);
        importDocument(plant, SyntheticCatalog.standard().envelope());
        objects = objectIds(plant, TestJwt.human(tenant, "VIEWER").bearer());

        lecturas = approvedDefinition(editor, plant, concept("lecturas", objects.get(MEASUREMENTS)));
        String equipos = approvedDefinition(editor, plant, concept("equipos", objects.get(EQUIPMENT)));
        numLecturas = approvedDefinition(editor, plant, metric("num_lecturas", lecturas, "COUNT", null));
        sumaValor = approvedDefinition(editor, plant, metric("suma_valor", lecturas, "SUM",
                objects.get(column(MEASUREMENTS, "value"))));
        fecha = approvedDefinition(editor, plant, dimension("fecha", lecturas,
                objects.get(column(MEASUREMENTS, "measured_at")), "TEMPORAL"));
        nota = approvedDefinition(editor, plant, dimension("nota", lecturas,
                objects.get(column(MEASUREMENTS, "note")), "CATEGORICAL"));
        nombreEquipo = approvedDefinition(editor, plant, dimension("nombre_equipo", equipos,
                objects.get(column(EQUIPMENT, "name")), "CATEGORICAL"));
    }

    // --- helpers ---

    private String createDataSource(String token) throws Exception {
        return body(mvc.perform(post("/api/v1/data-sources").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("key", unique("erp"), "name", "ERP", "engine", "postgresql",
                                "databaseName", "erp"))))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
    }

    private JsonNode importDocument(String dsId, String document) throws Exception {
        return body(mvc.perform(post("/api/v1/data-sources/" + dsId + "/snapshots")
                        .header(HttpHeaders.AUTHORIZATION, steward)
                        .contentType(MediaType.APPLICATION_JSON).content(document))
                .andExpect(status().isCreated()).andReturn());
    }

    private Map<String, String> objectIds(String dsId, String token) throws Exception {
        Map<String, String> ids = new HashMap<>();
        body(mvc.perform(get("/api/v1/data-sources/" + dsId + "/objects?size=200")
                        .header(HttpHeaders.AUTHORIZATION, token)).andReturn())
                .get("items").forEach(i -> ids.put(i.get("naturalKey").asString(), i.get("id").asString()));
        return ids;
    }

    private static String column(String relation, String column) {
        return relation + ".\"" + column + "\"";
    }

    private static Map<String, Object> concept(String key, String relationId) {
        return Map.of("kind", "CONCEPT", "key", key, "name", key, "concept", Map.of("relationId", relationId));
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

    private JsonNode createdDefinition(String token, String dsId, Map<String, Object> definition) throws Exception {
        return body(mvc.perform(post("/api/v1/data-sources/" + dsId + "/definitions")
                        .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                        .content(json(definition)))
                .andExpect(status().isCreated()).andReturn());
    }

    private JsonNode approve(String dsId, JsonNode definition) throws Exception {
        return body(mvc.perform(post("/api/v1/data-sources/" + dsId + "/definitions/" + definition.get("id").asString()
                        + "/approve").header(HttpHeaders.AUTHORIZATION, approver)
                        .header(HttpHeaders.IF_MATCH, "\"" + definition.get("version").asLong() + "\""))
                .andExpect(status().isOk()).andReturn());
    }

    private String approvedDefinition(String token, String dsId, Map<String, Object> definition) throws Exception {
        return approve(dsId, createdDefinition(token, dsId, definition)).get("id").asString();
    }

    private JsonNode definition(String id) throws Exception {
        return body(mvc.perform(get("/api/v1/data-sources/" + plant + "/definitions/" + id)
                .header(HttpHeaders.AUTHORIZATION, editor)).andExpect(status().isOk()).andReturn());
    }

    private String relationship(String name, String source, String target, String sourceColumn, String targetColumn,
                                boolean approve) throws Exception {
        JsonNode created = body(mvc.perform(post("/api/v1/data-sources/" + plant + "/relationships")
                        .header(HttpHeaders.AUTHORIZATION, editor).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", name, "sourceObjectId", objects.get(source),
                                "targetObjectId", objects.get(target),
                                "pairs", List.of(Map.of("sourceColumnId", objects.get(column(source, sourceColumn)),
                                        "targetColumnId", objects.get(column(target, targetColumn)))),
                                "declaredCardinality", "MANY_TO_ONE", "allowedJoinTypes", List.of("INNER")))))
                .andExpect(status().isCreated()).andReturn());
        if (approve) {
            mvc.perform(post("/api/v1/data-sources/" + plant + "/relationships/" + created.get("id").asString()
                            + "/approve").header(HttpHeaders.AUTHORIZATION, approver)
                            .header(HttpHeaders.IF_MATCH, "\"0\""))
                    .andExpect(status().isOk());
        }
        return created.get("id").asString();
    }

    private static Map<String, Object> request(List<String> metrics, List<String> dimensions,
                                               List<Map<String, Object>> filters, List<String> preferred) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("metrics", metrics);
        body.put("dimensions", dimensions);
        body.put("filters", filters);
        body.put("preferredRelationships", preferred);
        return body;
    }

    private static Map<String, Object> filter(String definitionId, String operator, Object value) {
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("definitionId", definitionId);
        filter.put("operator", operator);
        if (value instanceof List<?> values) {
            filter.put("values", values);
        } else if (value != null) {
            filter.put("value", value);
        }
        return filter;
    }

    private ResultActions resolve(String token, String dsId, Object body) throws Exception {
        return mvc.perform(post("/api/v1/data-sources/" + dsId + "/semantic-resolutions")
                .header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON)
                .content(json(body)));
    }

    private JsonNode resolved(Map<String, Object> body) throws Exception {
        return body(resolve(reports, plant, body).andExpect(status().isOk()).andReturn());
    }

    private JsonNode unresolvable(String dsId, Map<String, Object> body) throws Exception {
        return body(resolve(reports, dsId, body).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("semantic_resolution_failed")).andReturn());
    }

    private static List<String> codes(JsonNode problem) {
        List<String> codes = new ArrayList<>();
        problem.get("errors").forEach(e -> codes.add(e.get("code").asString()));
        return codes;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> names = new ArrayList<>();
        if (node.isObject()) {
            node.properties().forEach(e -> {
                names.add(e.getKey().toLowerCase(Locale.ROOT));
                names.addAll(fieldNames(e.getValue()));
            });
        } else if (node.isArray()) {
            node.forEach(child -> names.addAll(fieldNames(child)));
        }
        return names;
    }

    private long count(String sql) {
        return appJdbc.sql(sql).param("t", tenant.id().value()).query(Long.class).single();
    }

    // --- resolution ---

    @Test
    void resolvesMetricsDimensionsAndTypedFiltersWithoutSql() throws Exception {
        Map<String, Object> body = request(List.of(sumaValor, numLecturas), List.of(fecha, nota, nombreEquipo),
                List.of(filter(fecha, "GTE", "2026-01-01T00:00:00+01:00"), filter(nota, "IN", List.of("ok", "revisar")),
                        filter(sumaValor, "GT", 100.5)), List.of());
        JsonNode resolution = resolved(body);

        assertThat(resolution.get("dataSourceId").asString()).isEqualTo(plant);
        assertThat(resolution.get("metrics").get(0).get("id").asString()).isEqualTo(sumaValor);
        assertThat(resolution.get("metrics").get(0).get("aggregation").asString()).isEqualTo("SUM");
        assertThat(resolution.get("metrics").get(0).get("version").asLong()).isEqualTo(1);
        assertThat(resolution.get("metrics").get(1).has("columnObjectId")).as("COUNT of rows: no column").isFalse();
        assertThat(resolution.get("dimensions").get(0).get("valueType").asString()).isEqualTo("TIMESTAMP_TZ");
        assertThat(resolution.get("dimensions").get(0).get("timeZone").asString()).isEqualTo("UTC");
        assertThat(resolution.get("dimensions").get(2).get("id").asString()).isEqualTo(nombreEquipo);
        assertThat(resolution.get("concepts")).hasSize(2);
        assertThat(resolution.get("filters").get(0).get("stage").asString()).isEqualTo("PRE_AGGREGATION");
        assertThat(resolution.get("filters").get(0).get("values").get(0).asString()).isEqualTo("2025-12-31T23:00Z");
        assertThat(resolution.get("filters").get(1).get("values")).hasSize(2);
        assertThat(resolution.get("filters").get(2).get("stage").asString()).isEqualTo("POST_AGGREGATION");
        List<String> dependencies = new ArrayList<>();
        resolution.get("physicalDependencies").forEach(d -> dependencies.add(d.get("naturalKey").asString()));
        assertThat(dependencies).containsExactly(EQUIPMENT, column(EQUIPMENT, "name"), MEASUREMENTS,
                column(MEASUREMENTS, "measured_at"), column(MEASUREMENTS, "note"), column(MEASUREMENTS, "value"));
        String latestSnapshot = body(mvc.perform(get("/api/v1/data-sources/" + plant + "/snapshots")
                .header(HttpHeaders.AUTHORIZATION, reports)).andReturn()).get("items").get(0).get("id").asString();
        assertThat(resolution.get("metadataSnapshotId").asString()).isEqualTo(latestSnapshot);

        String text = resolution.toString().toUpperCase(Locale.ROOT);
        assertThat(text).doesNotContain("SELECT", " JOIN ", " WHERE ");
        assertThat(fieldNames(resolution)).noneMatch(n -> n.contains("sql") || n.contains("join") || n.contains("route")
                || n.contains("plan"));
        assertThat(resolution.get("preferredRelationships")).isEmpty();
        assertThat(resolved(body)).as("deterministic").isEqualTo(resolution);
    }

    @Test
    void rejectsIncompatibleValuesOperatorsAndUnauthorizedFilterReferences() throws Exception {
        JsonNode problem = unresolvable(plant, request(List.of(sumaValor), List.of(), List.of(
                filter(nota, "EQ", 5),
                filter(fecha, "GT", "2026-13-40"),
                filter(nota, "GT", "m"),
                filter(sumaValor, "IN", List.of(1, 2)),
                filter(lecturas, "IS_NULL", null),
                filter(numLecturas, "GT", 3),
                filter(UUID.randomUUID().toString(), "IS_NULL", null)), List.of()));

        assertThat(codes(problem)).containsExactly("FILTER_INVALID", "FILTER_INVALID", "OPERATOR_INCOMPATIBLE",
                "OPERATOR_INCOMPATIBLE", "FILTER_FIELD_NOT_FOUND", "FILTER_STAGE_UNDETERMINED",
                "FILTER_FIELD_NOT_FOUND");
        assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo("filters[0].values[0]");
        assertThat(problem.toString()).doesNotContain("2026-13-40");

        // Structural violations never reach the resolver.
        resolve(reports, plant, Map.of("metrics", List.of(sumaValor), "sql", "SELECT 1"))
                .andExpect(status().isBadRequest());
        resolve(reports, plant, request(List.of(sumaValor), List.of(),
                List.of(filter(nota, "EQ", Map.of("raw", "x"))), List.of())).andExpect(status().isBadRequest());
        resolve(reports, plant, request(List.of(sumaValor), List.of(),
                List.of(filter(nota, "LIKE", "x%")), List.of())).andExpect(status().isBadRequest());
        resolve(reports, plant, Map.of("dimensions", List.of(nota))).andExpect(status().isBadRequest());
    }

    @Test
    void draftAndDeactivatedDefinitionsAreNotResolved() throws Exception {
        String draft = createdDefinition(editor, plant, metric("borrador", lecturas, "COUNT", null))
                .get("id").asString();
        JsonNode suma = definition(sumaValor);
        mvc.perform(post("/api/v1/data-sources/" + plant + "/definitions/" + sumaValor + "/deactivate")
                        .header(HttpHeaders.AUTHORIZATION, approver)
                        .header(HttpHeaders.IF_MATCH, "\"" + suma.get("version").asLong() + "\"")
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reason", "Obsoleta"))))
                .andExpect(status().isOk());

        JsonNode problem = unresolvable(plant, request(List.of(draft, sumaValor), List.of(), List.of(), List.of()));
        assertThat(codes(problem)).containsExactly("DEFINITION_NOT_APPROVED", "DEFINITION_NOT_APPROVED");
    }

    @Test
    void aTypeChangeInvalidatesTheMetricAndItIsNotResolved() throws Exception {
        importDocument(plant, SyntheticCatalog.standard()
                .withColumnType("plant", "measurements", "value", "varchar").envelope());
        JsonNode problem = unresolvable(plant, request(List.of(sumaValor, numLecturas), List.of(), List.of(),
                List.of()));
        assertThat(codes(problem)).containsExactly("DEFINITION_INVALIDATED");
        assertThat(problem.get("errors").get(0).get("causes").toString()).contains("INCOMPATIBLE_AGGREGATION");
    }

    @Test
    void aMissingColumnInvalidatesTheDimension() throws Exception {
        importDocument(plant, SyntheticCatalog.standard().withoutColumn("plant", "measurements", "note").envelope());
        JsonNode problem = unresolvable(plant, request(List.of(numLecturas), List.of(nota), List.of(), List.of()));
        assertThat(codes(problem)).containsExactly("DEFINITION_INVALIDATED");
        assertThat(problem.get("errors").get(0).get("field").asString()).isEqualTo("dimensions[0]");
        assertThat(problem.get("errors").get(0).get("causes").toString()).contains("REFERENCE_MISSING");
    }

    @Test
    void aMissingTableInvalidatesItsConceptAndEverythingBuiltOnIt() throws Exception {
        importDocument(plant, SyntheticCatalog.standard().withoutTable("plant", "equipment").envelope());
        JsonNode problem = unresolvable(plant, request(List.of(numLecturas), List.of(nombreEquipo), List.of(),
                List.of()));
        assertThat(codes(problem)).containsExactly("DEFINITION_INVALIDATED");
        assertThat(problem.get("errors").get(0).get("causes").toString()).contains("REFERENCE_MISSING");
        // Definitions of other tables are unaffected.
        resolved(request(List.of(numLecturas), List.of(nota), List.of(), List.of()));
    }

    // --- isolation ---

    @Test
    void anotherTenantNeitherSeesTheDataSourceNorCanUseItsIds() throws Exception {
        Tenant other = newTenant();
        String otherReports = TestJwt.principal("SERVICE", other.id().toString(), "REPORTS_CLIENT").bearer();
        resolve(otherReports, plant, request(List.of(sumaValor), List.of(), List.of(), List.of()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("not_found"));

        // The other tenant's own data source does not see this tenant's definitions either.
        String otherSteward = TestJwt.human(other, "STEWARD").bearer();
        String otherPlant = createDataSource(otherSteward);
        mvc.perform(post("/api/v1/data-sources/" + otherPlant + "/snapshots")
                        .header(HttpHeaders.AUTHORIZATION, otherSteward).contentType(MediaType.APPLICATION_JSON)
                        .content(SyntheticCatalog.standard().envelope()))
                .andExpect(status().isCreated());
        JsonNode problem = body(resolve(otherReports, otherPlant,
                request(List.of(sumaValor), List.of(nota), List.of(), List.of()))
                .andExpect(status().isUnprocessableContent()).andReturn());
        assertThat(codes(problem)).containsExactly("METRIC_NOT_FOUND", "DIMENSION_NOT_FOUND");
        assertThat(problem.toString()).doesNotContain("suma_valor", "nota");
    }

    @Test
    void definitionsAndRelationshipsOfAnotherDataSourceAreNotFound() throws Exception {
        String erp = createDataSource(steward);
        importDocument(erp, envelope(ALL_SCHEMAS));
        String byDbid = relationship("Lecturas por dbid", MEASUREMENTS, EQUIPMENT, "equipment_dbid", "dbid", true);

        JsonNode problem = unresolvable(erp, request(List.of(sumaValor), List.of(fecha),
                List.of(filter(nota, "EQ", "ok")), List.of(byDbid)));
        assertThat(codes(problem)).containsExactly("METRIC_NOT_FOUND", "DIMENSION_NOT_FOUND",
                "FILTER_FIELD_NOT_FOUND", "PREFERRED_RELATIONSHIP_NOT_FOUND");
    }

    @Test
    void rejectsDuplicatesAndRequestsWithoutMetrics() throws Exception {
        assertThat(codes(unresolvable(plant, request(List.of(), List.of(nota), List.of(), List.of()))))
                .containsExactly("NO_METRICS");
        JsonNode problem = unresolvable(plant, request(List.of(sumaValor, sumaValor), List.of(nota, nota),
                List.of(), List.of()));
        assertThat(codes(problem)).containsExactly("DUPLICATE_REFERENCE", "DUPLICATE_REFERENCE");
        assertThat(problem.get("errors").get(1).get("field").asString()).isEqualTo("dimensions[1]");
    }

    // --- preferred relationships (S3.1) ---

    @Test
    void preferredRelationshipsAreValidatedAndEchoedButNeverChosen() throws Exception {
        String byDbid = relationship("Lecturas por dbid", MEASUREMENTS, EQUIPMENT, "equipment_dbid", "dbid", true);
        String byId = relationship("Lecturas por id", MEASUREMENTS, EQUIPMENT, "equipment_dbid", "id", true);
        String draft = relationship("Sensores", SENSORS, EQUIPMENT, "equipment_id", "id", false);

        JsonNode resolution = resolved(request(List.of(numLecturas), List.of(nombreEquipo), List.of(),
                List.of(byId, byDbid)));
        JsonNode preferred = resolution.get("preferredRelationships");
        assertThat(preferred).hasSize(2);
        assertThat(preferred.get(0).get("relationshipId").asString()).isEqualTo(byId);
        assertThat(preferred.get(1).get("relationshipId").asString()).isEqualTo(byDbid);
        assertThat(preferred.get(0).get("usable").asBoolean()).isTrue();
        assertThat(preferred.get(1).get("evidence").get("targetUniqueness").asString()).isEqualTo("UNKNOWN");

        JsonNode withoutPreference = resolved(request(List.of(numLecturas), List.of(nombreEquipo), List.of(),
                List.of()));
        assertThat(withoutPreference.get("preferredRelationships"))
                .as("two possible relationships exist, but S4.1 never selects one").isEmpty();

        JsonNode problem = unresolvable(plant, request(List.of(numLecturas), List.of(), List.of(),
                List.of(draft, UUID.randomUUID().toString())));
        assertThat(codes(problem)).containsExactly("PREFERRED_RELATIONSHIP_NOT_USABLE",
                "PREFERRED_RELATIONSHIP_NOT_FOUND");
        assertThat(problem.get("errors").get(0).get("causes").toString()).contains("STATUS_DRAFT");
    }

    // --- consistency ---

    @Test
    void capturesRevisionsSoThatLaterChangesAreDetectable() throws Exception {
        Map<String, Object> body = request(List.of(sumaValor), List.of(fecha), List.of(), List.of());
        JsonNode first = resolved(body);

        JsonNode suma = definition(sumaValor);
        JsonNode revised = body(mvc.perform(put("/api/v1/data-sources/" + plant + "/definitions/" + sumaValor)
                        .header(HttpHeaders.AUTHORIZATION, editor)
                        .header(HttpHeaders.IF_MATCH, "\"" + suma.get("version").asLong() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Suma del valor", "metric", Map.of("conceptId", lecturas,
                                "aggregation", "SUM", "columnId", objects.get(column(MEASUREMENTS, "value")))))))
                .andExpect(status().isOk()).andReturn());
        assertThat(codes(unresolvable(plant, body))).as("a revision is a DRAFT until approved again")
                .containsExactly("DEFINITION_NOT_APPROVED");
        approve(plant, revised);

        JsonNode second = resolved(body);
        assertThat(second.get("metrics").get(0).get("version").asLong())
                .isGreaterThan(first.get("metrics").get(0).get("version").asLong());
        assertThat(second.get("metrics").get(0).get("name").asString()).isEqualTo("Suma del valor");
        assertThat(second.get("fingerprint").asString()).isNotEqualTo(first.get("fingerprint").asString());

        Transactional tx = SemanticResolutionService.class.getAnnotation(Transactional.class);
        assertThat(tx.readOnly()).isTrue();
        assertThat(tx.isolation()).as("one consistent snapshot for the whole resolution")
                .isEqualTo(Isolation.REPEATABLE_READ);
    }

    // --- authorization ---

    @Test
    void onlyTheExistingResolvePermissionCanResolveAndResolvingChangesNothing() throws Exception {
        Map<String, Object> body = request(List.of(sumaValor), List.of(nota), List.of(), List.of());
        for (String role : List.of("VIEWER", "EDITOR", "APPROVER", "STEWARD", "AUDITOR")) {
            resolve(TestJwt.human(tenant, role).bearer(), plant, body).andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("forbidden"));
        }
        resolve(TestJwt.principal("AI", tenant.id().toString(), "AI_AGENT", "REPORTS_CLIENT").bearer(), plant, body)
                .andExpect(status().isForbidden()); // AI principals keep only read and propose permissions
        mvc.perform(post("/api/v1/data-sources/" + plant + "/semantic-resolutions")
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isUnauthorized());

        long audits = count("SELECT count(*) FROM audit_event WHERE tenant_id = :t");
        long versions = count("SELECT coalesce(sum(row_version), 0) FROM semantic_definition WHERE tenant_id = :t");
        resolved(body);
        resolve(reports, plant, request(List.of(), List.of(), List.of(), List.of()))
                .andExpect(status().isUnprocessableContent());
        assertThat(count("SELECT count(*) FROM audit_event WHERE tenant_id = :t")).as("read-only").isEqualTo(audits);
        assertThat(count("SELECT coalesce(sum(row_version), 0) FROM semantic_definition WHERE tenant_id = :t"))
                .isEqualTo(versions);
    }

    @Test
    void anArchivedDataSourceIsNotResolved() throws Exception {
        String etag = mvc.perform(get("/api/v1/data-sources/" + plant).header(HttpHeaders.AUTHORIZATION, steward))
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);
        mvc.perform(patch("/api/v1/data-sources/" + plant).header(HttpHeaders.AUTHORIZATION, steward)
                        .header(HttpHeaders.IF_MATCH, etag).contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "ARCHIVED"))))
                .andExpect(status().isOk());
        resolve(reports, plant, request(List.of(sumaValor), List.of(), List.of(), List.of()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("data_source_archived"));
    }
}
