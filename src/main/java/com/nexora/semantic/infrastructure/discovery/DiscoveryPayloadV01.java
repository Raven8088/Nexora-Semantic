package com.nexora.semantic.infrastructure.discovery;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Tolerant read model of the Nexora Discovery {@code POST /api/discovery} 200 response, contract observed in
 * Discovery 0.1.0-SNAPSHOT ({@code API.md}, {@code DiscoveryResponse}). Only the fields Semantic consumes are
 * declared; anything else (routines, SQL {@code definition}, {@code defaultValue}, {@code autoIncrement}, future
 * fields) is ignored by design (audit finding I7). Wrapper types are used so that missing values can be reported
 * as violations instead of silently defaulting.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record DiscoveryPayloadV01(String database, String engine, String productVersion, List<Schema> schemas) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Schema(String name, List<Table> tables, List<View> views) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Table(String name, String comment, List<Column> columns, List<String> primaryKey,
                 List<ForeignKey> foreignKeys) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record View(String name, String comment, Boolean materialized, List<Column> columns) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Column(String name, Integer position, String dataType, Integer size, Integer scale, Boolean nullable,
                  String comment) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ForeignKey(String name, String sourceTable, List<String> sourceColumns, String targetSchema,
                      String targetTable, List<String> targetColumns) {
    }
}
