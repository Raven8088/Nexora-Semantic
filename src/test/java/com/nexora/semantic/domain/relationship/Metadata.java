package com.nexora.semantic.domain.relationship;

import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DataSourceId;
import com.nexora.semantic.domain.source.EngineCode;
import com.nexora.semantic.domain.source.NaturalKey;
import com.nexora.semantic.domain.source.ObjectAttributes;
import com.nexora.semantic.domain.source.PhysicalObject;
import com.nexora.semantic.domain.source.PhysicalObjectStatus;
import com.nexora.semantic.domain.source.PhysicalObjectType;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** In-memory physical metadata of one data source, shaped like what S2 stores after a Discovery import. */
final class Metadata {

    static final EngineCode POSTGRESQL = new EngineCode("postgresql");
    static final Instant NOW = Instant.parse("2026-10-20T08:00:00Z");
    static final TenantId TENANT = TenantId.newId();
    static final DataSourceId SOURCE = DataSourceId.newId();
    private static final UUID SNAPSHOT = UUID.randomUUID();

    final Map<UUID, PhysicalObject> objects = new LinkedHashMap<>();
    private final Map<String, PhysicalObject> schemas = new HashMap<>();

    PhysicalObject table(String schema, String name, String... primaryKey) {
        PhysicalObject parent = schemas.computeIfAbsent(schema, s -> put(new PhysicalObject(UUID.randomUUID(),
                TENANT, SOURCE, PhysicalObjectType.SCHEMA, null, s, null, null, NaturalKey.schema(s),
                ObjectAttributes.NONE, null, "0".repeat(64), PhysicalObjectStatus.ACTIVE, SNAPSHOT, SNAPSHOT, null,
                NOW, NOW)));
        Map<String, Object> attributes = new HashMap<>();
        if (primaryKey.length > 0) {
            attributes.put("primaryKey", List.of(primaryKey));
        }
        return put(new PhysicalObject(UUID.randomUUID(), TENANT, SOURCE, PhysicalObjectType.TABLE, parent.id(),
                schema, name, null, NaturalKey.relation(schema, name), ObjectAttributes.of(attributes), null,
                "0".repeat(64), PhysicalObjectStatus.ACTIVE, SNAPSHOT, SNAPSHOT, null, NOW, NOW));
    }

    PhysicalObject column(PhysicalObject relation, String name, String dataType) {
        return column(relation, name, dataType, null);
    }

    PhysicalObject column(PhysicalObject relation, String name, String dataType, String comment) {
        return put(new PhysicalObject(UUID.randomUUID(), TENANT, SOURCE, PhysicalObjectType.COLUMN, relation.id(),
                relation.schemaName(), relation.objectName(), name,
                NaturalKey.column(relation.schemaName(), relation.objectName(), name),
                ObjectAttributes.of(Map.of("dataType", dataType, "position", 1, "nullable", true)), comment,
                "0".repeat(64), PhysicalObjectStatus.ACTIVE, SNAPSHOT, SNAPSHOT, null, NOW, NOW));
    }

    PhysicalObject foreignKey(PhysicalObject table, String name, List<String> columns, String targetSchema,
                              String targetTable, List<String> targetColumns) {
        return put(new PhysicalObject(UUID.randomUUID(), TENANT, SOURCE, PhysicalObjectType.FOREIGN_KEY, table.id(),
                table.schemaName(), table.objectName(), name,
                NaturalKey.foreignKey(table.schemaName(), table.objectName(), name),
                ObjectAttributes.of(Map.of("columns", columns, "targetSchema", targetSchema, "targetTable",
                        targetTable, "targetColumns", targetColumns)), null, "0".repeat(64),
                PhysicalObjectStatus.ACTIVE, SNAPSHOT, SNAPSHOT, null, NOW, NOW));
    }

    PhysicalObject missing(PhysicalObject object) {
        return put(new PhysicalObject(object.id(), TENANT, SOURCE, object.type(), object.parentId(),
                object.schemaName(), object.objectName(), object.childName(), object.naturalKey(), object.attributes(),
                object.comment(), object.fingerprint(), PhysicalObjectStatus.MISSING, SNAPSHOT, SNAPSHOT,
                UUID.randomUUID(), NOW, NOW));
    }

    PhysicalObject retyped(PhysicalObject column, String dataType) {
        return put(new PhysicalObject(column.id(), TENANT, SOURCE, column.type(), column.parentId(),
                column.schemaName(), column.objectName(), column.childName(), column.naturalKey(),
                ObjectAttributes.of(Map.of("dataType", dataType, "position", 1, "nullable", true)), column.comment(),
                column.fingerprint(), PhysicalObjectStatus.ACTIVE, SNAPSHOT, SNAPSHOT, null, NOW, NOW));
    }

    private PhysicalObject put(PhysicalObject object) {
        objects.put(object.id(), object);
        return object;
    }
}
