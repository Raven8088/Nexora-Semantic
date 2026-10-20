package com.nexora.semantic.domain.relationship;

import com.nexora.semantic.domain.catalog.DefinitionIssue;
import com.nexora.semantic.domain.catalog.DefinitionStatus;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Typed, read-only view of a relationship for the future query resolution stage (S4). It carries facts only: no
 * route resolution, plan, SQL or execution. S4 must use a relationship only when {@link #usable()} is true, must
 * join only from {@link #sourceObjectId()} to {@link #targetObjectId()} (or reversed, swapping the pairs) with one
 * of {@link #allowedJoinTypes()}, and must treat {@link RowMultiplicationRisk#POSSIBLE} as a real risk of
 * duplicated rows ({@code DISTINCT} is not a remedy). The declared cardinality is a human statement;
 * {@link RelationshipEvidence#cardinalityAssurance()} says whether primary keys back it.
 *
 * @param pairs          ordered column pairs (source column = target column), by physical object id
 * @param invalidReasons physical validation issues; empty when the relationship is physically valid
 * @param usable         APPROVED and physically valid
 */
public record RelationshipContract(
        UUID relationshipId,
        long version,
        DefinitionStatus status,
        boolean usable,
        List<DefinitionIssue> invalidReasons,
        RelationshipOrigin origin,
        UUID sourceObjectId,
        UUID targetObjectId,
        List<ColumnPair> pairs,
        DeclaredCardinality declaredCardinality,
        RelationshipEvidence evidence,
        UUID foreignKeyId,
        Set<JoinType> allowedJoinTypes) {

    public RelationshipContract {
        Objects.requireNonNull(relationshipId, "relationshipId");
        invalidReasons = List.copyOf(invalidReasons);
        pairs = List.copyOf(pairs);
        allowedJoinTypes = Set.copyOf(allowedJoinTypes);
    }

    public static RelationshipContract of(SemanticRelationship relationship,
                                          RelationshipValidator.Evaluation evaluation) {
        return new RelationshipContract(relationship.id(), relationship.version(), relationship.status(),
                relationship.status() == DefinitionStatus.APPROVED && evaluation.valid(), evaluation.issues(),
                relationship.origin(), relationship.sourceObjectId(), relationship.targetObjectId(),
                relationship.pairs(), relationship.declaredCardinality(), evaluation.evidence(),
                relationship.foreignKeyId(), relationship.allowedJoinTypes());
    }
}
