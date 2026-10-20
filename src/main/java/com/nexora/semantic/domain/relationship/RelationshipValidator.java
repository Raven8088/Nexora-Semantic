package com.nexora.semantic.domain.relationship;

import com.nexora.semantic.domain.catalog.DefinitionIssue;
import com.nexora.semantic.domain.catalog.DefinitionIssue.Code;
import com.nexora.semantic.domain.catalog.TypeCategory;
import com.nexora.semantic.domain.source.EngineCode;
import com.nexora.semantic.domain.source.PhysicalObject;
import com.nexora.semantic.domain.source.PhysicalObjectStatus;
import com.nexora.semantic.domain.source.PhysicalObjectType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Physical validation and evidence of a relationship against the current metadata of its data source (pure domain
 * logic, no data access, no SQL). The lookup map is scoped to the tenant and data source, so an object of another
 * tenant or data source is simply "not found".
 * <p>
 * Rules: source and target are tables or views; every column exists, is a column of its side and is not MISSING;
 * both columns of a pair have compatible types; no pair is repeated or contradictory; a DECLARED_FK relationship
 * keeps a foreign key that is still observed with exactly the same columns and target. Nothing is assumed from names,
 * {@code id} columns, primary keys or foreign keys: those only feed the evidence.
 * Messages describe rules and never echo names or values.
 */
public final class RelationshipValidator {

    /** Result of a validation: issues block approval and use; evidence describes what the metadata backs. */
    public record Evaluation(List<DefinitionIssue> issues, RelationshipEvidence evidence) {

        public Evaluation {
            issues = List.copyOf(issues);
            Objects.requireNonNull(evidence, "evidence");
        }

        public boolean valid() {
            return issues.isEmpty();
        }
    }

    static final String PRIMARY_KEY = "primaryKey";
    static final String FK_COLUMNS = "columns";
    static final String FK_TARGET_SCHEMA = "targetSchema";
    static final String FK_TARGET_TABLE = "targetTable";
    static final String FK_TARGET_COLUMNS = "targetColumns";

    private static final EnumSet<PhysicalObjectType> RELATIONS =
            EnumSet.of(PhysicalObjectType.TABLE, PhysicalObjectType.VIEW, PhysicalObjectType.MATERIALIZED_VIEW);

    /**
     * @param objects physical objects of the data source by id; must contain at least the source, the target, the
     *                pair columns and the foreign key (missing entries are reported as not found)
     */
    public Evaluation evaluate(EngineCode engine, Map<UUID, PhysicalObject> objects, RelationshipOrigin origin,
                               UUID sourceObjectId, UUID targetObjectId, List<ColumnPair> pairs, UUID foreignKeyId,
                               DeclaredCardinality cardinality) {
        List<DefinitionIssue> issues = new ArrayList<>();
        PhysicalObject source = relation(objects.get(sourceObjectId), "sourceObjectId", issues);
        PhysicalObject target = relation(objects.get(targetObjectId), "targetObjectId", issues);

        List<PhysicalObject> sourceColumns = new ArrayList<>();
        List<PhysicalObject> targetColumns = new ArrayList<>();
        Set<ColumnPair> seen = new HashSet<>();
        Map<UUID, UUID> bySource = new HashMap<>();
        Map<UUID, UUID> byTarget = new HashMap<>();
        for (int i = 0; i < pairs.size(); i++) {
            ColumnPair pair = pairs.get(i);
            String field = "pairs[" + i + "]";
            PhysicalObject left = column(objects.get(pair.sourceColumnId()), sourceObjectId, field + ".sourceColumnId",
                    issues);
            PhysicalObject right = column(objects.get(pair.targetColumnId()), targetObjectId,
                    field + ".targetColumnId", issues);
            sourceColumns.add(left);
            targetColumns.add(right);
            if (!seen.add(pair)) {
                issues.add(new DefinitionIssue(Code.DUPLICATE_PAIR, field, "the same column pair is repeated"));
                continue;
            }
            if (pair.sourceColumnId().equals(pair.targetColumnId())) {
                issues.add(new DefinitionIssue(Code.CONTRADICTORY_PAIR, field,
                        "a column cannot be joined with itself"));
            }
            UUID previousTarget = bySource.putIfAbsent(pair.sourceColumnId(), pair.targetColumnId());
            UUID previousSource = byTarget.putIfAbsent(pair.targetColumnId(), pair.sourceColumnId());
            if (previousTarget != null || previousSource != null) {
                issues.add(new DefinitionIssue(Code.CONTRADICTORY_PAIR, field,
                        "each column may appear in only one pair"));
            }
            if (left != null && right != null && !compatible(engine, left, right)) {
                issues.add(new DefinitionIssue(Code.INCOMPATIBLE_TYPES, field,
                        "the columns of a pair must have compatible data types"));
            }
        }

        ForeignKeyEvidence foreignKeyEvidence = ForeignKeyEvidence.NOT_DECLARED;
        if (origin == RelationshipOrigin.DECLARED_FK) {
            boolean present = foreignKey(objects.get(foreignKeyId), source, target, sourceColumns, targetColumns,
                    issues);
            foreignKeyEvidence = present ? ForeignKeyEvidence.PRESENT : ForeignKeyEvidence.LOST;
        }
        RelationshipEvidence evidence = RelationshipEvidence.of(cardinality,
                uniqueness(source, sourceColumns), uniqueness(target, targetColumns), foreignKeyEvidence);
        return new Evaluation(issues, evidence);
    }

