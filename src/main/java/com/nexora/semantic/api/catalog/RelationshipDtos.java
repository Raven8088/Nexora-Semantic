package com.nexora.semantic.api.catalog;

import com.nexora.semantic.api.catalog.DefinitionDtos.IssueResponse;
import com.nexora.semantic.application.catalog.SemanticRelationshipService.EvaluatedRelationship;
import com.nexora.semantic.domain.catalog.DefinitionIssue;
import com.nexora.semantic.domain.catalog.DefinitionStatus;
import com.nexora.semantic.domain.relationship.CardinalityAssurance;
import com.nexora.semantic.domain.relationship.ColumnPair;
import com.nexora.semantic.domain.relationship.DeclaredCardinality;
import com.nexora.semantic.domain.relationship.ForeignKeyEvidence;
import com.nexora.semantic.domain.relationship.JoinType;
import com.nexora.semantic.domain.relationship.RelationshipCandidate;
import com.nexora.semantic.domain.relationship.RelationshipCandidateFinder;
import com.nexora.semantic.domain.relationship.RelationshipContract;
import com.nexora.semantic.domain.relationship.RelationshipEvidence;
import com.nexora.semantic.domain.relationship.RelationshipOrigin;
import com.nexora.semantic.domain.relationship.RowMultiplicationRisk;
import com.nexora.semantic.domain.relationship.SemanticRelationship;
import com.nexora.semantic.domain.relationship.UniquenessEvidence;
import com.nexora.semantic.domain.source.PhysicalObject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * HTTP contract of semantic relationships (S3.1). References are ids of physical objects (S2): tables or views for
 * the endpoints, columns for the pairs, a foreign key for {@code from-foreign-key}. There is no free-form expression.
 * Unknown JSON properties are rejected (strict global setting).
 */
public final class RelationshipDtos {

    private RelationshipDtos() {
    }

    public record PairRequest(@NotNull UUID sourceColumnId, @NotNull UUID targetColumnId) {
    }

