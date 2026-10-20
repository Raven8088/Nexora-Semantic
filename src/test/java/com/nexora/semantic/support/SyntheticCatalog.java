package com.nexora.semantic.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Synthetic Discovery payload (same shape as the real {@code discovery-api/0.1} responses of {@link DiscoveryFixtures})
 * with the S3.1 examples that the captured ERP database does not contain:
 * <ul>
 *   <li>{@code plant.equipment(id PK, dbid, name)} and {@code plant.measurements(equipment_dbid, ...)}: joined by
 *       {@code dbid}, which is neither PK, UNIQUE nor FK, while {@code equipment.id} exists;</li>
 *   <li>{@code finance.invoices.client_code = finance.clients.account_number} (different names);</li>
 *   <li>composite and cross-schema: {@code finance.transactions(company_code, document_number)} =
 *       {@code docs.documents(company_code, number)} (whose PK is exactly those two columns);</li>
 *   <li>{@code plant.sensors}: a resolvable FK to {@code plant.equipment(id)}, an FK to a schema that is not imported
 *       ({@code procurement}) and an FK to {@code plant."Equipment"} (wrong case: must not resolve).</li>
 * </ul>
 * Mutators return a modified copy to simulate metadata changes between imports.
 */
public final class SyntheticCatalog {

    private record Column(String name, String type, int size, int scale, boolean nullable, String comment) {
    }

    private record ForeignKey(String name, List<String> columns, String targetSchema, String targetTable,
                              List<String> targetColumns) {
    }

    private record Table(String name, String comment, List<Column> columns, List<String> primaryKey,
                         List<ForeignKey> foreignKeys) {
    }

    private final Map<String, List<Table>> schemas;

    private SyntheticCatalog(Map<String, List<Table>> schemas) {
        this.schemas = schemas;
    }

    public static SyntheticCatalog standard() {
        Map<String, List<Table>> schemas = new LinkedHashMap<>();
        schemas.put("plant", List.of(
                new Table("equipment", "Plant equipment", List.of(
                        col("id", "int8", false), col("dbid", "int8", false), text("name", 100)),
                        List.of("id"), List.of()),
                new Table("measurements", "Sensor readings", List.of(
                        col("id", "int8", false),
                        new Column("equipment_dbid", "int8", 19, 0, false, "Equipment that produced the reading"),
                        new Column("value", "numeric", 12, 3, true, null),
                        new Column("measured_at", "timestamptz", 35, 6, false, null),
                        text("note", 200)),
                        List.of("id"), List.of()),
                new Table("sensors", null, List.of(
                        col("id", "int8", false), col("equipment_id", "int8", false), text("vendor_code", 10),
                        text("label", 50)),
                        List.of("id"), List.of(
                        new ForeignKey("sensors_equipment_fk", List.of("equipment_id"), "plant", "equipment",
                                List.of("id")),
                        new ForeignKey("sensors_vendor_fk", List.of("vendor_code"), "procurement", "vendors",
                                List.of("code")),
                        new ForeignKey("sensors_case_fk", List.of("equipment_id"), "plant", "Equipment",
                                List.of("id"))))));
        schemas.put("finance", List.of(
                new Table("clients", null, List.of(col("id", "int8", false), text("account_number", 20)),
                        List.of("id"), List.of()),
                new Table("invoices", null, List.of(
                        col("id", "int8", false), text("client_code", 20),
                        new Column("amount", "numeric", 12, 2, false, null),
                        new Column("issued_on", "date", 13, 0, false, null)),
                        List.of("id"), List.of()),
                new Table("transactions", null, List.of(
                        col("id", "int8", false), new Column("company_code", "bpchar", 4, 0, false, null),
                        text("document_number", 20), new Column("amount", "numeric", 12, 2, false, null)),
                        List.of("id"), List.of())));
        schemas.put("docs", List.of(
                new Table("documents", null, List.of(
                        new Column("company_code", "bpchar", 4, 0, false, null), text("number", 20),
                        text("title", 200)),
                        List.of("company_code", "number"), List.of())));
        return new SyntheticCatalog(schemas);
    }