    public Evaluation evaluate(EngineCode engine, Map<UUID, PhysicalObject> objects, SemanticRelationship r) {
        return evaluate(engine, objects, r.origin(), r.sourceObjectId(), r.targetObjectId(), r.pairs(),
                r.foreignKeyId(), r.declaredCardinality());
    }

    /**
     * Types are compatible when both map to the same known {@link TypeCategory}; for types Semantic does not
     * categorize, only the exact same declared type is accepted (fail closed).
     */
    public static boolean compatible(EngineCode engine, PhysicalObject left, PhysicalObject right) {
        String leftType = dataType(left);
        String rightType = dataType(right);
        if (leftType == null || rightType == null) {
            return false;
        }
        TypeCategory leftCategory = TypeCategory.of(engine, leftType);
        TypeCategory rightCategory = TypeCategory.of(engine, rightType);
        if (leftCategory == TypeCategory.OTHER || rightCategory == TypeCategory.OTHER) {
            return leftType.strip().equalsIgnoreCase(rightType.strip());
        }
        return leftCategory == rightCategory;
    }

    /** PRIMARY_KEY only when the columns are exactly (as a set) the known primary key of an ACTIVE relation. */
    static UniquenessEvidence uniqueness(PhysicalObject relation, List<PhysicalObject> columns) {
        if (relation == null || relation.status() != PhysicalObjectStatus.ACTIVE
                || columns.stream().anyMatch(c -> c == null || c.status() != PhysicalObjectStatus.ACTIVE
                || !relation.id().equals(c.parentId()))) {
            return UniquenessEvidence.UNKNOWN;
        }
        List<String> primaryKey = strings(relation.attributes().values().get(PRIMARY_KEY));
        Set<String> names = new HashSet<>();
        columns.forEach(c -> names.add(c.childName()));
        return !primaryKey.isEmpty() && names.equals(new HashSet<>(primaryKey)) && names.size() == primaryKey.size()
                ? UniquenessEvidence.PRIMARY_KEY : UniquenessEvidence.UNKNOWN;
    }

    private static boolean foreignKey(PhysicalObject fk, PhysicalObject source, PhysicalObject target,
                                      List<PhysicalObject> sourceColumns, List<PhysicalObject> targetColumns,
                                      List<DefinitionIssue> issues) {
        if (fk == null) {
            issues.add(new DefinitionIssue(Code.REFERENCE_NOT_FOUND, "foreignKeyId",
                    "no such object in this data source"));
            return false;
        }
        if (fk.type() != PhysicalObjectType.FOREIGN_KEY) {
            issues.add(new DefinitionIssue(Code.REFERENCE_WRONG_TYPE, "foreignKeyId", "must reference a foreign key"));
            return false;
        }
        if (fk.status() == PhysicalObjectStatus.MISSING) {
            issues.add(new DefinitionIssue(Code.FOREIGN_KEY_LOST, "foreignKeyId",
                    "the foreign key backing this relationship is missing from the latest metadata import"));
            return false;
        }
        if (!matches(fk, source, target, sourceColumns, targetColumns)) {
            issues.add(new DefinitionIssue(Code.FOREIGN_KEY_LOST, "foreignKeyId",
                    "the foreign key no longer joins exactly these columns"));
            return false;
        }
        return true;
    }

