package com.nexora.semantic.domain.relationship;

import com.nexora.semantic.domain.catalog.DefinitionIssue;
import com.nexora.semantic.domain.catalog.DefinitionIssue.Code;
import com.nexora.semantic.domain.source.NaturalKey;
import com.nexora.semantic.domain.source.PhysicalObject;
import com.nexora.semantic.domain.source.PhysicalObjectStatus;
import com.nexora.semantic.domain.source.PhysicalObjectType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a FOREIGN_KEY physical object into the source, target and column pairs of a relationship. S2 stores the
 * foreign key's columns and target by name, so they are resolved by exact (case-sensitive) natural key within the same
 * data source. When something cannot be resolved (typically a target schema that was not imported), nothing is
 * guessed: the result carries the reason.
 */
public final class ForeignKeyResolver {

    /** Outcome: either {@link #pairs()} is non-empty and {@link #issue()} is null, or the opposite. */
    public record Resolution(UUID foreignKeyId, UUID sourceObjectId, UUID targetObjectId, List<ColumnPair> pairs,
                             DefinitionIssue issue) {

        public boolean resolved() {
            return issue == null;
        }

        static Resolution failed(UUID foreignKeyId, Code code, String field, String message) {
            return new Resolution(foreignKeyId, null, null, List.of(), new DefinitionIssue(code, field, message));
        }
    }

    private static final EnumSet<PhysicalObjectType> RELATIONS =
            EnumSet.of(PhysicalObjectType.TABLE, PhysicalObjectType.VIEW, PhysicalObjectType.MATERIALIZED_VIEW);

    private final Map<UUID, PhysicalObject> byId = new HashMap<>();
    private final Map<String, List<PhysicalObject>> byNaturalKey = new HashMap<>();

    /** @param objects physical objects of one tenant and data source (at least those named by the foreign key) */
    public ForeignKeyResolver(Collection<PhysicalObject> objects) {
        for (PhysicalObject object : objects) {
            byId.put(object.id(), object);
            byNaturalKey.computeIfAbsent(object.naturalKey().value(), k -> new ArrayList<>()).add(object);
        }
    }

    /** Natural keys needed to resolve {@code fk} whose source table is {@code source}. */
    public static Set<NaturalKey> requiredNaturalKeys(PhysicalObject fk, PhysicalObject source) {
        Set<NaturalKey> keys = new LinkedHashSet<>();
        Map<String, Object> attributes = fk.attributes().values();
        if (source != null && source.objectName() != null) {
            RelationshipValidator.strings(attributes.get(RelationshipValidator.FK_COLUMNS))
                    .forEach(c -> keys.add(NaturalKey.column(source.schemaName(), source.objectName(), c)));
        }
        if (attributes.get(RelationshipValidator.FK_TARGET_SCHEMA) instanceof String schema
                && attributes.get(RelationshipValidator.FK_TARGET_TABLE) instanceof String table) {
            keys.add(NaturalKey.relation(schema, table));
            RelationshipValidator.strings(attributes.get(RelationshipValidator.FK_TARGET_COLUMNS))
                    .forEach(c -> keys.add(NaturalKey.column(schema, table, c)));
        }
        return keys;
    }

    public Resolution resolve(UUID foreignKeyId) {
        PhysicalObject fk = byId.get(foreignKeyId);
        if (fk == null) {
            return Resolution.failed(foreignKeyId, Code.REFERENCE_NOT_FOUND, "foreignKeyId",
                    "no such object in this data source");
        }
        if (fk.type() != PhysicalObjectType.FOREIGN_KEY) {
            return Resolution.failed(foreignKeyId, Code.REFERENCE_WRONG_TYPE, "foreignKeyId",
                    "must reference a foreign key");
        }
        if (fk.status() != PhysicalObjectStatus.ACTIVE) {
            return Resolution.failed(foreignKeyId, Code.REFERENCE_MISSING, "foreignKeyId",
                    "references an object missing from the latest metadata import");
        }
        PhysicalObject source = byId.get(fk.parentId());
        if (source == null || !RELATIONS.contains(source.type()) || source.status() != PhysicalObjectStatus.ACTIVE) {
            return Resolution.failed(foreignKeyId, Code.REFERENCE_UNRESOLVABLE, "foreignKey.source",
                    "the table that owns the foreign key is not active in the imported metadata");
        }
        Map<String, Object> attributes = fk.attributes().values();
        List<String> columns = RelationshipValidator.strings(attributes.get(RelationshipValidator.FK_COLUMNS));
        List<String> targetColumns =
                RelationshipValidator.strings(attributes.get(RelationshipValidator.FK_TARGET_COLUMNS));
        if (columns.isEmpty() || columns.size() != targetColumns.size()
                || columns.size() > SemanticRelationship.MAX_PAIRS
                || !(attributes.get(RelationshipValidator.FK_TARGET_SCHEMA) instanceof String targetSchema)
                || !(attributes.get(RelationshipValidator.FK_TARGET_TABLE) instanceof String targetTable)) {
            return Resolution.failed(foreignKeyId, Code.REFERENCE_UNRESOLVABLE, "foreignKey",
                    "the foreign key metadata does not describe a usable column mapping");
        }
        PhysicalObject target = active(NaturalKey.relation(targetSchema, targetTable), RELATIONS);
        if (target == null) {
            return Resolution.failed(foreignKeyId, Code.REFERENCE_UNRESOLVABLE, "foreignKey.target",
                    "the referenced table is not active in the imported metadata (schema not imported or excluded)");
        }
        List<ColumnPair> pairs = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            PhysicalObject left = active(NaturalKey.column(source.schemaName(), source.objectName(), columns.get(i)),
                    EnumSet.of(PhysicalObjectType.COLUMN));
            if (left == null) {
                return Resolution.failed(foreignKeyId, Code.REFERENCE_UNRESOLVABLE, "foreignKey.columns[" + i + "]",
                        "the column is not active in the imported metadata");
            }
            PhysicalObject right = active(NaturalKey.column(targetSchema, targetTable, targetColumns.get(i)),
                    EnumSet.of(PhysicalObjectType.COLUMN));
            if (right == null) {
                return Resolution.failed(foreignKeyId, Code.REFERENCE_UNRESOLVABLE,
                        "foreignKey.targetColumns[" + i + "]", "the column is not active in the imported metadata");
            }
            pairs.add(new ColumnPair(left.id(), right.id()));
        }
        return new Resolution(foreignKeyId, source.id(), target.id(), pairs, null);
    }

    private PhysicalObject active(NaturalKey key, EnumSet<PhysicalObjectType> types) {
        return byNaturalKey.getOrDefault(key.value(), List.of()).stream()
                .filter(o -> types.contains(o.type()) && o.status() == PhysicalObjectStatus.ACTIVE)
                .findFirst().orElse(null);
    }
}
