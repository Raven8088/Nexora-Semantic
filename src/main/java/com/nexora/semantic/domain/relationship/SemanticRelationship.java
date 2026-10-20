package com.nexora.semantic.domain.relationship;

import com.nexora.semantic.domain.catalog.ApprovalRules;
import com.nexora.semantic.domain.catalog.DefinitionStatus;
import com.nexora.semantic.domain.catalog.InvalidStateTransitionException;
import com.nexora.semantic.domain.shared.InvalidValueException;
import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.shared.TextRules;
import com.nexora.semantic.domain.source.DataSourceId;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * A join between two physical relations (tables or views) of the same tenant and data source (S3.1): one or more
 * ordered column pairs {@code source.c1 = target.d1 [AND source.c2 = target.d2 ...]}. Relationships connect
 * physical objects, not concepts; S4 reaches them through the physical bindings of concepts.
 * <p>
 * Same lifecycle and approval rules as semantic definitions (D19, D3). Objects, pairs, origin, foreign key evidence
 * and the replaced relationship are immutable: a correction is a new relationship that replaces the old one, which
 * becomes INACTIVE when the new one is approved. Descriptive fields, the declared cardinality and the allowed JOIN
 * types are editable; any edit returns the relationship to DRAFT.
 *
 * @param foreignKeyId   physical FOREIGN_KEY object kept as evidence; non-null exactly for {@code DECLARED_FK}
 * @param replacesId     relationship this one supersedes once approved; null when it supersedes nothing
 * @param approvedBy     subject of the last approver; kept on INACTIVE for history, null in DRAFT
 * @param inactiveReason non-null exactly when INACTIVE
 */