    /** Same source table, same target table (by name) and the same column equalities, compared exactly. */
    static boolean matches(PhysicalObject fk, PhysicalObject source, PhysicalObject target,
                           List<PhysicalObject> sourceColumns, List<PhysicalObject> targetColumns) {
        if (source == null || target == null || !source.id().equals(fk.parentId())
                || sourceColumns.contains(null) || targetColumns.contains(null)) {
            return false;
        }
        Map<String, Object> attributes = fk.attributes().values();
        if (!Objects.equals(attributes.get(FK_TARGET_SCHEMA), target.schemaName())
                || !Objects.equals(attributes.get(FK_TARGET_TABLE), target.objectName())) {
            return false;
        }
        List<String> fkColumns = strings(attributes.get(FK_COLUMNS));
        List<String> fkTargetColumns = strings(attributes.get(FK_TARGET_COLUMNS));
        if (fkColumns.isEmpty() || fkColumns.size() != fkTargetColumns.size()
                || fkColumns.size() != sourceColumns.size()) {
            return false;
        }
        Set<String> declared = new HashSet<>();
        for (int i = 0; i < fkColumns.size(); i++) {
            declared.add(NamePair.of(fkColumns.get(i), fkTargetColumns.get(i)));
        }
        Set<String> actual = new HashSet<>();
        for (int i = 0; i < sourceColumns.size(); i++) {
            actual.add(NamePair.of(sourceColumns.get(i).childName(), targetColumns.get(i).childName()));
        }
        return declared.equals(actual);
    }

    private static PhysicalObject relation(PhysicalObject object, String field, List<DefinitionIssue> issues) {
        if (object == null) {
            issues.add(new DefinitionIssue(Code.REFERENCE_NOT_FOUND, field, "no such object in this data source"));
            return null;
        }
        if (!RELATIONS.contains(object.type())) {
            issues.add(new DefinitionIssue(Code.REFERENCE_WRONG_TYPE, field, "must reference a table or view"));
            return null;
        }
        missing(object, field, issues);
        return object;
    }

    private static PhysicalObject column(PhysicalObject object, UUID relationId, String field,
                                         List<DefinitionIssue> issues) {
        if (object == null) {
            issues.add(new DefinitionIssue(Code.REFERENCE_NOT_FOUND, field, "no such object in this data source"));
            return null;
        }
        if (object.type() != PhysicalObjectType.COLUMN) {
            issues.add(new DefinitionIssue(Code.REFERENCE_WRONG_TYPE, field, "must reference a column"));
            return null;
        }
        missing(object, field, issues);
        if (!relationId.equals(object.parentId())) {
            String side = field.endsWith("sourceColumnId") ? "source" : "target";
            issues.add(new DefinitionIssue(Code.COLUMN_NOT_IN_RELATION, field,
                    "must be a column of the " + side + " table or view"));
        }
        return object;
    }

    private static void missing(PhysicalObject object, String field, List<DefinitionIssue> issues) {
        if (object.status() == PhysicalObjectStatus.MISSING) {
            issues.add(new DefinitionIssue(Code.REFERENCE_MISSING, field,
                    "references an object missing from the latest metadata import"));
        }
    }

    static String dataType(PhysicalObject column) {
        Object dataType = column.attributes().values().get("dataType");
        return dataType instanceof String type ? type : null;
    }

    static List<String> strings(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    /** Unambiguous encoding of a column-name equality (names are quoted, so no name can forge the separator). */
    private static final class NamePair {
        static String of(String left, String right) {
            return quote(left) + "=" + quote(right);
        }

        private static String quote(String name) {
            return "\"" + name.replace("\"", "\"\"") + "\"";
        }
    }
}
