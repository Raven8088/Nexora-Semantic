package com.nexora.semantic.domain.relationship;

import java.util.Objects;

/**
 * What the current physical metadata says about a relationship, kept apart from what a person declared.
 *
 * @param sourceUniqueness       uniqueness of the source columns (exact primary key or unknown)
 * @param targetUniqueness       uniqueness of the target columns (exact primary key or unknown)
 * @param foreignKey             physical foreign key backing (DECLARED_FK only)
 * @param cardinalityAssurance   how far the declared cardinality is backed by primary keys
 * @param sourceToTargetRisk     row multiplication when joining from source to target
 * @param targetToSourceRisk     row multiplication when joining from target to source
 */
public record RelationshipEvidence(
        UniquenessEvidence sourceUniqueness,
        UniquenessEvidence targetUniqueness,
        ForeignKeyEvidence foreignKey,
        CardinalityAssurance cardinalityAssurance,
        RowMultiplicationRisk sourceToTargetRisk,
        RowMultiplicationRisk targetToSourceRisk) {

    public RelationshipEvidence {
        Objects.requireNonNull(sourceUniqueness, "sourceUniqueness");
        Objects.requireNonNull(targetUniqueness, "targetUniqueness");
        Objects.requireNonNull(foreignKey, "foreignKey");
        Objects.requireNonNull(cardinalityAssurance, "cardinalityAssurance");
        Objects.requireNonNull(sourceToTargetRisk, "sourceToTargetRisk");
        Objects.requireNonNull(targetToSourceRisk, "targetToSourceRisk");
    }

    static RelationshipEvidence of(DeclaredCardinality declared, UniquenessEvidence source, UniquenessEvidence target,
                                   ForeignKeyEvidence foreignKey) {
        CardinalityAssurance assurance;
        if (!declared.sourceOne() && !declared.targetOne()) {
            assurance = CardinalityAssurance.NO_UNIQUENESS_CLAIM;
        } else {
            boolean backed = (!declared.sourceOne() || source == UniquenessEvidence.PRIMARY_KEY)
                    && (!declared.targetOne() || target == UniquenessEvidence.PRIMARY_KEY);
            assurance = backed ? CardinalityAssurance.BACKED_BY_PRIMARY_KEY : CardinalityAssurance.DECLARED_ONLY;
        }
        return new RelationshipEvidence(source, target, foreignKey, assurance, risk(target), risk(source));
    }

    private static RowMultiplicationRisk risk(UniquenessEvidence joinedSide) {
        return joinedSide == UniquenessEvidence.PRIMARY_KEY
                ? RowMultiplicationRisk.NONE_BY_PRIMARY_KEY : RowMultiplicationRisk.POSSIBLE;
    }
}