public record SemanticRelationship(
        UUID id,
        TenantId tenantId,
        DataSourceId dataSourceId,
        String name,
        String description,
        RelationshipOrigin origin,
        UUID sourceObjectId,
        UUID targetObjectId,
        List<ColumnPair> pairs,
        UUID foreignKeyId,
        DeclaredCardinality declaredCardinality,
        Set<JoinType> allowedJoinTypes,
        UUID replacesId,
        DefinitionStatus status,
        PrincipalRef createdBy,
        Instant createdAt,
        PrincipalRef updatedBy,
        Instant updatedAt,
        String approvedBy,
        Instant approvedAt,
        String inactiveReason,
        long version) {

    public static final int MAX_NAME_LENGTH = 200;
    public static final int MAX_DESCRIPTION_LENGTH = 2000;
    public static final int MAX_REASON_LENGTH = 500;
    public static final int MAX_PAIRS = 16;

    public SemanticRelationship {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        name = TextRules.requireText(name, "name", MAX_NAME_LENGTH);
        description = TextRules.optionalText(description, "description", MAX_DESCRIPTION_LENGTH);
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(sourceObjectId, "sourceObjectId");
        Objects.requireNonNull(targetObjectId, "targetObjectId");
        if (pairs == null || pairs.isEmpty() || pairs.size() > MAX_PAIRS) {
            throw new InvalidValueException("pairs", "must contain between 1 and " + MAX_PAIRS + " column pairs");
        }
        pairs = List.copyOf(pairs);
        if ((origin == RelationshipOrigin.DECLARED_FK) != (foreignKeyId != null)) {
            throw new InvalidValueException("foreignKeyId",
                    "is set exactly for relationships declared by a foreign key");
        }
        if (declaredCardinality == null) {
            throw new InvalidValueException("declaredCardinality", "is required");
        }
        if (allowedJoinTypes == null || allowedJoinTypes.isEmpty()) {
            throw new InvalidValueException("allowedJoinTypes", "must contain at least one of INNER, LEFT");
        }
        allowedJoinTypes = Set.copyOf(EnumSet.copyOf(allowedJoinTypes));
        if (id.equals(replacesId)) {
            throw new InvalidValueException("replacesId", "a relationship cannot replace itself");
        }
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedBy, "updatedBy");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (status == DefinitionStatus.APPROVED && (approvedBy == null || approvedAt == null)) {
            throw new IllegalArgumentException("approved relationships record their approver");
        }
        if ((status == DefinitionStatus.INACTIVE) != (inactiveReason != null)) {
            throw new IllegalArgumentException("inactiveReason is set exactly when INACTIVE");
        }
        if (status == DefinitionStatus.DRAFT && approvedBy != null) {
            throw new IllegalArgumentException("drafts carry no approval");
        }
    }

    /** Every new relationship starts as DRAFT, including those created from a foreign key: nothing is auto-approved. */
    public static SemanticRelationship create(UUID id, TenantId tenantId, DataSourceId dataSourceId, String name,
                                              String description, RelationshipOrigin origin, UUID sourceObjectId,
                                              UUID targetObjectId, List<ColumnPair> pairs, UUID foreignKeyId,
                                              DeclaredCardinality cardinality, Set<JoinType> joinTypes,
                                              UUID replacesId, PrincipalRef author, Instant now) {
        return new SemanticRelationship(id, tenantId, dataSourceId, name, description, origin, sourceObjectId,
                targetObjectId, pairs, foreignKeyId, cardinality, joinTypes, replacesId, DefinitionStatus.DRAFT,
                author, now, author, now, null, null, null, 0);
    }

    /** Equivalence key: same columns joined, regardless of pair order and direction. */
    public String signature() {
        return RelationshipSignature.of(sourceObjectId, targetObjectId, pairs);
    }

    /** New descriptive content from any status; the result is a DRAFT that needs a new approval. */
    public SemanticRelationship revise(String newName, String newDescription, DeclaredCardinality newCardinality,
                                       Set<JoinType> newJoinTypes, PrincipalRef editor, Instant now) {
        return new SemanticRelationship(id, tenantId, dataSourceId, newName, newDescription, origin, sourceObjectId,
                targetObjectId, pairs, foreignKeyId, newCardinality, newJoinTypes, replacesId, DefinitionStatus.DRAFT,
                createdBy, createdAt, editor, now, null, null, null, version);
    }

    /** Approves a DRAFT (D3). Physical validation is the caller's responsibility ({@link RelationshipValidator}). */
    public SemanticRelationship approve(PrincipalRef approver, Instant now) {
        if (status != DefinitionStatus.DRAFT) {
            throw new InvalidStateTransitionException(status, "approve");
        }
        ApprovalRules.requireIndependentHuman(approver, createdBy, updatedBy);
        return new SemanticRelationship(id, tenantId, dataSourceId, name, description, origin, sourceObjectId,
                targetObjectId, pairs, foreignKeyId, declaredCardinality, allowedJoinTypes, replacesId,
                DefinitionStatus.APPROVED, createdBy, createdAt, updatedBy, now, approver.id(), now, null, version);
    }

    /** Explicit deactivation of a DRAFT or APPROVED relationship (also used when it is replaced). */
    public SemanticRelationship deactivate(PrincipalRef by, String reason, Instant now) {
        if (status == DefinitionStatus.INACTIVE) {
            throw new InvalidStateTransitionException(status, "deactivate");
        }
        return inactive(TextRules.requireText(reason, "reason", MAX_REASON_LENGTH), by, now);
    }

    /** An APPROVED relationship that no longer validates becomes INACTIVE; it is never reactivated automatically. */
    public SemanticRelationship invalidate(String reason, PrincipalRef by, Instant now) {
        if (status != DefinitionStatus.APPROVED) {
            throw new InvalidStateTransitionException(status, "invalidate");
        }
        return inactive(reason, by, now);
    }

    public SemanticRelationship withVersion(long newVersion) {
        return new SemanticRelationship(id, tenantId, dataSourceId, name, description, origin, sourceObjectId,
                targetObjectId, pairs, foreignKeyId, declaredCardinality, allowedJoinTypes, replacesId, status,
                createdBy, createdAt, updatedBy, updatedAt, approvedBy, approvedAt, inactiveReason, newVersion);
    }

    private SemanticRelationship inactive(String reason, PrincipalRef by, Instant now) {
        return new SemanticRelationship(id, tenantId, dataSourceId, name, description, origin, sourceObjectId,
                targetObjectId, pairs, foreignKeyId, declaredCardinality, allowedJoinTypes, replacesId,
                DefinitionStatus.INACTIVE, createdBy, createdAt, by, now, approvedBy, approvedAt, reason, version);
    }
}
