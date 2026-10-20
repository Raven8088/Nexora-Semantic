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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.nexora.semantic.support.DiscoveryFixtures.ALL_SCHEMAS;
import static com.nexora.semantic.support.DiscoveryFixtures.ALL_SCHEMAS_V2;
import static com.nexora.semantic.support.DiscoveryFixtures.SALES;
import static com.nexora.semantic.support.DiscoveryFixtures.SALES_RESTRICTED;
import static com.nexora.semantic.support.DiscoveryFixtures.envelope;
import static com.nexora.semantic.support.DiscoveryFixtures.envelopeOf;
import static com.nexora.semantic.support.DiscoveryFixtures.payload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * S2 Basic end to end: real Nexora Discovery responses imported through the API into PostgreSQL 17.6, with JWT,
 * permissions, tenant isolation and the restricted application role.
 */
class MetadataImportApiIntegrationTest extends IntegrationTest {

    private Tenant tenant;
    private String steward;
    private String viewer;
    private String dataSourceId;

    @BeforeEach
    void setUp() throws Exception {
        tenant = newTenant();
        steward = TestJwt.human(tenant, "STEWARD").bearer();
        viewer = TestJwt.human(tenant, "VIEWER").bearer();
        dataSourceId = createDataSource(steward, "erp", "postgresql");
    }

