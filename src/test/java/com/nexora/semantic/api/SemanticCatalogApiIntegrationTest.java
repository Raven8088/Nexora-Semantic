package com.nexora.semantic.api;

import com.nexora.semantic.domain.tenant.Tenant;
import com.nexora.semantic.support.IntegrationTest;
import com.nexora.semantic.support.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nexora.semantic.support.DiscoveryFixtures.ALL_SCHEMAS;
import static com.nexora.semantic.support.DiscoveryFixtures.ALL_SCHEMAS_V2;
import static com.nexora.semantic.support.DiscoveryFixtures.envelope;
import static com.nexora.semantic.support.DiscoveryFixtures.envelopeOf;
import static com.nexora.semantic.support.DiscoveryFixtures.payload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S3 Basic end to end over the real Discovery metadata imported in S2: definitions, approval rules (D3/D19),
 * invalidation by metadata changes, tenant isolation, permissions and audit.
 */
class SemanticCatalogApiIntegrationTest extends IntegrationTest {

    private Tenant tenant;
    private String steward;
    private String editor;
    private String approver;
    private String viewer;
    private String dataSourceId;
    private Map<String, String> objectIds;

    @BeforeEach
    void setUp() throws Exception {
        tenant = newTenant();
        steward = TestJwt.human(tenant, "STEWARD").bearer();
        editor = TestJwt.human(tenant, "EDITOR").bearer();
        approver = TestJwt.human(tenant, "APPROVER").bearer();
        viewer = TestJwt.human(tenant, "VIEWER").bearer();
        dataSourceId = createDataSource(steward);
        importDocument(steward, dataSourceId, envelope(ALL_SCHEMAS));
        objectIds = objectIds(dataSourceId, viewer);
    }

    // --- helpers ---

