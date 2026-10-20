package com.nexora.semantic.domain.relationship;

import com.nexora.semantic.domain.catalog.DefinitionIssue;
import com.nexora.semantic.domain.relationship.RelationshipCandidate.Classification;
import com.nexora.semantic.domain.relationship.RelationshipCandidate.Pair;
import com.nexora.semantic.domain.relationship.RelationshipCandidate.Signal;
import com.nexora.semantic.domain.relationship.RelationshipCandidate.SignalCode;
import com.nexora.semantic.domain.source.EngineCode;
import com.nexora.semantic.domain.source.PhysicalObject;
import com.nexora.semantic.domain.source.PhysicalObjectStatus;
import com.nexora.semantic.domain.source.PhysicalObjectType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Deterministic, bounded relationship suggestions (S3.1). No data is read, no SQL is generated and no language model
 * or embedding is used: only the imported metadata.
 * <ol>
 *   <li>Every active foreign key that resolves exactly (see {@link ForeignKeyResolver}) is a STRONG candidate;
 *       foreign keys that cannot be resolved are reported with their reason instead of being guessed.</li>
 *   <li>Single-column name heuristics: a source column named {@code <table>_<column>} (also with the table name in
 *       singular) or with the same distinctive name as a target column that is the table's single-column primary
 *       key or looks like a business identifier ({@code dbid}, {@code sku}, {@code *_code}, {@code *_number}...).
 *       A column named {@code id} is never matched by its name alone, and nothing is suggested between columns
 *       of incompatible types.</li>
 * </ol>
 * Candidates equivalent to an existing DRAFT or APPROVED relationship are omitted. Work is linear in the number of
 * columns (hash lookups, no pairwise comparison) and bounded by {@link #MAX_COLUMNS} and the requested limit.
 */
public final class RelationshipCandidateFinder {

    /** Upper bound of columns examined per request; beyond it the result is marked truncated. */
    public static final int MAX_COLUMNS = 20_000;
    public static final int MAX_RESULTS = 100;

    /** Unresolvable foreign key, reported instead of an invented reference. */
    public record UnresolvedForeignKey(UUID foreignKeyId, String foreignKeyNaturalKey, DefinitionIssue reason) {
    }

    /**
     * @param truncated        the column bound or the result limit cut the search short
     * @param alreadyCataloged candidates omitted because an equivalent DRAFT or APPROVED relationship exists
     */
    public record Result(List<RelationshipCandidate> candidates, List<UnresolvedForeignKey> unresolvedForeignKeys,
                         boolean truncated, int alreadyCataloged) {
    }

    private static final EnumSet<PhysicalObjectType> RELATIONS =
            EnumSet.of(PhysicalObjectType.TABLE, PhysicalObjectType.VIEW, PhysicalObjectType.MATERIALIZED_VIEW);
    private static final Set<String> GENERIC_NAMES = Set.of("id", "name", "code", "number", "key", "description",
            "type", "status", "value", "label", "comment", "notes", "created_at", "updated_at", "created_by",
            "updated_by", "deleted_at", "version");
    private static final Pattern BUSINESS_IDENTIFIER = Pattern.compile(
            "(dbid|sku|code|number|uuid|guid|key|.+_(code|number|no|num|key|dbid|uuid|guid|sku|id))");

    private final RelationshipValidator validator = new RelationshipValidator();

    /**
     * @param objects        every physical object of the tenant and data source
     * @param catalogued     signatures of the DRAFT and APPROVED relationships of the data source
     * @param focusObjectId  when non-null, only candidates whose source or target is this relation
     * @param limit          maximum number of candidates (1..{@link #MAX_RESULTS})
     */
    public Result find(EngineCode engine, Collection<PhysicalObject> objects, Set<String> catalogued,
                       UUID focusObjectId, int limit) {
        int max = Math.clamp(limit, 1, MAX_RESULTS);
        Map<UUID, PhysicalObject> byId = new HashMap<>();
        Map<UUID, List<PhysicalObject>> columnsByRelation = new HashMap<>();
        List<PhysicalObject> foreignKeys = new ArrayList<>();
        int columns = 0;
        boolean truncated = false;
        for (PhysicalObject object : objects) {
            byId.put(object.id(), object);
        }
        for (PhysicalObject object : objects) {
            if (object.status() != PhysicalObjectStatus.ACTIVE) {
                continue;
            }
            if (object.type() == PhysicalObjectType.COLUMN && activeRelation(byId.get(object.parentId()))) {
                if (++columns > MAX_COLUMNS) {
                    truncated = true;
                    continue;
                }
                columnsByRelation.computeIfAbsent(object.parentId(), k -> new ArrayList<>()).add(object);
            } else if (object.type() == PhysicalObjectType.FOREIGN_KEY) {
                foreignKeys.add(object);
            }
        }
        columnsByRelation.values().forEach(list -> list.sort(Comparator.comparing(o -> o.naturalKey().value())));
        foreignKeys.sort(Comparator.comparing(o -> o.naturalKey().value()));

        Map<String, RelationshipCandidate> found = new HashMap<>();
        List<UnresolvedForeignKey> unresolved = new ArrayList<>();
        Set<String> skipped = new HashSet<>();

        ForeignKeyResolver resolver = new ForeignKeyResolver(objects);
        for (PhysicalObject fk : foreignKeys) {
            ForeignKeyResolver.Resolution resolution = resolver.resolve(fk.id());
            if (!resolution.resolved()) {
                if (focusObjectId == null || focusObjectId.equals(fk.parentId())) {
                    unresolved.add(new UnresolvedForeignKey(fk.id(), fk.naturalKey().value(), resolution.issue()));
                }
                continue;
            }
            if (!inFocus(focusObjectId, resolution.sourceObjectId(), resolution.targetObjectId())) {
                continue;
            }
            String signature = RelationshipSignature.of(resolution.sourceObjectId(), resolution.targetObjectId(),
                    resolution.pairs());
            if (catalogued.contains(signature)) {
                skipped.add(signature);
                continue;
            }
            RelationshipValidator.Evaluation evaluation = validator.evaluate(engine, byId,
                    RelationshipOrigin.DECLARED_FK, resolution.sourceObjectId(), resolution.targetObjectId(),
                    resolution.pairs(), fk.id(), DeclaredCardinality.MANY_TO_MANY);
            if (!evaluation.valid()) {
                continue;
            }
            List<Signal> signals = List.of(
                    new Signal(SignalCode.DECLARED_FOREIGN_KEY, "a physical foreign key joins exactly these columns"),
                    new Signal(SignalCode.COMPATIBLE_TYPES, "the columns of every pair have compatible data types"));
            found.putIfAbsent(signature, candidate(byId, resolution.sourceObjectId(), resolution.targetObjectId(),
                    resolution.pairs(), RelationshipOrigin.DECLARED_FK, fk.id(), signals, 0.95, evaluation));
        }

        // Index target keys by the source column names that would reference them.
        Map<String, List<TargetKey>> expected = new HashMap<>();
        List<UUID> relationIds = new ArrayList<>(columnsByRelation.keySet());
        relationIds.sort(Comparator.comparing(id -> byId.get(id).naturalKey().value()));
        for (UUID relationId : relationIds) {
            PhysicalObject relation = byId.get(relationId);
            List<String> primaryKey = RelationshipValidator.strings(
                    relation.attributes().values().get(RelationshipValidator.PRIMARY_KEY));
            for (PhysicalObject column : columnsByRelation.get(relationId)) {
                String name = lower(column.childName());
                boolean isPrimaryKey = primaryKey.size() == 1 && primaryKey.getFirst().equals(column.childName());
                boolean businessIdentifier = !isPrimaryKey && BUSINESS_IDENTIFIER.matcher(name).matches();
                if (!isPrimaryKey && !businessIdentifier) {
                    continue;
                }
                TargetKey key = new TargetKey(relation, column, isPrimaryKey);
                String table = lower(relation.objectName());
                for (String prefix : List.of(table, singular(table)).stream().distinct().toList()) {
                    expected.computeIfAbsent(prefix + "_" + name, k -> new ArrayList<>()).add(key.named(false));
                }
                if (!GENERIC_NAMES.contains(name)) {
                    expected.computeIfAbsent(name, k -> new ArrayList<>()).add(key.named(true));
                }
            }
        }
        for (UUID relationId : relationIds) {
            for (PhysicalObject column : columnsByRelation.get(relationId)) {
                for (TargetKey key : expected.getOrDefault(lower(column.childName()), List.of())) {
                    if (key.relation().id().equals(relationId)
                            || !inFocus(focusObjectId, relationId, key.relation().id())
                            || !RelationshipValidator.compatible(engine, column, key.column())) {
                        continue;
                    }
                    List<ColumnPair> pairs = List.of(new ColumnPair(column.id(), key.column().id()));
                    String signature = RelationshipSignature.of(relationId, key.relation().id(), pairs);
                    if (catalogued.contains(signature)) {
                        skipped.add(signature);
                        continue;
                    }
                    if (found.containsKey(signature)) {
                        continue;
                    }
                    found.put(signature, nameCandidate(engine, byId, column, key));
                }
            }
        }

        List<RelationshipCandidate> ranked = new ArrayList<>(found.values());
        ranked.sort(Comparator.comparingDouble(RelationshipCandidate::heuristicScore).reversed()
                .thenComparing(RelationshipCandidate::sourceNaturalKey)
                .thenComparing(RelationshipCandidate::targetNaturalKey)
                .thenComparing(c -> c.pairs().getFirst().sourceNaturalKey()));
        if (ranked.size() > max) {
            truncated = true;
            ranked = ranked.subList(0, max);
        }
        List<UnresolvedForeignKey> reported = unresolved.size() > max ? unresolved.subList(0, max) : unresolved;
        return new Result(List.copyOf(ranked), List.copyOf(reported), truncated || unresolved.size() > max,
                skipped.size());
    }

    private RelationshipCandidate nameCandidate(EngineCode engine, Map<UUID, PhysicalObject> byId,
                                                PhysicalObject column, TargetKey key) {
        List<Signal> signals = new ArrayList<>();
        double score;
        if (key.sameName()) {
            signals.add(new Signal(SignalCode.SAME_COLUMN_NAME, "both columns have the same distinctive name"));
            score = 0.3;
        } else {
            signals.add(new Signal(SignalCode.COLUMN_NAME_REFERENCES_TABLE,
                    "the source column name is the target table name followed by the target column name"));
            score = 0.5;
        }
        signals.add(new Signal(SignalCode.COMPATIBLE_TYPES, "both columns have compatible data types"));
        if (key.primaryKey()) {
            signals.add(new Signal(SignalCode.TARGET_PRIMARY_KEY,
                    "the target column is the single-column primary key of its table"));
            score += 0.2;
        } else {
            signals.add(new Signal(SignalCode.TARGET_BUSINESS_IDENTIFIER,
                    "the target column name looks like a business identifier; its uniqueness is not known"));
            score += 0.1;
        }
        if (mentions(column, byId.get(column.parentId()), key.relation())) {
            signals.add(new Signal(SignalCode.COMMENT_MENTIONS_TABLE,
                    "a comment of the source column or table mentions the target table"));
            score += 0.1;
        }
        List<ColumnPair> pairs = List.of(new ColumnPair(column.id(), key.column().id()));
        RelationshipValidator.Evaluation evaluation = validator.evaluate(engine, byId, RelationshipOrigin.SEMANTIC,
                column.parentId(), key.relation().id(), pairs, null, DeclaredCardinality.MANY_TO_MANY);
        return candidate(byId, column.parentId(), key.relation().id(), pairs, RelationshipOrigin.SEMANTIC, null,
                signals, Math.min(score, 0.79), evaluation);
    }

    private static RelationshipCandidate candidate(Map<UUID, PhysicalObject> byId, UUID sourceId, UUID targetId,
                                                   List<ColumnPair> pairs, RelationshipOrigin origin,
                                                   UUID foreignKeyId, List<Signal> signals, double score,
                                                   RelationshipValidator.Evaluation evaluation) {
        double rounded = Math.round(score * 100) / 100.0;
        return new RelationshipCandidate(sourceId, byId.get(sourceId).naturalKey().value(), targetId,
                byId.get(targetId).naturalKey().value(),
                pairs.stream().map(p -> new Pair(p.sourceColumnId(), byId.get(p.sourceColumnId()).naturalKey().value(),
                        p.targetColumnId(), byId.get(p.targetColumnId()).naturalKey().value())).toList(),
                origin, foreignKeyId, signals, evaluation.evidence().sourceUniqueness(),
                evaluation.evidence().targetUniqueness(), rounded, Classification.of(rounded));
    }

    private static boolean mentions(PhysicalObject column, PhysicalObject relation, PhysicalObject target) {
        Pattern word = Pattern.compile("\\b" + Pattern.quote(lower(target.objectName())) + "\\b");
        return (column.comment() != null && word.matcher(lower(column.comment())).find())
                || (relation != null && relation.comment() != null && word.matcher(lower(relation.comment())).find());
    }

    private static boolean activeRelation(PhysicalObject object) {
        return object != null && RELATIONS.contains(object.type()) && object.status() == PhysicalObjectStatus.ACTIVE;
    }

    private static boolean inFocus(UUID focus, UUID sourceId, UUID targetId) {
        return focus == null || focus.equals(sourceId) || focus.equals(targetId);
    }

    /** Naive English singular, only used to build name variants ({@code customers} → {@code customer}). */
    static String singular(String name) {
        if (name.endsWith("ies") && name.length() > 3) {
            return name.substring(0, name.length() - 3) + "y";
        }
        if (name.endsWith("s") && !name.endsWith("ss") && name.length() > 1) {
            return name.substring(0, name.length() - 1);
        }
        return name;
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record TargetKey(PhysicalObject relation, PhysicalObject column, boolean primaryKey, boolean sameName) {

        TargetKey(PhysicalObject relation, PhysicalObject column, boolean primaryKey) {
            this(relation, column, primaryKey, false);
        }

        TargetKey named(boolean same) {
            return new TargetKey(relation, column, primaryKey, same);
        }
    }
}