    private String createDataSource(String token, String databaseName, String engine) throws Exception {
        return body(mvc.perform(post("/api/v1/data-sources").header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("key", unique("erp"), "name", "ERP", "engine", engine,
                                "databaseName", databaseName))))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
    }

    private ResultActions importDocument(String token, String dsId, String document) throws Exception {
        return mvc.perform(post("/api/v1/data-sources/" + dsId + "/snapshots")
                .header(HttpHeaders.AUTHORIZATION, token)
                .contentType(MediaType.APPLICATION_JSON).content(document));
    }

    private JsonNode imported(String document) throws Exception {
        return body(importDocument(steward, dataSourceId, document).andExpect(status().isCreated()).andReturn());
    }

    private JsonNode objects(String query) throws Exception {
        return body(mvc.perform(get("/api/v1/data-sources/" + dataSourceId + "/objects?size=200" + query)
                .header(HttpHeaders.AUTHORIZATION, viewer)).andExpect(status().isOk()).andReturn());
    }

    private JsonNode object(String naturalKey) throws Exception {
        for (JsonNode item : objects("").get("items")) {
            if (item.get("naturalKey").asString().equals(naturalKey)) {
                return item;
            }
        }
        throw new AssertionError("object not found: " + naturalKey);
    }

    private Map<String, String> idsByNaturalKey() throws Exception {
        Map<String, String> ids = new HashMap<>();
        objects("").get("items").forEach(i -> ids.put(i.get("type").asString() + i.get("naturalKey").asString(),
                i.get("id").asString()));
        return ids;
    }

    @Test
    void importsARealDiscoveryResponse() throws Exception {
        JsonNode snapshot = body(importDocument(steward, dataSourceId, envelope(ALL_SCHEMAS))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION,
                        startsWith("/api/v1/data-sources/" + dataSourceId + "/snapshots/")))
                .andExpect(jsonPath("$.outcome").value("APPLIED"))
                .andExpect(jsonPath("$.allSchemas").value(true))
                .andExpect(jsonPath("$.contractVersion").value("discovery-api/0.1"))
                .andExpect(jsonPath("$.databaseName").value("erp"))
                .andExpect(jsonPath("$.summary.observed").value(48))
                .andExpect(jsonPath("$.summary.added").value(48))
                .andExpect(jsonPath("$.summary.unavailableSchemas").isEmpty())
                .andReturn());
        assertThat(snapshot.get("contentSha256").asString()).hasSize(64);

        assertThat(objects("&type=SCHEMA").get("totalItems").asInt()).isEqualTo(2);
        assertThat(objects("&type=TABLE").get("totalItems").asInt()).isEqualTo(6);
        assertThat(objects("&type=VIEW").get("totalItems").asInt()).isEqualTo(1);
        assertThat(objects("&type=MATERIALIZED_VIEW").get("totalItems").asInt()).isEqualTo(1);
        assertThat(objects("&type=COLUMN").get("totalItems").asInt()).isEqualTo(33);
        assertThat(objects("&type=FOREIGN_KEY").get("totalItems").asInt()).isEqualTo(5);
        assertThat(objects("&schema=restricted").get("totalItems").asInt()).isZero();

        JsonNode stockFk = object("\"sales\".\"order_lines\"#\"order_lines_stock_fk\"");
        assertThat(stockFk.get("attributes").get("targetSchema").asString()).isEqualTo("inventory");
        assertThat(stockFk.get("attributes").get("columns").toString()).isEqualTo("[\"warehouse_code\",\"product_sku\"]");
        JsonNode customerId = object("\"sales\".\"customers\".\"id\"");
        assertThat(customerId.get("attributes").get("dataType").asString()).isEqualTo("bigserial");
        assertThat(customerId.get("attributes").get("nullable").asBoolean()).isFalse();
        assertThat(customerId.get("attributes").has("defaultValue")).isFalse();
        JsonNode customers = object("\"sales\".\"customers\"");
        assertThat(customerId.get("parentId").asString()).isEqualTo(customers.get("id").asString());
        assertThat(objects("&parentId=" + customers.get("id").asString()).get("totalItems").asInt()).isEqualTo(5);
        assertThat(object("\"inventory\".\"warehouses\"").get("comment").asString())
                .isEqualTo("Physical storage locations");

        mvc.perform(get("/api/v1/data-sources/" + dataSourceId + "/objects/" + customers.get("id").asString())
                        .header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("TABLE"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.attributes.primaryKey[0]").value("id"))
                .andExpect(jsonPath("$.firstSeenSnapshotId").value(snapshot.get("id").asString()));
    }

    @Test
    void repeatedImportIsIdempotentAndKeepsStableIds() throws Exception {
        imported(envelope(ALL_SCHEMAS));
        Map<String, String> before = idsByNaturalKey();

        JsonNode second = imported(envelope(ALL_SCHEMAS));

        assertThat(second.get("outcome").asString()).isEqualTo("UNCHANGED");
        assertThat(second.get("summary").get("unchanged").asInt()).isEqualTo(48);
        assertThat(second.get("summary").get("added").asInt()).isZero();
        assertThat(objects("").get("totalItems").asInt()).isEqualTo(48);
        assertThat(idsByNaturalKey()).isEqualTo(before);
        assertThat(object("\"sales\".\"orders\"").get("lastSeenSnapshotId").asString())
                .isEqualTo(second.get("id").asString());
    }

    @Test
    void schemaEvolutionIsTrackedWithoutDeletingAnything() throws Exception {
        imported(envelope(ALL_SCHEMAS));
        String viewId = object("\"sales\".\"v_order_summary\"").get("id").asString();

        JsonNode v2 = imported(envelope(ALL_SCHEMAS_V2));

        assertThat(v2.get("summary").get("added").asInt()).isEqualTo(6);   // segment + refunds (table, 3 cols, fk)
        assertThat(v2.get("summary").get("updated").asInt()).isEqualTo(2); // products.name size, orders comment
        assertThat(v2.get("summary").get("missing").asInt()).isEqualTo(5); // view + 4 columns
        assertThat(objects("").get("totalItems").asInt()).isEqualTo(54);
        JsonNode view = object("\"sales\".\"v_order_summary\"");
        assertThat(view.get("status").asString()).isEqualTo("MISSING");
        assertThat(view.get("missingSinceSnapshotId").asString()).isEqualTo(v2.get("id").asString());
        assertThat(object("\"inventory\".\"products\".\"name\"").get("attributes").get("size").asInt()).isEqualTo(200);
        assertThat(object("\"sales\".\"orders\"").get("comment").asString()).isEqualTo("Customer orders");

        JsonNode back = imported(envelope(ALL_SCHEMAS));

        assertThat(back.get("summary").get("reappeared").asInt()).isEqualTo(5);
        assertThat(back.get("summary").get("missing").asInt()).isEqualTo(6);
        assertThat(back.get("summary").get("updated").asInt()).isEqualTo(2);
        JsonNode reappeared = object("\"sales\".\"v_order_summary\"");
        assertThat(reappeared.get("id").asString()).isEqualTo(viewId);
        assertThat(reappeared.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(reappeared.has("missingSinceSnapshotId")).isFalse();
        assertThat(object("\"sales\".\"refunds\"").get("status").asString()).isEqualTo("MISSING");
        assertThat(objects("&status=MISSING").get("totalItems").asInt()).isEqualTo(6);
    }

    @Test
    void schemasOutsideThePartialScopeAreUntouched() throws Exception {
        JsonNode first = imported(envelope(ALL_SCHEMAS));

        JsonNode partial = imported(envelope(SALES, List.of("sales")));

        assertThat(partial.get("allSchemas").asBoolean()).isFalse();
        assertThat(partial.get("requestedSchemas").toString()).isEqualTo("[\"sales\"]");
        assertThat(partial.get("outcome").asString()).isEqualTo("UNCHANGED");
        assertThat(partial.get("summary").get("observed").asInt()).isEqualTo(28);
        JsonNode products = object("\"inventory\".\"products\"");
        assertThat(products.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(products.get("lastSeenSnapshotId").asString()).isEqualTo(first.get("id").asString());
    }

    @Test
    void requestedSchemaMissingFromTheResponseIsUnavailableNotDeleted() throws Exception {
        JsonNode restricted = imported(envelope(SALES_RESTRICTED, List.of("sales", "restricted")));
        assertThat(restricted.get("summary").get("unavailableSchemas").toString()).isEqualTo("[\"restricted\"]");
        assertThat(objects("&schema=restricted").get("totalItems").asInt()).isZero();

        imported(envelope(ALL_SCHEMAS));
        JsonNode lostInventory = imported(envelopeOf(payload(SALES), List.of("inventory", "sales")));

        assertThat(lostInventory.get("summary").get("unavailableSchemas").toString()).isEqualTo("[\"inventory\"]");
        assertThat(lostInventory.get("summary").get("missing").asInt()).isZero();
        assertThat(object("\"inventory\"").get("status").asString()).isEqualTo("UNAVAILABLE");
        // 19 children of inventory (4 relations, 13 columns, 2 foreign keys) keep their previous status.
        assertThat(objects("&schema=inventory&status=ACTIVE").get("totalItems").asInt()).isEqualTo(19);
    }

    @Test
    void importHistoryIsListedNewestFirst() throws Exception {
        JsonNode first = imported(envelope(ALL_SCHEMAS));
        JsonNode second = imported(envelope(ALL_SCHEMAS_V2));

        mvc.perform(get("/api/v1/data-sources/" + dataSourceId + "/snapshots").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[0].id").value(second.get("id").asString()))
                .andExpect(jsonPath("$.items[1].id").value(first.get("id").asString()));
        mvc.perform(get("/api/v1/data-sources/" + dataSourceId + "/snapshots/" + first.get("id").asString())
                        .header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.added").value(48))
                .andExpect(jsonPath("$.importedBy").exists());
    }

    @Test
    void importIsAudited() throws Exception {
        JsonNode snapshot = imported(envelope(ALL_SCHEMAS));
        String auditor = TestJwt.human(tenant, "AUDITOR").bearer();

        mvc.perform(get("/api/v1/audit-events").param("targetType", "METADATA_SNAPSHOT")
                        .param("targetId", snapshot.get("id").asString()).header(HttpHeaders.AUTHORIZATION, auditor))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].action").value("METADATA_SNAPSHOT_IMPORTED"))
                .andExpect(jsonPath("$.items[0].details.dataSourceId").value(dataSourceId))
                .andExpect(jsonPath("$.items[0].details.added").value(48))
                .andExpect(jsonPath("$.items[0].details.outcome").value("APPLIED"));
    }

    // --- Validation and errors ---

    @Test
    void snapshotOfAnotherDatabaseIsRejected() throws Exception {
        String crm = createDataSource(steward, "crm", "postgresql");
        importDocument(steward, crm, envelope(ALL_SCHEMAS))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("snapshot_mismatch"))
                .andExpect(jsonPath("$.errors[0].field").value("payload.database"));

        String mysql = createDataSource(steward, "erp", "mysql");
        importDocument(steward, mysql, envelope(ALL_SCHEMAS))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("payload.engine"));
    }

    @Test
    void archivedDataSourceRejectsImports() throws Exception {
        mvc.perform(patch("/api/v1/data-sources/" + dataSourceId).header(HttpHeaders.AUTHORIZATION, steward)
                        .header(HttpHeaders.IF_MATCH, "\"0\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isOk());

        importDocument(steward, dataSourceId, envelope(ALL_SCHEMAS))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("data_source_archived"));
    }

    @Test
    void invalidDocumentsAreRejectedWithoutSideEffects() throws Exception {
        importDocument(steward, dataSourceId, "{\"contractVersion\":")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("malformed_request"));
        importDocument(steward, dataSourceId, envelope(ALL_SCHEMAS).replace("discovery-api/0.1", "discovery-api/2.0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("unsupported_contract_version"));
        importDocument(steward, dataSourceId, envelope(ALL_SCHEMAS).replaceFirst("\\{\"contractVersion\"",
                        "{\"credentials\":\"x\",\"contractVersion\""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("snapshot_invalid"))
                .andExpect(jsonPath("$.errors[0].field").value("credentials"));
        importDocument(steward, dataSourceId, envelope(ALL_SCHEMAS)
                        .replace("\"primaryKey\":[\"sku\"]", "\"primaryKey\":[\"nope\"]"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("payload.schemas[0].tables[0].primaryKey[0]"))
                .andExpect(jsonPath("$.errors[0].message").value("is not a column of the table"));
        mvc.perform(post("/api/v1/data-sources/" + dataSourceId + "/snapshots").header(HttpHeaders.AUTHORIZATION, steward)
                        .contentType(MediaType.TEXT_PLAIN).content(envelope(ALL_SCHEMAS)))
                .andExpect(status().isUnsupportedMediaType());

        assertThat(objects("").get("totalItems").asInt()).isZero();
        mvc.perform(get("/api/v1/data-sources/" + dataSourceId + "/snapshots").header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void unknownDiscoveryFieldsAreToleratedAndCredentialsAreNeverStored() throws Exception {
        String payload = payload(ALL_SCHEMAS).replaceFirst("\\{\"database\"",
                "{\"connection\":{\"host\":\"db.internal\",\"username\":\"svc\",\"password\":\"Sup3r-Secret\"},"
                        + "\"discoveryVersion\":\"0.2.0\",\"database\"");

        imported(envelopeOf(payload, List.of()));

        long leaks = appJdbc.sql("""
                        SELECT (SELECT count(*) FROM physical_object
                                 WHERE attributes::text LIKE '%Sup3r%' OR coalesce(comment, '') LIKE '%Sup3r%')
                             + (SELECT count(*) FROM metadata_snapshot WHERE row_to_json(metadata_snapshot)::text LIKE '%Sup3r%')
                             + (SELECT count(*) FROM audit_event WHERE details::text LIKE '%Sup3r%')""")
                .query(Long.class).single();
        assertThat(leaks).isZero();
    }

    // --- Permissions and tenant isolation ---

    @Test
    void readersCannotImport() throws Exception {
        importDocument(viewer, dataSourceId, envelope(ALL_SCHEMAS))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
        importDocument(TestJwt.principal("AI", tenant.id().toString(), "STEWARD").bearer(), dataSourceId,
                envelope(ALL_SCHEMAS)).andExpect(status().isForbidden());
        String reports = TestJwt.principal("SERVICE", tenant.id().toString(), "REPORTS_CLIENT").bearer();
        imported(envelope(ALL_SCHEMAS));
        mvc.perform(get("/api/v1/data-sources/" + dataSourceId + "/objects").header(HttpHeaders.AUTHORIZATION, reports))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(48));
    }

    @Test
    void tenantsAreIsolated() throws Exception {
        imported(envelope(ALL_SCHEMAS));
        String objectId = object("\"sales\".\"orders\"").get("id").asString();
        String snapshotId = objects("").get("items").get(0).get("firstSeenSnapshotId").asString();
        Tenant other = newTenant();
        String otherSteward = TestJwt.human(other, "STEWARD", "VIEWER").bearer();
        String otherDataSource = createDataSource(otherSteward, "erp", "postgresql");

        importDocument(otherSteward, dataSourceId, envelope(ALL_SCHEMAS))
                .andExpect(status().isNotFound());
        for (String path : List.of("/objects", "/snapshots", "/objects/" + objectId, "/snapshots/" + snapshotId)) {
            mvc.perform(get("/api/v1/data-sources/" + dataSourceId + path).header(HttpHeaders.AUTHORIZATION, otherSteward))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("not_found"));
        }
        // Ids of another tenant are not reachable through one's own data source either.
        mvc.perform(get("/api/v1/data-sources/" + otherDataSource + "/objects/" + objectId)
                        .header(HttpHeaders.AUTHORIZATION, otherSteward))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/data-sources/" + otherDataSource + "/snapshots/" + snapshotId)
                        .header(HttpHeaders.AUTHORIZATION, otherSteward))
                .andExpect(status().isNotFound());

        // The same Discovery response imported by another tenant gets its own, independent ids.
        importDocument(otherSteward, otherDataSource, envelope(ALL_SCHEMAS)).andExpect(status().isCreated());
        JsonNode otherObjects = body(mvc.perform(get("/api/v1/data-sources/" + otherDataSource + "/objects?size=200")
                .header(HttpHeaders.AUTHORIZATION, otherSteward)).andReturn());
        assertThat(otherObjects.get("totalItems").asInt()).isEqualTo(48);
        otherObjects.get("items").forEach(item -> assertThat(item.get("id").asString()).isNotEqualTo(objectId));
    }

    @Test
    void unknownDataSourceIsNotFound() throws Exception {
        importDocument(steward, UUID.randomUUID().toString(), envelope(ALL_SCHEMAS))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/data-sources/" + dataSourceId + "/objects?type=INDEX")
                        .header(HttpHeaders.AUTHORIZATION, viewer))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation_failed"));
    }
}
