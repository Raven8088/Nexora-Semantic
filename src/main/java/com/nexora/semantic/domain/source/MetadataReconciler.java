package com.nexora.semantic.domain.source;

import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DiscoveredDatabase.Column;
import com.nexora.semantic.domain.source.DiscoveredDatabase.ForeignKey;
import com.nexora.semantic.domain.source.DiscoveredDatabase.Relation;
import com.nexora.semantic.domain.source.DiscoveredDatabase.Schema;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Reconciles one Discovery result with the objects already known for a data source. Pure domain logic:
 * <ul>
 *   <li>an observed object keeps its id (same type and natural key) and becomes ACTIVE; new ones get a new id;</li>
 *   <li>an unobserved object becomes MISSING only if its schema is present in the payload; objects of schemas
 *       outside the requested scope are untouched;</li>
 *   <li>a schema in scope but absent from the payload becomes UNAVAILABLE (possibly a privilege loss) and its
 *       children keep their status;</li>
 *   <li>nothing is ever deleted.</li>
 * </ul>
 * Objects to insert are ordered schemas → relations → columns → foreign keys, so parents always precede children.
 */
public final class MetadataReconciler {

    /** @param toInsert new objects; @param toUpdate known objects whose state or last-seen snapshot changed */
    public record Result(MetadataSnapshot snapshot, List<PhysicalObject> toInsert, List<PhysicalObject> toUpdate) {
    }

    private record ObjectKey(PhysicalObjectType type, NaturalKey naturalKey) {
    }

    private record Observation(ObjectKey key, ObjectKey parent, String schema, String object, String child,
                               ObjectAttributes attributes, String comment, String fingerprint) {
    }

    public Result reconcile(TenantId tenantId, DataSourceId dataSourceId, DiscoveredDatabase database,
                            List<PhysicalObject> existing, UUID snapshotId, String importedBy, Instant now,
                            Supplier<UUID> ids) {
        List<Observation> observations = observe(database);
        Map<ObjectKey, PhysicalObject> known = new HashMap<>();
        Map<ObjectKey, UUID> resolvedIds = new HashMap<>();
        for (PhysicalObject object : existing) {
            ObjectKey key = new ObjectKey(object.type(), object.naturalKey());
            known.put(key, object);
            resolvedIds.put(key, object.id());
        }

        List<PhysicalObject> toInsert = new ArrayList<>();
        List<PhysicalObject> toUpdate = new ArrayList<>();
        Set<ObjectKey> observed = new HashSet<>();
        int added = 0;
        int updated = 0;
        int unchanged = 0;
        int reappeared = 0;
        for (Observation observation : observations) {
            if (!observed.add(observation.key())) {
                throw new InvalidSnapshotException(InvalidSnapshotException.Kind.INVALID, "Duplicate object in snapshot",
                        List.of(Map.of("field", "payload.schemas", "message", "contains duplicate objects")));
            }
            UUID parentId = observation.parent() == null ? null
                    : Objects.requireNonNull(resolvedIds.get(observation.parent()), "parent observed first");
            PhysicalObject current = known.get(observation.key());
            if (current == null) {
                PhysicalObject created = new PhysicalObject(ids.get(), tenantId, dataSourceId, observation.key().type(),
                        parentId, observation.schema(), observation.object(), observation.child(),
                        observation.key().naturalKey(), observation.attributes(), observation.comment(),
                        observation.fingerprint(), PhysicalObjectStatus.ACTIVE, snapshotId, snapshotId, null, now, now);
                toInsert.add(created);
                resolvedIds.put(observation.key(), created.id());
                added++;
            } else {
                boolean changed = !current.fingerprint().equals(observation.fingerprint())
                        || !Objects.equals(current.parentId(), parentId);
                if (!current.isActive()) {
                    reappeared++;
                } else if (changed) {
                    updated++;
                } else {
                    unchanged++;
                }
                toUpdate.add(current.observed(parentId, observation.attributes(), observation.comment(),
                        observation.fingerprint(), snapshotId, now));
            }
        }

        Set<String> presentSchemas = new HashSet<>();
        database.schemas().forEach(schema -> presentSchemas.add(schema.name()));
        Set<String> unavailableSchemas = unavailableSchemas(database, existing, presentSchemas);
        int missing = 0;
        int newlyUnavailable = 0;
        for (PhysicalObject object : existing) {
            if (observed.contains(new ObjectKey(object.type(), object.naturalKey()))) {
                continue;
            }
            if (object.type() == PhysicalObjectType.SCHEMA && unavailableSchemas.contains(object.schemaName())) {
                if (object.status() != PhysicalObjectStatus.UNAVAILABLE) {
                    toUpdate.add(object.unavailable(now));
                    newlyUnavailable++;
                }
            } else if (presentSchemas.contains(object.schemaName()) && object.status() != PhysicalObjectStatus.MISSING) {
                toUpdate.add(object.missing(snapshotId, now));
                missing++;
            }
        }

        MetadataSnapshot.Summary summary = new MetadataSnapshot.Summary(observations.size(), added, updated, unchanged,
                missing, reappeared, List.copyOf(unavailableSchemas));
        boolean applied = added + updated + missing + reappeared + newlyUnavailable > 0;
        MetadataSnapshot snapshot = new MetadataSnapshot(snapshotId, tenantId, dataSourceId,
                database.contractVersion(), database.engine(), database.databaseName(), database.productVersion(),
                database.discoveredAt(), database.scope().requestedSchemas(), contentHash(observations),
                applied ? MetadataSnapshot.Outcome.APPLIED : MetadataSnapshot.Outcome.UNCHANGED, summary,
                importedBy, now);
        return new Result(snapshot, toInsert, toUpdate);
    }

