package com.nexora.semantic.domain.relationship;

import java.util.List;
import java.util.UUID;

/**
 * A relationship suggestion computed on demand from the physical metadata. It is never stored and never usable: a
 * person must create a DRAFT relationship from it (and another person approve it). The score is a deterministic
 * heuristic over explainable signals, not a probability and not a guarantee.
 *
 * @param suggestedOrigin DECLARED_FK when a physical foreign key backs it (then {@code foreignKeyId} is set)
 * @param signals         why it is suggested, in a fixed order
 * @param heuristicScore  0..1, higher means more signals; only meaningful to rank suggestions
 */
public record RelationshipCandidate(
        UUID sourceObjectId,
        String sourceNaturalKey,
        UUID targetObjectId,
        String targetNaturalKey,
        List<Pair> pairs,
        RelationshipOrigin suggestedOrigin,
        UUID foreignKeyId,
        List<Signal> signals,
        UniquenessEvidence sourceUniqueness,
        UniquenessEvidence targetUniqueness,
        double heuristicScore,
        Classification classification) {

    public record Pair(UUID sourceColumnId, String sourceNaturalKey, UUID targetColumnId, String targetNaturalKey) {
    }

    /** Explainable reason; the detail describes the rule, never data values. */
    public record Signal(SignalCode code, String detail) {
    }

    public enum SignalCode {
        DECLARED_FOREIGN_KEY,
        COMPATIBLE_TYPES,
        COLUMN_NAME_REFERENCES_TABLE,
        SAME_COLUMN_NAME,
        TARGET_PRIMARY_KEY,
        TARGET_BUSINESS_IDENTIFIER,
        COMMENT_MENTIONS_TABLE
    }

    /** Heuristic band of the score (STRONG ≥ 0.8, MEDIUM ≥ 0.5, WEAK otherwise). */
    public enum Classification {
        STRONG,
        MEDIUM,
        WEAK;

        static Classification of(double score) {
            return score >= 0.8 ? STRONG : score >= 0.5 ? MEDIUM : WEAK;
        }
    }

    public RelationshipCandidate {
        pairs = List.copyOf(pairs);
        signals = List.copyOf(signals);
    }
}
