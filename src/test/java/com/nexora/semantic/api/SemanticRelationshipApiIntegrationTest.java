package com.nexora.semantic.api;

import com.nexora.semantic.application.catalog.SemanticRelationshipService;
import com.nexora.semantic.domain.relationship.RelationshipContract;
import com.nexora.semantic.domain.shared.CallerContext;
import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.PrincipalType;
import com.nexora.semantic.domain.source.DataSourceId;
import com.nexora.semantic.domain.tenant.Tenant;
import com.nexora.semantic.support.IntegrationTest;
import com.nexora.semantic.support.SyntheticCatalog;
import com.nexora.semantic.support.TestJwt;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nexora.semantic.support.DiscoveryFixtures.ALL_SCHEMAS;
import static com.nexora.semantic.support.DiscoveryFixtures.envelope;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S3.1 end to end: relationships between physical objects over the real Discovery ERP metadata (foreign keys,
 * composite and cross-schema) and a synthetic payload with the mandatory examples ({@code measurements.equipment_dbid
 * = equipment.dbid}, different column names, composite cross-schema join, unresolvable foreign keys). Covers
 * approval rules, evidence versus declared cardinality, invalidation by imports, replacement with history,
 * candidates, tenant and data source isolation, If-Match and audit.
 */
class SemanticRelationshipApiIntegrationTest extends IntegrationTest {

    private static final String EQUIPMENT = "\"plant\".\"equipment\"";
    private static final String MEASUREMENTS = "\"plant\".\"measurements\"";
    private static final String SENSORS = "\"plant\".\"sensors\"";

    @Autowired
    private SemanticRelationshipService relationshipService;

    private Tenant tenant;
    private String steward;
    private String editor;
    private String approver;
    private String viewer;
    private String auditor;
    private String erp;
    private String plant;
    private Map<String, String> erpIds;
    private Map<String, String> plantIds;