    /**
     * Requested schemas absent from the payload. For a full import (no explicit scope) every previously known
     * schema that is now absent counts, since a full import cannot tell a dropped schema from a revoked one.
     */
    private static Set<String> unavailableSchemas(DiscoveredDatabase database, List<PhysicalObject> existing,
                                                  Set<String> presentSchemas) {
        Set<String> candidates = new TreeSet<>(database.scope().requestedSchemas());
        if (database.scope().allSchemas()) {
            existing.stream()
                    .filter(object -> object.type() == PhysicalObjectType.SCHEMA)
                    .forEach(object -> candidates.add(object.schemaName()));
        }
        candidates.removeAll(presentSchemas);
        return candidates;
    }

    private static List<Observation> observe(DiscoveredDatabase database) {
        List<Observation> schemas = new ArrayList<>();
        List<Observation> relations = new ArrayList<>();
        List<Observation> columns = new ArrayList<>();
        List<Observation> foreignKeys = new ArrayList<>();
        for (Schema schema : database.schemas()) {
            ObjectKey schemaKey = new ObjectKey(PhysicalObjectType.SCHEMA, NaturalKey.schema(schema.name()));
            schemas.add(observation(schemaKey, null, schema.name(), null, null, ObjectAttributes.NONE, null));
            for (Relation relation : schema.relations()) {
                ObjectKey relationKey = new ObjectKey(relation.type(),
                        NaturalKey.relation(schema.name(), relation.name()));
                Map<String, Object> relationAttributes = new LinkedHashMap<>();
                if (!relation.primaryKey().isEmpty()) {
                    relationAttributes.put("primaryKey", relation.primaryKey());
                }
                relations.add(observation(relationKey, schemaKey, schema.name(), relation.name(), null,
                        ObjectAttributes.of(relationAttributes), relation.comment()));
                for (Column column : relation.columns()) {
                    Map<String, Object> attributes = new LinkedHashMap<>();
                    attributes.put("position", column.position());
                    attributes.put("dataType", column.dataType());
                    attributes.put("size", column.size());
                    attributes.put("scale", column.scale());
                    attributes.put("nullable", column.nullable());
                    columns.add(observation(new ObjectKey(PhysicalObjectType.COLUMN,
                                    NaturalKey.column(schema.name(), relation.name(), column.name())), relationKey,
                            schema.name(), relation.name(), column.name(), ObjectAttributes.of(attributes),
                            column.comment()));
                }
                for (ForeignKey foreignKey : relation.foreignKeys()) {
                    Map<String, Object> attributes = new LinkedHashMap<>();
                    attributes.put("columns", foreignKey.columns());
                    attributes.put("targetSchema", foreignKey.targetSchema());
                    attributes.put("targetTable", foreignKey.targetTable());
                    attributes.put("targetColumns", foreignKey.targetColumns());
                    foreignKeys.add(observation(new ObjectKey(PhysicalObjectType.FOREIGN_KEY,
                                    NaturalKey.foreignKey(schema.name(), relation.name(), foreignKey.name())),
                            relationKey, schema.name(), relation.name(), foreignKey.name(),
                            ObjectAttributes.of(attributes), null));
                }
            }
        }
        List<Observation> all = new ArrayList<>(schemas);
        all.addAll(relations);
        all.addAll(columns);
        all.addAll(foreignKeys);
        return all;
    }

    private static Observation observation(ObjectKey key, ObjectKey parent, String schema, String object, String child,
                                           ObjectAttributes attributes, String comment) {
        return new Observation(key, parent, schema, object, child, attributes, comment, attributes.fingerprint(comment));
    }

    private static String contentHash(List<Observation> observations) {
        TreeSet<String> lines = new TreeSet<>();
        observations.forEach(o -> lines.add(o.key().type() + "|" + o.key().naturalKey() + "|" + o.fingerprint()));
        return ObjectAttributes.sha256(String.join("\n", lines));
    }
}