    public SyntheticCatalog withoutColumn(String schema, String table, String column) {
        return map(schema, table, t -> new Table(t.name(), t.comment(),
                t.columns().stream().filter(c -> !c.name().equals(column)).toList(), t.primaryKey(),
                t.foreignKeys()));
    }

    public SyntheticCatalog withColumnType(String schema, String table, String column, String type) {
        return map(schema, table, t -> new Table(t.name(), t.comment(), t.columns().stream()
                .map(c -> c.name().equals(column) ? new Column(c.name(), type, c.size(), c.scale(), c.nullable(),
                        c.comment()) : c).toList(), t.primaryKey(), t.foreignKeys()));
    }

    public SyntheticCatalog withoutForeignKey(String schema, String table, String foreignKey) {
        return map(schema, table, t -> new Table(t.name(), t.comment(), t.columns(), t.primaryKey(),
                t.foreignKeys().stream().filter(f -> !f.name().equals(foreignKey)).toList()));
    }

    public SyntheticCatalog withoutTable(String schema, String table) {
        Map<String, List<Table>> copy = new LinkedHashMap<>(schemas);
        copy.put(schema, copy.get(schema).stream().filter(t -> !t.name().equals(table)).toList());
        return new SyntheticCatalog(copy);
    }

    /** Import envelope (full import of every schema). */
    public String envelope() {
        return DiscoveryFixtures.envelopeOf(payload(), List.of());
    }

    public String payload() {
        return "{\"database\":\"erp\",\"engine\":\"postgresql\",\"productVersion\":\"17.6\",\"schemas\":"
                + schemas.entrySet().stream().map(e -> "{\"name\":" + str(e.getKey()) + ",\"tables\":"
                        + e.getValue().stream().map(SyntheticCatalog::table).collect(joining()) + ",\"views\":[]}")
                .collect(joining()) + "}";
    }

    private SyntheticCatalog map(String schema, String table, java.util.function.UnaryOperator<Table> change) {
        Map<String, List<Table>> copy = new LinkedHashMap<>(schemas);
        copy.put(schema, copy.get(schema).stream().map(t -> t.name().equals(table) ? change.apply(t) : t).toList());
        return new SyntheticCatalog(copy);
    }

    private static String table(Table t) {
        List<String> columns = new ArrayList<>();
        for (int i = 0; i < t.columns().size(); i++) {
            Column c = t.columns().get(i);
            columns.add("{\"name\":" + str(c.name()) + ",\"position\":" + (i + 1) + ",\"dataType\":" + str(c.type())
                    + ",\"size\":" + c.size() + ",\"scale\":" + c.scale() + ",\"nullable\":" + c.nullable()
                    + ",\"autoIncrement\":false" + (c.comment() == null ? "" : ",\"comment\":" + str(c.comment()))
                    + "}");
        }
        String foreignKeys = t.foreignKeys().stream().map(f -> "{\"name\":" + str(f.name()) + ",\"sourceTable\":"
                + str(t.name()) + ",\"sourceColumns\":" + strings(f.columns()) + ",\"targetSchema\":"
                + str(f.targetSchema()) + ",\"targetTable\":" + str(f.targetTable()) + ",\"targetColumns\":"
                + strings(f.targetColumns()) + "}").collect(joining());
        return "{\"name\":" + str(t.name()) + (t.comment() == null ? "" : ",\"comment\":" + str(t.comment()))
                + ",\"columns\":[" + String.join(",", columns) + "],\"primaryKey\":" + strings(t.primaryKey())
                + ",\"foreignKeys\":" + foreignKeys + "}";
    }

    private static Column col(String name, String type, boolean nullable) {
        return new Column(name, type, 19, 0, nullable, null);
    }

    private static Column text(String name, int size) {
        return new Column(name, "varchar", size, 0, true, null);
    }

    private static String strings(List<String> values) {
        return values.stream().map(SyntheticCatalog::str).collect(joining());
    }

    private static java.util.stream.Collector<CharSequence, ?, String> joining() {
        return Collectors.joining(",", "[", "]");
    }

    private static String str(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
