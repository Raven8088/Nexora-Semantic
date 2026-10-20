package com.nexora.semantic.domain.source;

import java.util.Objects;

/**
 * Stable, exact (case-sensitive) identity of a physical object inside a data source, independent of Discovery's
 * DTOs. Each identifier is double-quoted with embedded quotes doubled, as in SQL, so no name can forge a separator:
 * <ul>
 *   <li>schema: {@code "sales"}</li>
 *   <li>table/view: {@code "sales"."orders"}</li>
 *   <li>column: {@code "sales"."orders"."amount"}</li>
 *   <li>foreign key: {@code "sales"."order_lines"#"order_lines_order_fk"}</li>
 * </ul>
 */
public record NaturalKey(String value) {

    public NaturalKey {
        Objects.requireNonNull(value, "value");
    }

    public static NaturalKey schema(String schema) {
        return new NaturalKey(quote(schema));
    }

    public static NaturalKey relation(String schema, String relation) {
        return new NaturalKey(quote(schema) + "." + quote(relation));
    }

    public static NaturalKey column(String schema, String relation, String column) {
        return new NaturalKey(quote(schema) + "." + quote(relation) + "." + quote(column));
    }

    public static NaturalKey foreignKey(String schema, String table, String foreignKey) {
        return new NaturalKey(quote(schema) + "." + quote(table) + "#" + quote(foreignKey));
    }

    static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public String toString() {
        return value;
    }
}
