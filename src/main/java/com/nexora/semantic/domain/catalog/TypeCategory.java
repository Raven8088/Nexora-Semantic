package com.nexora.semantic.domain.catalog;

import com.nexora.semantic.domain.source.EngineCode;

import java.util.Locale;
import java.util.Map;

/**
 * Engine-neutral category of a column data type, used to check that aggregations and dimensions are compatible
 * with the physical column. Only PostgreSQL type names (as reported by Nexora Discovery) are mapped; unknown engines
 * or types are {@link #OTHER}, which is never compatible with SUM, AVG or temporal dimensions (fail closed).
 */
public enum TypeCategory {
    NUMERIC,
    TEXT,
    BOOLEAN,
    DATE,
    /** Timestamp without time zone (wall-clock values). */
    TIMESTAMP,
    /** Timestamp with time zone (instants). */
    TIMESTAMP_TZ,
    TIME,
    UUID,
    OTHER;

    private static final Map<String, TypeCategory> POSTGRESQL = Map.ofEntries(
            Map.entry("int2", NUMERIC), Map.entry("int4", NUMERIC), Map.entry("int8", NUMERIC),
            Map.entry("smallint", NUMERIC), Map.entry("integer", NUMERIC), Map.entry("bigint", NUMERIC),
            Map.entry("serial", NUMERIC), Map.entry("smallserial", NUMERIC), Map.entry("bigserial", NUMERIC),
            Map.entry("serial2", NUMERIC), Map.entry("serial4", NUMERIC), Map.entry("serial8", NUMERIC),
            Map.entry("numeric", NUMERIC), Map.entry("decimal", NUMERIC), Map.entry("float4", NUMERIC),
            Map.entry("float8", NUMERIC), Map.entry("real", NUMERIC), Map.entry("double precision", NUMERIC),
            Map.entry("money", NUMERIC),
            Map.entry("varchar", TEXT), Map.entry("bpchar", TEXT), Map.entry("char", TEXT), Map.entry("text", TEXT),
            Map.entry("name", TEXT), Map.entry("citext", TEXT), Map.entry("character varying", TEXT),
            Map.entry("character", TEXT),
            Map.entry("bool", BOOLEAN), Map.entry("boolean", BOOLEAN),
            Map.entry("date", DATE),
            Map.entry("timestamp", TIMESTAMP), Map.entry("timestamp without time zone", TIMESTAMP),
            Map.entry("timestamptz", TIMESTAMP_TZ), Map.entry("timestamp with time zone", TIMESTAMP_TZ),
            Map.entry("time", TIME), Map.entry("timetz", TIME),
            Map.entry("uuid", UUID));

    public static TypeCategory of(EngineCode engine, String dataType) {
        if (dataType == null || !"postgresql".equals(engine.value())) {
            return OTHER;
        }
        return POSTGRESQL.getOrDefault(dataType.strip().toLowerCase(Locale.ROOT), OTHER);
    }

    public boolean isTemporal() {
        return this == DATE || this == TIMESTAMP || this == TIMESTAMP_TZ;
    }
}
