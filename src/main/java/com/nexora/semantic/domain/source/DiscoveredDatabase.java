package com.nexora.semantic.domain.source;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Semantic's own, vendor-neutral representation of one Discovery result (the anti-corruption layer maps
 * Discovery's JSON into it). Only what Semantic needs is kept: no SQL definitions, default values or routines.
 * Instances are produced by the adapter after structural validation; invariants here are a second line of defense.
 *
 * @param contractVersion version of the import contract the payload arrived with
 * @param discoveredAt    when Discovery produced the payload, if the importer supplied it
 * @param scope           schemas that were requested from Discovery
 */
public record DiscoveredDatabase(
        String contractVersion,
        String databaseName,
        EngineCode engine,
        String productVersion,
        Instant discoveredAt,
        SnapshotScope scope,
        List<Schema> schemas) {

    public DiscoveredDatabase {
        Objects.requireNonNull(contractVersion, "contractVersion");
        Objects.requireNonNull(databaseName, "databaseName");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(scope, "scope");
        schemas = List.copyOf(schemas);
    }

    /**
     * @param requestedSchemas schemas requested from Discovery; empty means every accessible schema
     */
    public record SnapshotScope(Set<String> requestedSchemas) {

        public static final SnapshotScope ALL = new SnapshotScope(Set.of());

        public SnapshotScope {
            requestedSchemas = Set.copyOf(requestedSchemas);
        }

        public boolean allSchemas() {
            return requestedSchemas.isEmpty();
        }

        public boolean covers(String schema) {
            return allSchemas() || requestedSchemas.contains(schema);
        }
    }

    public record Schema(String name, List<Relation> relations) {
        public Schema {
            Objects.requireNonNull(name, "name");
            relations = List.copyOf(relations);
        }
    }

    /**
     * Table, view or materialized view.
     *
     * @param primaryKey ordered primary key columns (empty for views and tables without one)
     */
    public record Relation(
            PhysicalObjectType type,
            String name,
            String comment,
            List<Column> columns,
            List<String> primaryKey,
            List<ForeignKey> foreignKeys) {

        public Relation {
            Objects.requireNonNull(type, "type");
            if (!type.isRelation()) {
                throw new IllegalArgumentException("not a relation type: " + type);
            }
            Objects.requireNonNull(name, "name");
            columns = List.copyOf(columns);
            primaryKey = List.copyOf(primaryKey);
            foreignKeys = List.copyOf(foreignKeys);
        }
    }

    /** @param size character length or numeric precision; null when not applicable */
    public record Column(String name, int position, String dataType, Integer size, Integer scale, boolean nullable,
                         String comment) {
        public Column {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(dataType, "dataType");
        }
    }

    /** Outgoing foreign key; the target may lie in a schema that was not imported. */
    public record ForeignKey(String name, List<String> columns, String targetSchema, String targetTable,
                             List<String> targetColumns) {
        public ForeignKey {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(targetSchema, "targetSchema");
            Objects.requireNonNull(targetTable, "targetTable");
            columns = List.copyOf(columns);
            targetColumns = List.copyOf(targetColumns);
        }
    }
}