    @BeforeEach
    void setUp() throws Exception {
        tenant = newTenant();
        steward = TestJwt.human(tenant, "STEWARD").bearer();
        editor = TestJwt.human(tenant, "EDITOR").bearer();
        approver = TestJwt.human(tenant, "APPROVER").bearer();
        viewer = TestJwt.human(tenant, "VIEWER").bearer();
        auditor = TestJwt.human(tenant, "AUDITOR").bearer();
        erp = createDataSource(steward);
        importDocument(erp, envelope(ALL_SCHEMAS));
        erpIds = objectIds(erp);
        plant = createDataSource(steward);
        importDocument(plant, SyntheticCatalog.standard().envelope());
        plantIds = objectIds(plant);
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

    private Map<String, String> objectIds(String dsId) throws Exception {
        Map<String, String> ids = new HashMap<>();
        body(mvc.perform(get("/api/v1/data-sources/" + dsId + "/objects?size=200")
                        .header(HttpHeaders.AUTHORIZATION, viewer)).andReturn())
                .get("items").forEach(i -> ids.put(i.get("naturalKey").asString(), i.get("id").asString()));
        return ids;
    }

    private static String relationships(String dsId) {
        return "/api/v1/data-sources/" + dsId + "/relationships";
    }

    private static String column(String relation, String column) {
        return relation + ".\"" + column + "\"";
    }

    private String plant(String naturalKey) {
        return id(plantIds, naturalKey);
    }

    private static String id(Map<String, String> ids, String naturalKey) {
        String id = ids.get(naturalKey);
        assertThat(id).as(naturalKey).isNotNull();
        return id;
    }

    /** {@code pairs} alternates source and target column natural keys. */
    private Map<String, Object> semantic(String name, String source, String target, String cardinality,
                                         String... pairs) {
        List<Map<String, String>> list = new ArrayList<>();
        for (int i = 0; i < pairs.length; i += 2) {
            list.add(Map.of("sourceColumnId", plant(pairs[i]), "targetColumnId", plant(pairs[i + 1])));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("description", "Relación " + name);
        body.put("sourceObjectId", plant(source));
        body.put("targetObjectId", plant(target));
        body.put("pairs", list);
        body.put("declaredCardinality", cardinality);
        body.put("allowedJoinTypes", List.of("INNER", "LEFT"));
        return body;
    }

    private Map<String, Object> equipmentByDbid() {
        return semantic("Lecturas por equipo", MEASUREMENTS, EQUIPMENT, "MANY_TO_ONE",
                column(MEASUREMENTS, "equipment_dbid"), column(EQUIPMENT, "dbid"));
    }

    private ResultActions create(String token, String dsId, Map<String, Object> body) throws Exception {
        return mvc.perform(post(relationships(dsId)).header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private JsonNode created(Map<String, Object> body) throws Exception {
        return body(create(editor, plant, body).andExpect(status().isCreated()).andReturn());
    }

    private ResultActions fromForeignKey(String token, String dsId, String foreignKeyId) throws Exception {
        return mvc.perform(post(relationships(dsId) + "/from-foreign-key").header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("foreignKeyId", foreignKeyId,
                        "declaredCardinality", "MANY_TO_ONE", "allowedJoinTypes", List.of("INNER")))));
    }

    private ResultActions approve(String token, String dsId, JsonNode relationship) throws Exception {
        return mvc.perform(post(relationships(dsId) + "/" + relationship.get("id").asString() + "/approve")
                .header(HttpHeaders.AUTHORIZATION, token)
                .header(HttpHeaders.IF_MATCH, "\"" + relationship.get("version").asLong() + "\""));
    }

    private JsonNode approved(JsonNode relationship) throws Exception {
        return body(approve(approver, plant, relationship).andExpect(status().isOk()).andReturn());
    }

    private ResultActions update(String token, JsonNode relationship, Map<String, Object> body) throws Exception {
        return mvc.perform(put(relationships(plant) + "/" + relationship.get("id").asString())
                .header(HttpHeaders.AUTHORIZATION, token)
                .header(HttpHeaders.IF_MATCH, "\"" + relationship.get("version").asLong() + "\"")
                .contentType(MediaType.APPLICATION_JSON).content(json(body)));
    }

    private JsonNode fetch(String dsId, String id) throws Exception {
        return body(mvc.perform(get(relationships(dsId) + "/" + id).header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk()).andReturn());
    }

    private JsonNode fetch(JsonNode relationship) throws Exception {
        return fetch(plant, relationship.get("id").asString());
    }

    private JsonNode contract(JsonNode relationship) throws Exception {
        return body(mvc.perform(get(relationships(plant) + "/" + relationship.get("id").asString() + "/contract")
                .header(HttpHeaders.AUTHORIZATION, viewer)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode auditOf(String targetType, String targetId) throws Exception {
        return body(mvc.perform(get("/api/v1/audit-events").param("targetType", targetType)
                .param("targetId", targetId).header(HttpHeaders.AUTHORIZATION, auditor))
                .andExpect(status().isOk()).andReturn());
    }

    private long storedRelationships(String dsId) {
        return appJdbc.sql("SELECT count(*) FROM semantic_relationship WHERE data_source_id = :d")
                .param("d", UUID.fromString(dsId)).query(Long.class).single();
    }

    // --- foreign keys ---

    @Test
    void createsADraftFromACompositeCrossSchemaForeignKeyWithItsEvidence() throws Exception {
        String fk = id(erpIds, "\"sales\".\"order_lines\"#\"order_lines_stock_fk\"");
        JsonNode draft = body(fromForeignKey(editor, erp, fk)
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.origin").value("DECLARED_FK"))
                .andExpect(jsonPath("$.usable").value(false))
                .andExpect(jsonPath("$.physicallyValid").value(true))
                .andExpect(jsonPath("$.name").value("order_lines_stock_fk"))
                .andExpect(jsonPath("$.source.naturalKey").value("\"sales\".\"order_lines\""))
                .andExpect(jsonPath("$.target.naturalKey").value("\"inventory\".\"stock\""))
                .andExpect(jsonPath("$.foreignKey.objectId").value(fk))
                .andExpect(jsonPath("$.pairs[0].sourceColumn.naturalKey")
                        .value("\"sales\".\"order_lines\".\"warehouse_code\""))
                .andExpect(jsonPath("$.pairs[0].targetColumn.naturalKey")
                        .value("\"inventory\".\"stock\".\"warehouse_code\""))
                .andExpect(jsonPath("$.pairs[1].sourceColumn.naturalKey")
                        .value("\"sales\".\"order_lines\".\"product_sku\""))
                .andExpect(jsonPath("$.evidence.foreignKey").value("PRESENT"))
                .andExpect(jsonPath("$.evidence.targetUniqueness").value("PRIMARY_KEY"))
                .andExpect(jsonPath("$.evidence.cardinalityAssurance").value("BACKED_BY_PRIMARY_KEY"))
                .andReturn());

        // Never duplicated: neither the same foreign key again nor an equivalent SEMANTIC relationship.
        fromForeignKey(editor, erp, fk).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("relationship_equivalent_exists"))
                .andExpect(jsonPath("$.existingRelationshipId").value(draft.get("id").asString()));
        assertThat(storedRelationships(erp)).isEqualTo(1);

        approve(approver, erp, draft).andExpect(status().isOk()).andExpect(jsonPath("$.usable").value(true));
    }

    @Test
    void anUnresolvableForeignKeyIsRejectedWithItsReasonAndNothingIsInvented() throws Exception {
        fromForeignKey(editor, plant, plant(SENSORS + "#\"sensors_vendor_fk\""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("relationship_invalid"))
                .andExpect(jsonPath("$.errors[0].code").value("REFERENCE_UNRESOLVABLE"))
                .andExpect(jsonPath("$.errors[0].field").value("foreignKey.target"));
        fromForeignKey(editor, plant, plant(SENSORS + "#\"sensors_case_fk\""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("REFERENCE_UNRESOLVABLE"));
        fromForeignKey(editor, plant, plant(EQUIPMENT))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("REFERENCE_WRONG_TYPE"));
        assertThat(storedRelationships(plant)).isZero();
    }

    // --- semantic relationships ---

    @Test
    void equipmentDbidIsApprovableButItsUniquenessIsNeverPresentedAsGuaranteed() throws Exception {
        JsonNode draft = body(create(editor, plant, equipmentByDbid())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.origin").value("SEMANTIC"))
                .andExpect(jsonPath("$.foreignKey").doesNotExist())
                .andExpect(jsonPath("$.target.naturalKey").value(EQUIPMENT))
                .andExpect(jsonPath("$.pairs[0].targetColumn.naturalKey").value(column(EQUIPMENT, "dbid")))
                .andExpect(jsonPath("$.evidence.targetUniqueness").value("UNKNOWN"))
                .andExpect(jsonPath("$.evidence.foreignKey").value("NOT_DECLARED"))
                .andExpect(jsonPath("$.evidence.cardinalityAssurance").value("DECLARED_ONLY"))
                .andExpect(jsonPath("$.evidence.sourceToTargetRowMultiplication").value("POSSIBLE"))
                .andReturn());

        approve(approver, plant, draft).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.usable").value(true));

        JsonNode contract = contract(draft);
        assertThat(contract.get("usable").asBoolean()).isTrue();
        assertThat(contract.get("declaredCardinality").asString()).isEqualTo("MANY_TO_ONE");
        assertThat(contract.get("evidence").get("targetUniqueness").asString()).isEqualTo("UNKNOWN");
        assertThat(contract.get("evidence").get("cardinalityAssurance").asString()).isEqualTo("DECLARED_ONLY");
        assertThat(contract.get("evidence").get("sourceToTargetRowMultiplication").asString()).isEqualTo("POSSIBLE");
        assertThat(contract.get("sourceObjectId").asString()).isEqualTo(plant(MEASUREMENTS));
        assertThat(contract.get("pairs").get(0).get("targetColumnId").asString())
                .isEqualTo(plant(column(EQUIPMENT, "dbid")));
        assertThat(contract.get("allowedJoinTypes").toString()).isEqualTo("[\"INNER\",\"LEFT\"]");
        assertThat(contract.get("version").asLong()).isEqualTo(1);
    }

    @Test
    void relatesColumnsWithDifferentNamesWithoutAnyForeignKey() throws Exception {
        create(editor, plant, semantic("Facturas por cliente", "\"finance\".\"invoices\"", "\"finance\".\"clients\"",
                "ONE_TO_ONE", "\"finance\".\"invoices\".\"client_code\"", "\"finance\".\"clients\".\"account_number\""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.physicallyValid").value(true))
                .andExpect(jsonPath("$.evidence.foreignKey").value("NOT_DECLARED"))
                .andExpect(jsonPath("$.evidence.sourceUniqueness").value("UNKNOWN"))
                .andExpect(jsonPath("$.evidence.targetUniqueness").value("UNKNOWN"))
                .andExpect(jsonPath("$.evidence.cardinalityAssurance").value("DECLARED_ONLY"));
    }

    @Test
    void compositeCrossSchemaRelationshipIsBackedByTheExactTargetPrimaryKey() throws Exception {
        String transactions = "\"finance\".\"transactions\"";
        String documents = "\"docs\".\"documents\"";
        create(editor, plant, semantic("Movimientos por documento", transactions, documents, "MANY_TO_ONE",
                column(transactions, "company_code"), column(documents, "company_code"),
                column(transactions, "document_number"), column(documents, "number")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pairs.length()").value(2))
                .andExpect(jsonPath("$.pairs[1].ordinal").value(1))
                .andExpect(jsonPath("$.pairs[1].targetColumn.naturalKey").value(column(documents, "number")))
                .andExpect(jsonPath("$.evidence.targetUniqueness").value("PRIMARY_KEY"))
                .andExpect(jsonPath("$.evidence.sourceUniqueness").value("UNKNOWN"))
                .andExpect(jsonPath("$.evidence.cardinalityAssurance").value("BACKED_BY_PRIMARY_KEY"))
                .andExpect(jsonPath("$.evidence.sourceToTargetRowMultiplication").value("NONE_BY_PRIMARY_KEY"))
                .andExpect(jsonPath("$.evidence.targetToSourceRowMultiplication").value("POSSIBLE"));
    }

    @Test
    void rejectsUnknownColumnsColumnsOfOtherTablesAndIncompatibleTypes() throws Exception {
        Map<String, Object> unknown = equipmentByDbid();
        unknown.put("pairs", List.of(Map.of("sourceColumnId", UUID.randomUUID().toString(),
                "targetColumnId", plant(column(EQUIPMENT, "dbid")))));
        create(editor, plant, unknown).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("REFERENCE_NOT_FOUND"))
                .andExpect(jsonPath("$.errors[0].field").value("pairs[0].sourceColumnId"));

        create(editor, plant, semantic("x", MEASUREMENTS, EQUIPMENT, "MANY_TO_ONE",
                column(MEASUREMENTS, "equipment_dbid"), column("\"plant\".\"sensors\"", "equipment_id")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("COLUMN_NOT_IN_RELATION"));

        create(editor, plant, semantic("x", MEASUREMENTS, EQUIPMENT, "MANY_TO_ONE",
                column(MEASUREMENTS, "measured_at"), column(EQUIPMENT, "dbid")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("INCOMPATIBLE_TYPES"));
        assertThat(storedRelationships(plant)).isZero();
    }

    @Test
    void rejectsRepeatedPairsAndEquivalentRelationshipsInAnyDirection() throws Exception {
        create(editor, plant, semantic("x", MEASUREMENTS, EQUIPMENT, "MANY_TO_ONE",
                column(MEASUREMENTS, "equipment_dbid"), column(EQUIPMENT, "dbid"),
                column(MEASUREMENTS, "equipment_dbid"), column(EQUIPMENT, "dbid")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("DUPLICATE_PAIR"));
        create(editor, plant, semantic("x", MEASUREMENTS, EQUIPMENT, "MANY_TO_ONE",
                column(MEASUREMENTS, "equipment_dbid"), column(EQUIPMENT, "dbid"),
                column(MEASUREMENTS, "equipment_dbid"), column(EQUIPMENT, "id")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("CONTRADICTORY_PAIR"));

        JsonNode first = created(equipmentByDbid());
        create(editor, plant, semantic("Inversa", EQUIPMENT, MEASUREMENTS, "ONE_TO_MANY",
                column(EQUIPMENT, "dbid"), column(MEASUREMENTS, "equipment_dbid")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("relationship_equivalent_exists"))
                .andExpect(jsonPath("$.existingRelationshipId").value(first.get("id").asString()));
        assertThat(storedRelationships(plant)).isEqualTo(1);
    }

    // --- isolation ---

    @Test
    void isolatesTenantsAndDataSources() throws Exception {
        JsonNode relationship = created(equipmentByDbid());
        String id = relationship.get("id").asString();

        Tenant other = newTenant();
        String otherViewer = TestJwt.human(other, "VIEWER", "EDITOR").bearer();
        mvc.perform(get(relationships(plant) + "/" + id).header(HttpHeaders.AUTHORIZATION, otherViewer))
                .andExpect(status().isNotFound());
        mvc.perform(get(relationships(plant)).header(HttpHeaders.AUTHORIZATION, otherViewer))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/data-sources/" + plant + "/relationship-candidates")
                .header(HttpHeaders.AUTHORIZATION, otherViewer)).andExpect(status().isNotFound());
        create(otherViewer, plant, equipmentByDbid()).andExpect(status().isNotFound());

        // Another data source of the same tenant: its relationship ids are unknown, its objects are not found.
        mvc.perform(get(relationships(erp) + "/" + id).header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isNotFound());
        create(editor, erp, equipmentByDbid()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("REFERENCE_NOT_FOUND"));
        fromForeignKey(editor, plant, id(erpIds, "\"sales\".\"orders\"#\"orders_customer_fk\""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("REFERENCE_NOT_FOUND"));
        assertThat(storedRelationships(erp)).isZero();
    }

    // --- candidates ---

    @Test
    void candidatesAreExplainableSuggestionsThatAreNeverStored() throws Exception {
        JsonNode result = body(mvc.perform(get("/api/v1/data-sources/" + plant + "/relationship-candidates")
                        .header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk()).andReturn());
        List<JsonNode> candidates = new ArrayList<>();
        result.get("candidates").forEach(candidates::add);

        JsonNode fk = candidates.getFirst();
        assertThat(fk.get("suggestedOrigin").asString()).isEqualTo("DECLARED_FK");
        assertThat(fk.get("classification").asString()).isEqualTo("STRONG");
        assertThat(fk.get("heuristic").asBoolean()).isTrue();
        assertThat(fk.get("foreignKeyId").asString()).isEqualTo(plant(SENSORS + "#\"sensors_equipment_fk\""));

        JsonNode dbid = candidates.stream().filter(c -> c.get("pairs").get(0).get("sourceColumn").get("naturalKey")
                .asString().equals(column(MEASUREMENTS, "equipment_dbid"))).findFirst().orElseThrow();
        assertThat(dbid.get("pairs").get(0).get("targetColumn").get("naturalKey").asString())
                .isEqualTo(column(EQUIPMENT, "dbid"));
        assertThat(dbid.get("suggestedOrigin").asString()).isEqualTo("SEMANTIC");
        assertThat(dbid.get("targetUniqueness").asString()).isEqualTo("UNKNOWN");
        assertThat(dbid.get("signals").toString()).contains("COLUMN_NAME_REFERENCES_TABLE",
                "TARGET_BUSINESS_IDENTIFIER");
        assertThat(candidates).noneMatch(c -> c.get("pairs").get(0).get("sourceColumn").get("naturalKey").asString()
                .endsWith(".\"id\""));
        assertThat(result.get("unresolvedForeignKeys").toString())
                .contains("sensors_vendor_fk", "sensors_case_fk", "REFERENCE_UNRESOLVABLE");

        assertThat(storedRelationships(plant)).as("suggestions are computed on demand, never stored").isZero();
        mvc.perform(get(relationships(plant)).header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(jsonPath("$.totalItems").value(0));

        // Only a person creating a DRAFT persists it; it is then no longer suggested.
        created(equipmentByDbid());
        mvc.perform(get("/api/v1/data-sources/" + plant + "/relationship-candidates").param("limit", "100")
                        .header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(jsonPath("$.alreadyCataloged").value(1));
        mvc.perform(get("/api/v1/data-sources/" + plant + "/relationship-candidates").param("limit", "1000")
                .header(HttpHeaders.AUTHORIZATION, viewer)).andExpect(status().isBadRequest());
    }

    // --- approval ---

    @Test
    void approverMustBeAnotherHumanAndNeverAServiceOrAnAi() throws Exception {
        String approverAuthor = TestJwt.human(tenant, "APPROVER").bearer();
        JsonNode own = body(create(approverAuthor, plant, equipmentByDbid())
                .andExpect(status().isCreated()).andReturn());
        approve(approverAuthor, plant, own).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("self_approval_forbidden"));

        String lastEditor = TestJwt.human(tenant, "APPROVER").bearer();
        JsonNode edited = body(update(lastEditor, own, Map.of("name", "Editado", "declaredCardinality", "MANY_TO_ONE",
                "allowedJoinTypes", List.of("INNER"))).andExpect(status().isOk()).andReturn());
        approve(lastEditor, plant, edited).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("self_approval_forbidden"));

        String service = TestJwt.principal("SERVICE", tenant.id().toString(), "APPROVER").bearer();
        approve(service, plant, edited).andExpect(status().isForbidden());
        String ai = TestJwt.principal("AI", tenant.id().toString(), "AI_AGENT", "APPROVER").bearer();
        approve(ai, plant, edited).andExpect(status().isForbidden());
        approve(editor, plant, edited).andExpect(status().isForbidden());

        JsonNode aiDraft = body(create(ai, plant, semantic("Propuesta IA", "\"plant\".\"sensors\"", EQUIPMENT,
                "MANY_TO_ONE", column(SENSORS, "equipment_id"), column(EQUIPMENT, "id")))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.createdBy").exists()).andReturn());
        approve(ai, plant, aiDraft).andExpect(status().isForbidden());

        approve(approver, plant, edited).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        create(viewer, plant, equipmentByDbid()).andExpect(status().isForbidden());
    }

    @Test
    void draftAndInactiveRelationshipsAreNeverUsable() throws Exception {
        JsonNode draft = created(equipmentByDbid());
        assertThat(contract(draft).get("usable").asBoolean()).isFalse();
        assertThat(contract(draft).get("status").asString()).isEqualTo("DRAFT");

        JsonNode approved = approved(draft);
        JsonNode other = created(semantic("Sensores", SENSORS, EQUIPMENT, "MANY_TO_ONE",
                column(SENSORS, "equipment_id"), column(EQUIPMENT, "id")));
        CallerContext caller = new CallerContext(tenant.id(), new PrincipalRef("s4", PrincipalType.SERVICE), null);
        List<RelationshipContract> contracts = relationshipService.approvedContracts(caller,
                new DataSourceId(UUID.fromString(plant)));
        assertThat(contracts).extracting(c -> c.relationshipId().toString())
                .containsExactly(approved.get("id").asString());
        assertThat(contracts.getFirst().usable()).isTrue();

        JsonNode inactive = body(mvc.perform(post(relationships(plant) + "/" + approved.get("id").asString()
                        + "/deactivate").header(HttpHeaders.AUTHORIZATION, approver)
                        .header(HttpHeaders.IF_MATCH, "\"" + approved.get("version").asLong() + "\"")
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reason", "Ya no se usa"))))
                .andExpect(status().isOk()).andReturn());
        assertThat(inactive.get("status").asString()).isEqualTo("INACTIVE");
        assertThat(inactive.get("usable").asBoolean()).isFalse();
        assertThat(contract(inactive).get("usable").asBoolean()).isFalse();
        assertThat(relationshipService.approvedContracts(caller, new DataSourceId(UUID.fromString(plant)))).isEmpty();
        assertThat(contract(other).get("usable").asBoolean()).isFalse();
    }

    // --- invalidation by imports ---

    @Test
    void aMissingTableInvalidatesAndRestoringItDoesNotReactivate() throws Exception {
        JsonNode approved = approved(created(equipmentByDbid()));
        JsonNode snapshot = importDocument(plant, SyntheticCatalog.standard().withoutTable("plant", "equipment")
                .envelope());

        JsonNode invalid = fetch(approved);
        assertThat(invalid.get("status").asString()).isEqualTo("INACTIVE");
        assertThat(invalid.get("usable").asBoolean()).isFalse();
        assertThat(invalid.get("inactiveReason").asString()).contains("REFERENCE_MISSING");
        assertThat(invalid.get("issues").toString()).contains("REFERENCE_MISSING");
        assertThat(invalid.get("approvedBy").asString()).as("history is kept").isNotBlank();
        assertThat(auditOf("METADATA_SNAPSHOT", snapshot.get("id").asString())
                .get("items").get(0).get("details").get("invalidatedRelationships").asInt()).isEqualTo(1);

        importDocument(plant, SyntheticCatalog.standard().envelope());
        JsonNode stillInactive = fetch(approved);
        assertThat(stillInactive.get("status").asString()).isEqualTo("INACTIVE");
        assertThat(stillInactive.get("physicallyValid").asBoolean()).isTrue();

        // Revalidation is explicit: a person revises it and another approves it.
        JsonNode revised = body(update(editor, stillInactive, Map.of("name", "Lecturas por equipo",
                "declaredCardinality", "MANY_TO_ONE", "allowedJoinTypes", List.of("INNER", "LEFT")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT")).andReturn());
        approve(approver, plant, revised).andExpect(status().isOk()).andExpect(jsonPath("$.usable").value(true));
    }

    @Test
    void aMissingColumnInvalidates() throws Exception {
        JsonNode approved = approved(created(equipmentByDbid()));
        importDocument(plant, SyntheticCatalog.standard().withoutColumn("plant", "equipment", "dbid").envelope());
        JsonNode invalid = fetch(approved);
        assertThat(invalid.get("status").asString()).isEqualTo("INACTIVE");
        assertThat(invalid.get("issues").get(0).get("code").asString()).isEqualTo("REFERENCE_MISSING");
        assertThat(invalid.get("issues").get(0).get("field").asString()).isEqualTo("pairs[0].targetColumnId");
    }

    @Test
    void anIncompatibleTypeChangeInvalidatesAndAReimportIsIdempotent() throws Exception {
        JsonNode approved = approved(created(equipmentByDbid()));
        String changed = SyntheticCatalog.standard()
                .withColumnType("plant", "measurements", "equipment_dbid", "varchar").envelope();
        importDocument(plant, changed);
        JsonNode invalid = fetch(approved);
        assertThat(invalid.get("status").asString()).isEqualTo("INACTIVE");
        assertThat(invalid.get("inactiveReason").asString()).contains("INCOMPATIBLE_TYPES");

        JsonNode again = importDocument(plant, changed);
        assertThat(auditOf("METADATA_SNAPSHOT", again.get("id").asString())
                .get("items").get(0).get("details").get("invalidatedRelationships").asInt()).isZero();
        JsonNode audit = auditOf("SEMANTIC_RELATIONSHIP", approved.get("id").asString());
        assertThat(audit.get("items").get(0).get("action").asString()).isEqualTo("RELATIONSHIP_INVALIDATED");
        assertThat(audit.get("totalItems").asInt()).as("created, approved, invalidated once").isEqualTo(3);
        assertThat(fetch(approved).get("version").asLong()).isEqualTo(invalid.get("version").asLong());
    }

    @Test
    void aLostForeignKeyBlocksUseKeepsHistoryAndRequiresAnExplicitSemanticReplacement() throws Exception {
        JsonNode declared = body(fromForeignKey(editor, plant, plant(SENSORS + "#\"sensors_equipment_fk\""))
                .andExpect(status().isCreated()).andReturn());
        JsonNode approved = approved(declared);

        importDocument(plant, SyntheticCatalog.standard()
                .withoutForeignKey("plant", "sensors", "sensors_equipment_fk").envelope());
        JsonNode lost = fetch(approved);
        assertThat(lost.get("status").asString()).isEqualTo("INACTIVE");
        assertThat(lost.get("origin").asString()).as("never silently converted").isEqualTo("DECLARED_FK");
        assertThat(lost.get("evidence").get("foreignKey").asString()).isEqualTo("LOST");
        assertThat(lost.get("issues").get(0).get("code").asString()).isEqualTo("FOREIGN_KEY_LOST");
        assertThat(lost.get("inactiveReason").asString()).contains("FOREIGN_KEY_LOST");
        JsonNode invalidation = auditOf("SEMANTIC_RELATIONSHIP", approved.get("id").asString()).get("items").get(0);
        assertThat(invalidation.get("action").asString()).isEqualTo("RELATIONSHIP_INVALIDATED");
        assertThat(invalidation.get("details").get("foreignKeyLost").asBoolean()).isTrue();

        // A revision alone cannot make it usable again while the foreign key is lost.
        JsonNode revised = body(update(editor, lost, Map.of("name", "Sensores por equipo",
                "declaredCardinality", "MANY_TO_ONE", "allowedJoinTypes", List.of("INNER")))
                .andExpect(status().isOk()).andReturn());
        approve(approver, plant, revised).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].code").value("FOREIGN_KEY_LOST"));

        // Explicit flow: an equivalent SEMANTIC relationship that replaces it; the old one stays as history.
        Map<String, Object> semantic = semantic("Sensores por equipo", SENSORS, EQUIPMENT, "MANY_TO_ONE",
                column(SENSORS, "equipment_id"), column(EQUIPMENT, "id"));
        create(editor, plant, semantic).andExpect(status().isConflict());
        semantic.put("replacesId", approved.get("id").asString());
        JsonNode replacement = created(semantic);
        approve(approver, plant, replacement).andExpect(status().isOk())
                .andExpect(jsonPath("$.origin").value("SEMANTIC"))
                .andExpect(jsonPath("$.usable").value(true))
                .andExpect(jsonPath("$.replacesId").value(approved.get("id").asString()));
        JsonNode history = fetch(approved);
        assertThat(history.get("status").asString()).isEqualTo("INACTIVE");
        assertThat(history.get("inactiveReason").asString()).contains(replacement.get("id").asString());
        assertThat(history.get("origin").asString()).isEqualTo("DECLARED_FK");
    }

    // --- replacement, edition, If-Match, audit ---

    @Test
    void controlledReplacementNeverLeavesTwoApprovedAndKeepsHistory() throws Exception {
        JsonNode wrong = approved(created(semantic("Lecturas (incorrecta)", MEASUREMENTS, EQUIPMENT, "MANY_TO_ONE",
                column(MEASUREMENTS, "equipment_dbid"), column(EQUIPMENT, "id"))));
        Map<String, Object> corrected = equipmentByDbid();
        corrected.put("replacesId", wrong.get("id").asString());
        JsonNode draft = created(corrected);
        assertThat(fetch(wrong).get("usable").asBoolean()).as("the old one is usable until the new one is approved")
                .isTrue();

        approve(approver, plant, draft).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"));
        JsonNode replaced = fetch(wrong);
        assertThat(replaced.get("status").asString()).isEqualTo("INACTIVE");
        assertThat(replaced.get("inactiveReason").asString()).isEqualTo("Replaced by relationship "
                + draft.get("id").asString());
        assertThat(replaced.get("pairs").get(0).get("targetColumn").get("naturalKey").asString())
                .isEqualTo(column(EQUIPMENT, "id"));
        assertThat(auditOf("SEMANTIC_RELATIONSHIP", wrong.get("id").asString()).get("items").get(0)
                .get("action").asString()).isEqualTo("RELATIONSHIP_REPLACED");
        assertThat(appJdbc.sql("SELECT count(*) FROM semantic_relationship WHERE data_source_id = :d "
                        + "AND status = 'APPROVED'").param("d", UUID.fromString(plant)).query(Long.class).single())
                .isEqualTo(1);
        assertThat(storedRelationships(plant)).isEqualTo(2);
    }

    @Test
    void editingDescriptiveFieldsReturnsToDraftAndPairsAreImmutable() throws Exception {
        JsonNode approved = approved(created(equipmentByDbid()));
        JsonNode edited = body(update(editor, approved, Map.of("name", "Mediciones del equipo", "description",
                "Nueva descripción", "declaredCardinality", "ONE_TO_ONE", "allowedJoinTypes", List.of("LEFT")))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"2\""))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.approvedBy").doesNotExist())
                .andExpect(jsonPath("$.declaredCardinality").value("ONE_TO_ONE"))
                .andExpect(jsonPath("$.allowedJoinTypes[0]").value("LEFT"))
                .andReturn());
        assertThat(edited.get("pairs")).isEqualTo(approved.get("pairs"));

        Map<String, Object> withPairs = new LinkedHashMap<>(Map.of("name", "x", "declaredCardinality", "MANY_TO_ONE",
                "allowedJoinTypes", List.of("INNER")));
        withPairs.put("pairs", List.of(Map.of("sourceColumnId", plant(column(MEASUREMENTS, "id")),
                "targetColumnId", plant(column(EQUIPMENT, "id")))));
        update(editor, edited, withPairs).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("pairs"));
        Map<String, Object> withTarget = new LinkedHashMap<>(Map.of("name", "x", "declaredCardinality", "MANY_TO_ONE",
                "allowedJoinTypes", List.of("INNER"), "targetObjectId", plant(SENSORS)));
        update(editor, edited, withTarget).andExpect(status().isBadRequest());
        assertThat(fetch(edited).get("pairs")).isEqualTo(approved.get("pairs"));
    }

    @Test
    void writesRequireAMatchingIfMatch() throws Exception {
        JsonNode draft = created(equipmentByDbid());
        String path = relationships(plant) + "/" + draft.get("id").asString();
        Map<String, Object> body = Map.of("name", "x", "declaredCardinality", "MANY_TO_ONE",
                "allowedJoinTypes", List.of("INNER"));
        mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, editor).contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(put(path).header(HttpHeaders.AUTHORIZATION, editor).header(HttpHeaders.IF_MATCH, "\"7\"")
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isPreconditionFailed());
        mvc.perform(post(path + "/approve").header(HttpHeaders.AUTHORIZATION, approver))
                .andExpect(status().isPreconditionRequired());
        mvc.perform(post(path + "/deactivate").header(HttpHeaders.AUTHORIZATION, approver)
                        .header(HttpHeaders.IF_MATCH, "\"3\"").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("reason", "x"))))
                .andExpect(status().isPreconditionFailed());
        assertThat(fetch(draft).get("version").asLong()).isZero();
    }

    @Test
    void everyChangeIsAudited() throws Exception {
        JsonNode draft = created(equipmentByDbid());
        JsonNode approved = approved(draft);
        mvc.perform(post(relationships(plant) + "/" + approved.get("id").asString() + "/deactivate")
                        .header(HttpHeaders.AUTHORIZATION, approver)
                        .header(HttpHeaders.IF_MATCH, "\"" + approved.get("version").asLong() + "\"")
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("reason", "Obsoleta"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/v1/audit-events").param("targetType", "SEMANTIC_RELATIONSHIP")
                        .param("targetId", draft.get("id").asString()).header(HttpHeaders.AUTHORIZATION, auditor))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.items[0].action").value("RELATIONSHIP_DEACTIVATED"))
                .andExpect(jsonPath("$.items[0].details.reason").value("Obsoleta"))
                .andExpect(jsonPath("$.items[1].action").value("RELATIONSHIP_APPROVED"))
                .andExpect(jsonPath("$.items[1].details.cardinalityAssurance").value("DECLARED_ONLY"))
                .andExpect(jsonPath("$.items[1].actorType").value("HUMAN"))
                .andExpect(jsonPath("$.items[2].action").value("RELATIONSHIP_CREATED"))
                .andExpect(jsonPath("$.items[2].details.origin").value("SEMANTIC"))
                .andExpect(jsonPath("$.items[2].details.pairs").value(1));
    }
}