    /** A SEMANTIC relationship; pairs are ordered and immutable once created. */
    public record CreateRelationshipRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @NotNull UUID sourceObjectId,
            @NotNull UUID targetObjectId,
            @NotEmpty @Size(max = SemanticRelationship.MAX_PAIRS) List<@Valid @NotNull PairRequest> pairs,
            @NotNull DeclaredCardinality declaredCardinality,
            @NotEmpty @Size(max = 2) Set<@NotNull JoinType> allowedJoinTypes,
            UUID replacesId) {

        List<ColumnPair> columnPairs() {
            return pairs.stream().map(p -> new ColumnPair(p.sourceColumnId(), p.targetColumnId())).toList();
        }
    }

    /** A DECLARED_FK relationship from a physical foreign key; {@code name} defaults to the foreign key name. */
    public record CreateFromForeignKeyRequest(
            @NotNull UUID foreignKeyId,
            @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @NotNull DeclaredCardinality declaredCardinality,
            @NotEmpty @Size(max = 2) Set<@NotNull JoinType> allowedJoinTypes,
            UUID replacesId) {
    }

    /** Full replacement of the editable fields; objects, pairs and origin are immutable. */
    public record UpdateRelationshipRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @NotNull DeclaredCardinality declaredCardinality,
            @NotEmpty @Size(max = 2) Set<@NotNull JoinType> allowedJoinTypes) {
    }

    public record ObjectRef(UUID objectId, String naturalKey) {

        static ObjectRef of(Map<UUID, PhysicalObject> refs, UUID id) {
            if (id == null) {
                return null;
            }
            PhysicalObject object = refs.get(id);
            return new ObjectRef(id, object == null ? null : object.naturalKey().value());
        }
    }

    public record PairResponse(int ordinal, ObjectRef sourceColumn, ObjectRef targetColumn) {
    }

    /**
     * Physical evidence, kept apart from the declared cardinality.
     *
     * @param sourceToTargetRowMultiplication whether joining source → target can repeat source rows
     * @param targetToSourceRowMultiplication whether joining target → source can repeat target rows
     */
    public record EvidenceResponse(
            UniquenessEvidence sourceUniqueness,
            UniquenessEvidence targetUniqueness,
            ForeignKeyEvidence foreignKey,
            CardinalityAssurance cardinalityAssurance,
            RowMultiplicationRisk sourceToTargetRowMultiplication,
            RowMultiplicationRisk targetToSourceRowMultiplication) {

        static EvidenceResponse from(RelationshipEvidence e) {
            return new EvidenceResponse(e.sourceUniqueness(), e.targetUniqueness(), e.foreignKey(),
                    e.cardinalityAssurance(), e.sourceToTargetRisk(), e.targetToSourceRisk());
        }
    }

    /**
     * @param physicallyValid no physical validation issue with the current metadata
     * @param usable          APPROVED and physically valid: usable by query resolution (S4)
     * @param issues          why it cannot be approved or used now
     */
    public record RelationshipResponse(
            UUID id,
            UUID dataSourceId,
            String name,
            String description,
            RelationshipOrigin origin,
            DefinitionStatus status,
            boolean physicallyValid,
            boolean usable,
            List<IssueResponse> issues,
            ObjectRef source,
            ObjectRef target,
            List<PairResponse> pairs,
            ObjectRef foreignKey,
            DeclaredCardinality declaredCardinality,
            List<JoinType> allowedJoinTypes,
            EvidenceResponse evidence,
            UUID replacesId,
            String createdBy,
            Instant createdAt,
            String updatedBy,
            Instant updatedAt,
            String approvedBy,
            Instant approvedAt,
            String inactiveReason,
            long version) {

        static RelationshipResponse from(EvaluatedRelationship evaluated) {
            SemanticRelationship r = evaluated.relationship();
            Map<UUID, PhysicalObject> refs = evaluated.references();
            List<PairResponse> pairs = new ArrayList<>();
            for (int i = 0; i < r.pairs().size(); i++) {
                ColumnPair pair = r.pairs().get(i);
                pairs.add(new PairResponse(i, ObjectRef.of(refs, pair.sourceColumnId()),
                        ObjectRef.of(refs, pair.targetColumnId())));
            }
            return new RelationshipResponse(r.id(), r.dataSourceId().value(), r.name(), r.description(), r.origin(),
                    r.status(), evaluated.evaluation().valid(), evaluated.usable(),
                    RelationshipDtos.issues(evaluated.evaluation().issues()), ObjectRef.of(refs, r.sourceObjectId()),
                    ObjectRef.of(refs, r.targetObjectId()), pairs, ObjectRef.of(refs, r.foreignKeyId()),
                    r.declaredCardinality(), r.allowedJoinTypes().stream().sorted().toList(),
                    EvidenceResponse.from(evaluated.evaluation().evidence()), r.replacesId(), r.createdBy().id(),
                    r.createdAt(), r.updatedBy().id(), r.updatedAt(), r.approvedBy(), r.approvedAt(),
                    r.inactiveReason(), r.version());
        }
    }

    public record ContractPair(UUID sourceColumnId, UUID targetColumnId) {
    }

    /** Typed contract for S4 (facts only: no route, plan or SQL). */
    public record ContractResponse(
            UUID relationshipId,
            long version,
            DefinitionStatus status,
            boolean usable,
            List<IssueResponse> invalidReasons,
            RelationshipOrigin origin,
            UUID sourceObjectId,
            UUID targetObjectId,
            List<ContractPair> pairs,
            DeclaredCardinality declaredCardinality,
            EvidenceResponse evidence,
            UUID foreignKeyId,
            List<JoinType> allowedJoinTypes) {

        static ContractResponse from(RelationshipContract c) {
            return new ContractResponse(c.relationshipId(), c.version(), c.status(), c.usable(),
                    RelationshipDtos.issues(c.invalidReasons()), c.origin(), c.sourceObjectId(), c.targetObjectId(),
                    c.pairs().stream().map(p -> new ContractPair(p.sourceColumnId(), p.targetColumnId())).toList(),
                    c.declaredCardinality(), EvidenceResponse.from(c.evidence()), c.foreignKeyId(),
                    c.allowedJoinTypes().stream().sorted().toList());
        }
    }

    public record CandidatePairResponse(ObjectRef sourceColumn, ObjectRef targetColumn) {
    }

    public record SignalResponse(RelationshipCandidate.SignalCode code, String detail) {
    }

    /**
     * A suggestion, never stored. {@code heuristic} is always true: the score ranks suggestions and is not a
     * probability or a guarantee.
     */
    public record CandidateResponse(
            ObjectRef source,
            ObjectRef target,
            List<CandidatePairResponse> pairs,
            RelationshipOrigin suggestedOrigin,
            UUID foreignKeyId,
            List<SignalResponse> signals,
            UniquenessEvidence sourceUniqueness,
            UniquenessEvidence targetUniqueness,
            boolean heuristic,
            double heuristicScore,
            RelationshipCandidate.Classification classification) {

        static CandidateResponse from(RelationshipCandidate c) {
            return new CandidateResponse(new ObjectRef(c.sourceObjectId(), c.sourceNaturalKey()),
                    new ObjectRef(c.targetObjectId(), c.targetNaturalKey()),
                    c.pairs().stream().map(p -> new CandidatePairResponse(
                            new ObjectRef(p.sourceColumnId(), p.sourceNaturalKey()),
                            new ObjectRef(p.targetColumnId(), p.targetNaturalKey()))).toList(),
                    c.suggestedOrigin(), c.foreignKeyId(),
                    c.signals().stream().map(s -> new SignalResponse(s.code(), s.detail())).toList(),
                    c.sourceUniqueness(), c.targetUniqueness(), true, c.heuristicScore(), c.classification());
        }
    }

    public record UnresolvedForeignKeyResponse(UUID foreignKeyId, String naturalKey, IssueResponse reason) {
    }

    public record CandidatesResponse(List<CandidateResponse> candidates,
                                     List<UnresolvedForeignKeyResponse> unresolvedForeignKeys,
                                     boolean truncated, int alreadyCataloged) {

        static CandidatesResponse from(RelationshipCandidateFinder.Result result) {
            return new CandidatesResponse(result.candidates().stream().map(CandidateResponse::from).toList(),
                    result.unresolvedForeignKeys().stream().map(u -> new UnresolvedForeignKeyResponse(
                            u.foreignKeyId(), u.foreignKeyNaturalKey(), issue(u.reason()))).toList(),
                    result.truncated(), result.alreadyCataloged());
        }
    }

    private static List<IssueResponse> issues(List<DefinitionIssue> issues) {
        return issues.stream().map(RelationshipDtos::issue).toList();
    }

    private static IssueResponse issue(DefinitionIssue i) {
        return new IssueResponse(i.code().name(), i.field(), i.message());
    }
}
