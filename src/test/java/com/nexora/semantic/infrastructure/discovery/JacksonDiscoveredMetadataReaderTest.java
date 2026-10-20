package com.nexora.semantic.infrastructure.discovery;

import com.nexora.semantic.domain.source.DiscoveredDatabase;
import com.nexora.semantic.domain.source.DiscoveredDatabase.Relation;
import com.nexora.semantic.domain.source.InvalidSnapshotException;
import com.nexora.semantic.domain.source.InvalidSnapshotException.Kind;
import com.nexora.semantic.domain.source.PhysicalObjectType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.nexora.semantic.support.DiscoveryFixtures.ALL_SCHEMAS;
import static com.nexora.semantic.support.DiscoveryFixtures.SALES;
import static com.nexora.semantic.support.DiscoveryFixtures.bytes;
import static com.nexora.semantic.support.DiscoveryFixtures.envelope;
import static com.nexora.semantic.support.DiscoveryFixtures.envelopeOf;
import static com.nexora.semantic.support.DiscoveryFixtures.payload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Anti-corruption adapter against real Discovery responses (audit finding I7). */
class JacksonDiscoveredMetadataReaderTest {

    private final JacksonDiscoveredMetadataReader reader = new JacksonDiscoveredMetadataReader();

    private DiscoveredDatabase read(String json) {
        return reader.read(bytes(json));
    }

    private InvalidSnapshotException rejected(String json) {
        return catchThrowableOfType(InvalidSnapshotException.class, () -> read(json));
    }

    private static Relation relation(DiscoveredDatabase db, String schema, String name) {
        return db.schemas().stream().filter(s -> s.name().equals(schema)).findFirst().orElseThrow()
                .relations().stream().filter(r -> r.name().equals(name)).findFirst().orElseThrow();
    }

    @Test
    void readsARealDiscoveryResponse() {
        DiscoveredDatabase db = read(envelope(ALL_SCHEMAS));

        assertThat(db.databaseName()).isEqualTo("erp");
        assertThat(db.engine().value()).isEqualTo("postgresql");
        assertThat(db.productVersion()).startsWith("17.6");
        assertThat(db.discoveredAt()).isEqualTo(Instant.parse("2026-10-19T17:13:00Z"));
        assertThat(db.scope().allSchemas()).isTrue();
        assertThat(db.schemas()).extracting(DiscoveredDatabase.Schema::name).containsExactly("inventory", "sales");

        List<Relation> relations = db.schemas().stream().flatMap(s -> s.relations().stream()).toList();
        assertThat(relations).extracting(Relation::type).containsOnly(PhysicalObjectType.TABLE,
                PhysicalObjectType.VIEW, PhysicalObjectType.MATERIALIZED_VIEW).hasSize(8);
        assertThat(relations.stream().mapToInt(r -> r.columns().size()).sum()).isEqualTo(33);
        assertThat(relations.stream().mapToInt(r -> r.foreignKeys().size()).sum()).isEqualTo(5);

        assertThat(relation(db, "inventory", "mv_stock_totals").type()).isEqualTo(PhysicalObjectType.MATERIALIZED_VIEW);
        assertThat(relation(db, "sales", "v_order_summary").type()).isEqualTo(PhysicalObjectType.VIEW);
        assertThat(relation(db, "sales", "v_order_summary").comment()).isEqualTo("Orders with customer e-mail");
        Relation orderLines = relation(db, "sales", "order_lines");
        assertThat(orderLines.primaryKey()).containsExactly("order_id", "line_no");
        DiscoveredDatabase.ForeignKey stockFk = orderLines.foreignKeys().stream()
                .filter(fk -> fk.name().equals("order_lines_stock_fk")).findFirst().orElseThrow();
        assertThat(stockFk.columns()).containsExactly("warehouse_code", "product_sku");
        assertThat(stockFk.targetSchema()).isEqualTo("inventory");
        assertThat(stockFk.targetColumns()).containsExactly("warehouse_code", "product_sku");
        DiscoveredDatabase.Column unitPrice = orderLines.columns().stream()
                .filter(c -> c.name().equals("unit_price")).findFirst().orElseThrow();
        assertThat(unitPrice.dataType()).isEqualTo("numeric");
        assertThat(unitPrice.size()).isEqualTo(10);
        assertThat(unitPrice.scale()).isEqualTo(2);
        assertThat(unitPrice.nullable()).isTrue();
    }

    @Test
    void ignoresRoutinesDefinitionsAndDefaultValuesOfTheRealPayload() {
        String payload = payload(ALL_SCHEMAS);
        assertThat(payload).contains("\"routines\"").contains("\"defaultValue\"");

        DiscoveredDatabase db = read(envelope(ALL_SCHEMAS));

        assertThat(db.toString()).doesNotContain("nextval").doesNotContain("format_amount");
    }

    @Test
    void toleratesUnknownFieldsInsideTheDiscoveryPayload() {
        String extended = payload(SALES)
                .replaceFirst("\\{\"database\"", "{\"futureTopLevel\":{\"x\":[1,2]},\"database\"")
                .replaceFirst("\\{\"name\":\"sales\"", "{\"owner\":\"postgres\",\"name\":\"sales\"")
                .replaceFirst("\"nullable\":false", "\"nullable\":false,\"collation\":\"C\",\"stats\":{\"n\":1}")
                .replaceFirst("\"foreignKeys\":\\[\\]", "\"foreignKeys\":[],\"rowEstimate\":1200");

        DiscoveredDatabase db = read(envelopeOf(extended, List.of("sales")));

        assertThat(db.schemas()).hasSize(1);
        assertThat(db.schemas().getFirst().relations()).hasSize(4);
    }