    private String createDataSource(String token) throws Exception {
        return body(mvc.perform(post("/api/v1/data-sources").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("key", unique("erp"), "name", "ERP", "engine", "postgresql",
                                "databaseName", "erp"))))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
    }

    private JsonNode importDocument(String token, String dsId, String document) throws Exception {
        return body(mvc.perform(post("/api/v1/data-sources/" + dsId + "/snapshots").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(document)).andExpect(status().isCreated()).andReturn());
    }

    private Map<String, String> objectIds(String dsId, String token) throws Exception {
        Map<String, String> ids = new HashMap<>();
        body(mvc.perform(get("/api/v1/data-sources/" + dsId + "/objects?size=200").header(HttpHeaders.AUTHORIZATION, token))
                .andReturn()).get("items").forEach(i -> ids.put(i.get("naturalKey").asString(), i.get("id").asString()));
        return ids;
    }

    private String object(String naturalKey) {
        return objectIds.get(naturalKey);
    }

    private String definitions() {
        return "/api/v1/data-sources/" + dataSourceId + "/definitions";
    }

    private ResultActions create(String token, Map<String, Object> body) throws Exception {
        return mvc.perform(post(definitions()).header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private static Map<String, Object> concept(String key, String relationId) {
        return body("CONCEPT", key, "concept", Map.of("relationId", relationId));
    }

    private static Map<String, Object> metric(String key, String conceptId, String aggregation, String columnId) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("conceptId", conceptId);
        block.put("aggregation", aggregation);
        if (columnId != null) {
            block.put("columnId", columnId);
        }
        return body("METRIC", key, "metric", block);
    }

    private static Map<String, Object> dimension(String key, String conceptId, String columnId, String type,
                                                 String timeZone) {
        Map<String, Object> block = new LinkedHashMap<>();
        block.put("conceptId", conceptId);
        block.put("columnId", columnId);
        block.put("type", type);
        if (timeZone != null) {
            block.put("timeZone", timeZone);
        }
        return body("DIMENSION", key, "dimension", block);
    }

    private static Map<String, Object> body(String kind, String key, String blockName, Map<String, Object> block) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("key", key);
        body.put("name", "Nombre " + key);
        body.put("description", "Descripción de " + key);
        body.put(blockName, block);
        return body;
    }

    private JsonNode created(String token, Map<String, Object> body) throws Exception {
        return body(create(token, body).andExpect(status().isCreated()).andReturn());
    }

    private ResultActions approve(String token, JsonNode definition) throws Exception {
        return mvc.perform(post(definitions() + "/" + definition.get("id").asString() + "/approve")
                .header(HttpHeaders.AUTHORIZATION, token)
                .header(HttpHeaders.IF_MATCH, "\"" + definition.get("version").asLong() + "\""));
    }

    private JsonNode approved(JsonNode definition) throws Exception {
        return body(approve(approver, definition).andExpect(status().isOk()).andReturn());
    }

    private JsonNode fetch(String id) throws Exception {
        return body(mvc.perform(get(definitions() + "/" + id).header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode approvedConcept(String key, String relation) throws Exception {
        return approved(created(editor, concept(key, object(relation))));
    }

    // --- lifecycle ---

    @Test
    void conceptsMetricsAndDimensionsAreCreatedAndApproved() throws Exception {
        JsonNode draft = body(create(editor, concept("pedido", object("\"sales\".\"orders\"")))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.origin").value("MANUAL"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.issues").isEmpty())
                .andExpect(jsonPath("$.concept.relationNaturalKey").value("\"sales\".\"orders\""))
                .andReturn());

        JsonNode pedido = body(approve(approver, draft)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.usable").value(true))
                .andExpect(jsonPath("$.approvedBy").exists())
                .andReturn());
        String pedidoId = pedido.get("id").asString();

        JsonNode ventas = approved(created(editor,
                metric("ventas_totales", pedidoId, "SUM", object("\"sales\".\"orders\".\"total\""))));
        assertThat(ventas.get("usable").asBoolean()).isTrue();
        assertThat(ventas.get("metric").get("columnNaturalKey").asString()).isEqualTo("\"sales\".\"orders\".\"total\"");
        assertThat(approved(created(editor, metric("ticket_medio", pedidoId, "AVG",
                object("\"sales\".\"orders\".\"total\"")))).get("usable").asBoolean()).isTrue();
        JsonNode numero = approved(created(editor, metric("numero_pedidos", pedidoId, "COUNT", null)));
        assertThat(numero.get("metric").has("columnId")).isFalse();

        JsonNode fecha = approved(created(editor, dimension("fecha_pedido", pedidoId,
                object("\"sales\".\"orders\".\"order_date\""), "TEMPORAL", null)));
        assertThat(fecha.get("dimension").get("granularities").toString())
                .isEqualTo("[\"DAY\",\"WEEK\",\"MONTH\",\"QUARTER\",\"YEAR\"]");
        assertThat(fecha.get("dimension").has("timeZone")).isFalse();
        assertThat(approved(created(editor, dimension("notas", pedidoId,
                object("\"sales\".\"orders\".\"notes\""), "CATEGORICAL", null))).get("usable").asBoolean()).isTrue();

        String clienteId = approvedConcept("cliente", "\"sales\".\"customers\"").get("id").asString();
        JsonNode alta = approved(created(editor, dimension("alta_cliente", clienteId,
                object("\"sales\".\"customers\".\"created_at\""), "TEMPORAL", null)));
        assertThat(alta.get("dimension").get("timeZone").asString()).isEqualTo("UTC");
        JsonNode altaMadrid = created(editor, dimension("alta_cliente_madrid", clienteId,
                object("\"sales\".\"customers\".\"created_at\""), "TEMPORAL", "Europe/Madrid"));
        assertThat(altaMadrid.get("dimension").get("timeZone").asString()).isEqualTo("Europe/Madrid");

        mvc.perform(get(definitions()).param("kind", "METRIC").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[0].key").value("numero_pedidos"));
        mvc.perform(get(definitions()).param("status", "DRAFT").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].key").value("alta_cliente_madrid"));
    }

    @Test
    void metricsNeedAnApprovedConcept() throws Exception {
        JsonNode draftConcept = created(editor, concept("pedido", object("\"sales\".\"orders\"")));
        JsonNode metric = created(editor, metric("numero", draftConcept.get("id").asString(), "COUNT", null));
        assertThat(metric.get("issues").get(0).get("code").asString()).isEqualTo("CONCEPT_NOT_APPROVED");

        approve(approver, metric)
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("definition_invalid"))
                .andExpect(jsonPath("$.errors[0].code").value("CONCEPT_NOT_APPROVED"))
                .andExpect(jsonPath("$.errors[0].field").value("metric.conceptId"));

        approved(draftConcept);
        approve(approver, metric).andExpect(status().isOk()).andExpect(jsonPath("$.usable").value(true));
    }

    @Test
    void revisingAnApprovedDefinitionRequiresANewApproval() throws Exception {
        JsonNode pedido = approvedConcept("pedido", "\"sales\".\"orders\"");
        Map<String, Object> update = Map.of("name", "Pedido de venta",
                "concept", Map.of("relationId", object("\"sales\".\"orders\"")));

        mvc.perform(put(definitions() + "/" + pedido.get("id").asString()).header(HttpHeaders.AUTHORIZATION, editor)
                        .contentType(MediaType.APPLICATION_JSON).content(json(update)))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(put(definitions() + "/" + pedido.get("id").asString()).header(HttpHeaders.AUTHORIZATION, editor)
                        .header(HttpHeaders.IF_MATCH, "\"0\"").contentType(MediaType.APPLICATION_JSON).content(json(update)))
                .andExpect(status().isPreconditionFailed());
        JsonNode revised = body(mvc.perform(put(definitions() + "/" + pedido.get("id").asString())
                        .header(HttpHeaders.AUTHORIZATION, editor).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(json(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.name").value("Pedido de venta"))
                .andExpect(jsonPath("$.approvedBy").doesNotExist())
                .andReturn());

        approve(approver, revised).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(3));
    }

    @Test
    void deactivation() throws Exception {
        JsonNode pedido = approvedConcept("pedido", "\"sales\".\"orders\"");
        String uri = definitions() + "/" + pedido.get("id").asString() + "/deactivate";

        mvc.perform(post(uri).header(HttpHeaders.AUTHORIZATION, approver).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reason"));
        mvc.perform(post(uri).header(HttpHeaders.AUTHORIZATION, editor).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());
        JsonNode inactive = body(mvc.perform(post(uri).header(HttpHeaders.AUTHORIZATION, approver)
                        .header(HttpHeaders.IF_MATCH, "\"1\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Sustituido por pedido_neto\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INACTIVE"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.inactiveReason").value("Sustituido por pedido_neto"))
                .andReturn());

        mvc.perform(post(uri).header(HttpHeaders.AUTHORIZATION, approver).header(HttpHeaders.IF_MATCH, "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("invalid_state_transition"));
        approve(approver, inactive)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("invalid_state_transition"));
    }

    // --- approval rules ---

    @Test
    void approvalRequiresAnotherHumanWithApprovalPermission() throws Exception {
        JsonNode draft = created(editor, concept("pedido", object("\"sales\".\"orders\"")));
        approve(editor, draft).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("forbidden"));

        TestJwt.Builder approverWhoAuthors = TestJwt.human(tenant, "APPROVER");
        String author = approverWhoAuthors.bearer();
        JsonNode own = created(author, concept("cliente", object("\"sales\".\"customers\"")));
        mvc.perform(post(definitions() + "/" + own.get("id").asString() + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, author).header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("self_approval_forbidden"));

        String serviceApprover = TestJwt.principal("SERVICE", tenant.id().toString(), "APPROVER").bearer();
        approve(serviceApprover, draft).andExpect(status().isForbidden());
        approve(approver, draft).andExpect(status().isOk());
    }

    @Test
    void anAiAgentProposesDraftsButCannotApproveThem() throws Exception {
        String qwen = TestJwt.principal("AI", tenant.id().toString(), "AI_AGENT", "APPROVER").bearer();
        JsonNode aiDraft = body(create(qwen, concept("pedido", object("\"sales\".\"orders\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.origin").value("AI"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn());

        approve(qwen, aiDraft).andExpect(status().isForbidden());
        approve(approver, aiDraft).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
    }

    @Test
    void readersCannotWrite() throws Exception {
        create(viewer, concept("pedido", object("\"sales\".\"orders\""))).andExpect(status().isForbidden());
        create(steward, concept("pedido", object("\"sales\".\"orders\""))).andExpect(status().isForbidden());
        approvedConcept("pedido", "\"sales\".\"orders\"");
        String reports = TestJwt.principal("SERVICE", tenant.id().toString(), "REPORTS_CLIENT").bearer();
        mvc.perform(get(definitions()).header(HttpHeaders.AUTHORIZATION, reports))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].usable").value(true));
    }

    // --- references and validation ---

    @Test
    void invalidReferencesAreRejected() throws Exception {
        String pedidoId = approvedConcept("pedido", "\"sales\".\"orders\"").get("id").asString();

        create(editor, concept("c1", object("\"sales\".\"orders\".\"total\"")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("REFERENCE_WRONG_TYPE"));
        create(editor, concept("c2", UUID.randomUUID().toString()))
                .andExpect(jsonPath("$.errors[0].code").value("REFERENCE_NOT_FOUND"));
        create(editor, metric("m1", pedidoId, "SUM", object("\"sales\".\"customers\".\"id\"")))
                .andExpect(jsonPath("$.errors[0].code").value("COLUMN_NOT_IN_CONCEPT"));
        create(editor, metric("m2", pedidoId, "SUM", object("\"sales\".\"orders\".\"notes\"")))
                .andExpect(jsonPath("$.errors[0].code").value("INCOMPATIBLE_AGGREGATION"))
                .andExpect(jsonPath("$.errors[0].field").value("metric.aggregation"));
        create(editor, metric("m3", pedidoId, "AVG", null))
                .andExpect(jsonPath("$.errors[0].code").value("INCOMPATIBLE_AGGREGATION"));
        create(editor, dimension("d1", pedidoId, object("\"sales\".\"orders\".\"notes\""), "TEMPORAL", null))
                .andExpect(jsonPath("$.errors[0].code").value("INCOMPATIBLE_DIMENSION"));
        create(editor, dimension("d2", pedidoId, object("\"sales\".\"orders\".\"order_date\""), "TEMPORAL",
                "Europe/Madrid"))
                .andExpect(jsonPath("$.errors[0].code").value("NOT_APPLICABLE"))
                .andExpect(jsonPath("$.errors[0].field").value("dimension.timeZone"));
        create(editor, dimension("d3", pedidoId, object("\"sales\".\"orders\".\"order_date\""), "TEMPORAL", "Mars/Base"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
        mvc.perform(get(definitions()).header(HttpHeaders.AUTHORIZATION, viewer)).andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    void malformedRequestsAreRejected() throws Exception {
        Map<String, Object> wrongBlock = concept("pedido", object("\"sales\".\"orders\""));
        wrongBlock.put("kind", "METRIC");
        create(editor, wrongBlock).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_failed"));
        Map<String, Object> twoBlocks = concept("pedido", object("\"sales\".\"orders\""));
        twoBlocks.put("metric", Map.of("conceptId", UUID.randomUUID().toString(), "aggregation", "COUNT"));
        create(editor, twoBlocks).andExpect(status().isBadRequest());
        Map<String, Object> sqlAttempt = concept("pedido", object("\"sales\".\"orders\""));
        sqlAttempt.put("expression", "SUM(total) FROM orders; DROP TABLE x");
        create(editor, sqlAttempt).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("expression"));
        create(editor, concept("Pedido Neto", object("\"sales\".\"orders\""))).andExpect(status().isBadRequest());

        created(editor, concept("pedido", object("\"sales\".\"orders\"")));
        create(editor, concept("pedido", object("\"sales\".\"customers\"")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("duplicate_key"));
    }

    @Test
    void referencesToOtherTenantsOrDataSourcesAreImpossible() throws Exception {
        Tenant other = newTenant();
        String otherSteward = TestJwt.human(other, "STEWARD", "VIEWER").bearer();
        String otherDataSource = createDataSource(otherSteward);
        importDocument(otherSteward, otherDataSource, envelope(ALL_SCHEMAS));
        String foreignOrders = objectIds(otherDataSource, otherSteward).get("\"sales\".\"orders\"");

        create(editor, concept("pedido", foreignOrders))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("REFERENCE_NOT_FOUND"));

        String secondSource = createDataSource(steward);
        importDocument(steward, secondSource, envelope(ALL_SCHEMAS));
        String otherSourceOrders = objectIds(secondSource, viewer).get("\"sales\".\"orders\"");
        create(editor, concept("pedido", otherSourceOrders))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("REFERENCE_NOT_FOUND"));
    }

    @Test
    void tenantsAreIsolated() throws Exception {
        JsonNode pedido = approvedConcept("pedido", "\"sales\".\"orders\"");
        Tenant other = newTenant();
        String otherApprover = TestJwt.human(other, "APPROVER").bearer();

        mvc.perform(get(definitions()).header(HttpHeaders.AUTHORIZATION, otherApprover)).andExpect(status().isNotFound());
        mvc.perform(get(definitions() + "/" + pedido.get("id").asString()).header(HttpHeaders.AUTHORIZATION, otherApprover))
                .andExpect(status().isNotFound());
        mvc.perform(post(definitions() + "/" + pedido.get("id").asString() + "/deactivate")
                        .header(HttpHeaders.AUTHORIZATION, otherApprover).header(HttpHeaders.IF_MATCH, "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
                .andExpect(status().isNotFound());
        assertThat(fetch(pedido.get("id").asString()).get("status").asString()).isEqualTo("APPROVED");
    }

    // --- metadata changes ---

    @Test
    void missingObjectsInvalidateApprovedDefinitionsUntilReviewed() throws Exception {
        JsonNode resumen = approvedConcept("resumen", "\"sales\".\"v_order_summary\"");
        String resumenId = resumen.get("id").asString();
        JsonNode filas = approved(created(editor, metric("filas_resumen", resumenId, "COUNT", null)));
        JsonNode pedido = approvedConcept("pedido", "\"sales\".\"orders\"");

        JsonNode v2 = importDocument(steward, dataSourceId, envelope(ALL_SCHEMAS_V2));

        JsonNode invalidConcept = fetch(resumenId);
        assertThat(invalidConcept.get("status").asString()).isEqualTo("INACTIVE");
        assertThat(invalidConcept.get("usable").asBoolean()).isFalse();
        assertThat(invalidConcept.get("issues").get(0).get("code").asString()).isEqualTo("REFERENCE_MISSING");
        assertThat(invalidConcept.get("inactiveReason").asString()).contains("REFERENCE_MISSING");
        JsonNode invalidMetric = fetch(filas.get("id").asString());
        assertThat(invalidMetric.get("status").asString()).isEqualTo("INACTIVE");
        assertThat(invalidMetric.get("inactiveReason").asString()).contains("CONCEPT_NOT_APPROVED");
        assertThat(fetch(pedido.get("id").asString()).get("status").asString()).isEqualTo("APPROVED");

        String auditor = TestJwt.human(tenant, "AUDITOR").bearer();
        mvc.perform(get("/api/v1/audit-events").param("targetType", "SEMANTIC_DEFINITION").param("targetId", resumenId)
                        .header(HttpHeaders.AUTHORIZATION, auditor))
                .andExpect(jsonPath("$.items[0].action").value("DEFINITION_INVALIDATED"))
                .andExpect(jsonPath("$.items[0].details.snapshotId").value(v2.get("id").asString()));
        mvc.perform(get("/api/v1/audit-events").param("targetType", "METADATA_SNAPSHOT")
                        .param("targetId", v2.get("id").asString()).header(HttpHeaders.AUTHORIZATION, auditor))
                .andExpect(jsonPath("$.items[0].details.invalidatedDefinitions").value(2));

        // New definitions cannot reference the missing view.
        create(editor, concept("resumen2", object("\"sales\".\"v_order_summary\"")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("REFERENCE_MISSING"));

        // Restoring the metadata does not silently reactivate it: it stays INACTIVE until revised and approved.
        importDocument(steward, dataSourceId, envelope(ALL_SCHEMAS));
        JsonNode stillInactive = fetch(resumenId);
        assertThat(stillInactive.get("status").asString()).isEqualTo("INACTIVE");
        assertThat(stillInactive.get("issues")).isEmpty();

        JsonNode revised = body(mvc.perform(put(definitions() + "/" + resumenId).header(HttpHeaders.AUTHORIZATION, editor)
                        .header(HttpHeaders.IF_MATCH, "\"" + stillInactive.get("version").asLong() + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("name", "Resumen", "concept",
                                Map.of("relationId", object("\"sales\".\"v_order_summary\""))))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT")).andReturn());
        approve(approver, revised).andExpect(status().isOk()).andExpect(jsonPath("$.usable").value(true));
    }

    @Test
    void anIncompatibleColumnTypeChangeInvalidatesTheMetric() throws Exception {
        String lineasId = approvedConcept("linea_pedido", "\"sales\".\"order_lines\"").get("id").asString();
        JsonNode importe = approved(created(editor, metric("importe_lineas", lineasId, "SUM",
                object("\"sales\".\"order_lines\".\"unit_price\""))));
        JsonNode cantidad = approved(created(editor, metric("cantidad", lineasId, "SUM",
                object("\"sales\".\"order_lines\".\"quantity\""))));

        // The real Discovery response, with unit_price changed from numeric to text.
        String changed = payload(ALL_SCHEMAS).replace(
                "\"name\":\"unit_price\",\"position\":6,\"dataType\":\"numeric\"",
                "\"name\":\"unit_price\",\"position\":6,\"dataType\":\"text\"");
        assertThat(changed).isNotEqualTo(payload(ALL_SCHEMAS));
        importDocument(steward, dataSourceId, envelopeOf(changed, List.of()));

        JsonNode invalid = fetch(importe.get("id").asString());
        assertThat(invalid.get("status").asString()).isEqualTo("INACTIVE");
        assertThat(invalid.get("issues").get(0).get("code").asString()).isEqualTo("INCOMPATIBLE_AGGREGATION");
        assertThat(fetch(cantidad.get("id").asString()).get("usable").asBoolean()).isTrue();
        assertThat(fetch(lineasId).get("status").asString()).isEqualTo("APPROVED");
    }

    @Test
    void changesAndApprovalsAreAudited() throws Exception {
        JsonNode draft = created(editor, concept("pedido", object("\"sales\".\"orders\"")));
        approved(draft);
        String auditor = TestJwt.human(tenant, "AUDITOR").bearer();

        mvc.perform(get("/api/v1/audit-events").param("targetType", "SEMANTIC_DEFINITION")
                        .param("targetId", draft.get("id").asString()).header(HttpHeaders.AUTHORIZATION, auditor))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].action").value("DEFINITION_APPROVED"))
                .andExpect(jsonPath("$.items[0].actorType").value("HUMAN"))
                .andExpect(jsonPath("$.items[1].action").value("DEFINITION_CREATED"))
                .andExpect(jsonPath("$.items[1].details.kind").value("CONCEPT"))
                .andExpect(jsonPath("$.items[1].details.origin").value("MANUAL"));
    }
}