    @Test
    void neverKeepsCredentialsSmuggledIntoThePayload() {
        String withConnection = payload(SALES).replaceFirst("\\{\"database\"",
                "{\"connection\":{\"host\":\"db.internal\",\"username\":\"u\",\"password\":\"Sup3r-Secret\"},\"database\"");

        DiscoveredDatabase db = read(envelopeOf(withConnection, List.of("sales")));

        assertThat(db.toString()).doesNotContain("Sup3r-Secret").doesNotContain("db.internal");
    }

    @Test
    void theEnvelopeIsStrict() {
        String json = envelope(SALES, List.of("sales")).replaceFirst("\\{\"contractVersion\"",
                "{\"password\":\"x\",\"contractVersion\"");
        InvalidSnapshotException e = rejected(json);
        assertThat(e.kind()).isEqualTo(Kind.INVALID);
        assertThat(e.violations()).contains(Map.of("field", "password", "message", "unknown property"));
    }

    @Test
    void unsupportedContractVersion() {
        InvalidSnapshotException e = rejected(envelope(SALES).replace("discovery-api/0.1", "discovery-api/9.0"));
        assertThat(e.kind()).isEqualTo(Kind.UNSUPPORTED_CONTRACT_VERSION);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not json", "{\"contractVersion\":", "[1,2]", "{} {}"})
    void malformedOrNonObjectDocuments(String json) {
        InvalidSnapshotException e = rejected(json);
        assertThat(e.kind()).isIn(Kind.MALFORMED, Kind.INVALID);
    }

    @Test
    void envelopeFieldsAreRequiredAndValidated() {
        InvalidSnapshotException e = rejected("{\"discoveredAt\":\"yesterday\",\"requestedSchemas\":\"sales\"}");
        assertThat(e.violations()).extracting(v -> v.get("field"))
                .contains("contractVersion", "discoveredAt", "requestedSchemas", "payload");
    }

    @Test
    void schemasOutsideTheRequestedScopeAreRejected() {
        InvalidSnapshotException e = rejected(envelope(ALL_SCHEMAS, List.of("sales")));
        assertThat(e.violations()).contains(
                Map.of("field", "payload.schemas[0].name", "message", "was not requested (requestedSchemas)"));
    }

    @Test
    void emptyRequestedSchemasMeansAllSchemas() {
        String json = envelope(ALL_SCHEMAS).replace("\"discoveredAt\"", "\"requestedSchemas\":[],\"discoveredAt\"");
        assertThat(read(json).scope().allSchemas()).isTrue();
    }

    @Test
    void structuralViolationsReportPathsWithoutValues() {
        String broken = payload(SALES)
                .replaceFirst("\"primaryKey\":\\[\"id\"\\]", "\"primaryKey\":[\"no_such_column\"]")
                .replace("\"name\":\"email\"", "\"name\":\"SECRET-email\\u0007\"")
                .replaceFirst("\"targetColumns\":\\[\"id\"\\]", "\"targetColumns\":[\"id\",\"extra\"]")
                .replaceFirst(",\"nullable\":true", "");

        InvalidSnapshotException e = rejected(envelopeOf(broken, List.of("sales")));

        assertThat(e.kind()).isEqualTo(Kind.INVALID);
        assertThat(e.violations()).extracting(v -> v.get("field")).contains(
                "payload.schemas[0].tables[0].primaryKey[0]",
                "payload.schemas[0].tables[0].columns[1].name");
        assertThat(e.violations()).anySatisfy(v -> assertThat(v.get("field")).endsWith(".targetColumns"));
        assertThat(e.violations()).anySatisfy(v -> assertThat(v.get("field")).endsWith(".nullable"));
        assertThat(e.violations().toString()).doesNotContain("SECRET").doesNotContain("no_such_column");
    }

    @Test
    void duplicateNamesAreRejected() {
        String duplicated = payload(SALES).replaceFirst("\"name\":\"order_lines\"", "\"name\":\"customers\"");
        InvalidSnapshotException e = rejected(envelopeOf(duplicated, List.of("sales")));
        assertThat(e.violations()).anySatisfy(v -> assertThat(v.get("message")).isEqualTo("is duplicated within the schema"));
    }

    @Test
    void commentsAreSanitizedAndTruncated() {
        String longComment = "x".repeat(2500);
        String json = payload(SALES).replace("\"comment\":\"Orders with customer e-mail\"",
                "\"comment\":\"line1\\nline2\\u0000\\u001b[31m" + longComment + "\"");

        Relation view = relation(read(envelopeOf(json, List.of("sales"))), "sales", "v_order_summary");

        assertThat(view.comment()).startsWith("line1\nline2[31m").hasSize(JacksonDiscoveredMetadataReader.MAX_COMMENT_LENGTH)
                .doesNotContain("\u0000").doesNotContain("\u001b");
    }

    @Test
    void wrongJsonTypesInThePayloadAreReportedAsContractViolations() {
        String json = payload(SALES).replaceFirst("\"position\":1", "\"position\":\"first\"");
        InvalidSnapshotException e = rejected(envelopeOf(json, List.of("sales")));
        assertThat(e.kind()).isEqualTo(Kind.INVALID);
        assertThatThrownBy(() -> read(envelopeOf(json, List.of("sales")))).isInstanceOf(InvalidSnapshotException.class);
    }
}
