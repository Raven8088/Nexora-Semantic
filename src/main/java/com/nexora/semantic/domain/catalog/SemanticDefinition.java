package com.nexora.semantic.domain.catalog;

import com.nexora.semantic.domain.shared.InvalidValueException;
import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.PrincipalType;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.shared.TextRules;
import com.nexora.semantic.domain.source.DataSourceId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A business concept, metric or dimension of one data source's semantic catalog (S3 Basic). Lifecycle (D19):
 * <pre>
 *   create ─► DRAFT ──approve──► APPROVED
 *              ▲  │                 │  deactivate / invalidate
 *        revise│  └──deactivate──►  ▼
 *              └──────────────── INACTIVE
 * </pre>
 * Any revision returns the definition to DRAFT and clears the approval, so what is APPROVED is exactly what was
 * approved. Approval invariants (D3) are enforced here, independently of roles: only HUMAN principals approve,
 * and never the author or last editor.
 *
 * @param approvedBy     subject of the last approver; kept on INACTIVE for history, null in DRAFT
 * @param inactiveReason why the definition was deactivated; non-null exactly when INACTIVE
 * @param version        optimistic locking version as stored
 */
public record SemanticDefinition(
        UUID id,
        TenantId tenantId,
        DataSourceId dataSourceId,
        DefinitionKey key,
        String name,
        String description,
        DefinitionSpec spec,
        DefinitionStatus status,
        DefinitionOrigin origin,
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

    public SemanticDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        Objects.requireNonNull(key, "key");
        name = TextRules.requireText(name, "name", MAX_NAME_LENGTH);
        description = TextRules.optionalText(description, "description", MAX_DESCRIPTION_LENGTH);
        Objects.requireNonNull(spec, "spec");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedBy, "updatedBy");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (status == DefinitionStatus.APPROVED && (approvedBy == null || approvedAt == null)) {
            throw new IllegalArgumentException("approved definitions record their approver");
        }
        if ((status == DefinitionStatus.INACTIVE) != (inactiveReason != null)) {
            throw new IllegalArgumentException("inactiveReason is set exactly when INACTIVE");
        }
        if (status == DefinitionStatus.DRAFT && approvedBy != null) {
            throw new IllegalArgumentException("drafts carry no approval");
        }
    }

    public static SemanticDefinition create(UUID id, TenantId tenantId, DataSourceId dataSourceId, DefinitionKey key,
                                            String name, String description, DefinitionSpec spec, PrincipalRef author,
                                            Instant now) {
        DefinitionOrigin origin = author.type() == PrincipalType.AI ? DefinitionOrigin.AI : DefinitionOrigin.MANUAL;
        return new SemanticDefinition(id, tenantId, dataSourceId, key, name, description, spec, DefinitionStatus.DRAFT,
                origin, author, now, author, now, null, null, null, 0);
    }

    public DefinitionKind kind() {
        return spec.kind();
    }

    /** New content from any status; the result is a DRAFT that needs a new approval. The kind cannot change. */
    public SemanticDefinition revise(String newName, String newDescription, DefinitionSpec newSpec, PrincipalRef editor,
                                     Instant now) {
        if (newSpec.kind() != kind()) {
            throw new InvalidValueException("kind", "cannot change");
        }
        return new SemanticDefinition(id, tenantId, dataSourceId, key, newName, newDescription, newSpec,
                DefinitionStatus.DRAFT, origin, createdBy, createdAt, editor, now, null, null, null, version);
    }

    /**
     * Approves a DRAFT. Validation of references and compatibility is the caller's responsibility
     * ({@link DefinitionValidator}); this method enforces the lifecycle and the approval invariants.
     */
    public SemanticDefinition approve(PrincipalRef approver, Instant now) {
        if (status != DefinitionStatus.DRAFT) {
            throw new InvalidStateTransitionException(status, "approve");
        }
        ApprovalRules.requireIndependentHuman(approver, createdBy, updatedBy);
        return new SemanticDefinition(id, tenantId, dataSourceId, key, name, description, spec,
                DefinitionStatus.APPROVED, origin, createdBy, createdAt, updatedBy, now, approver.id(), now, null,
                version);
    }

    /** Explicit deactivation of a DRAFT or APPROVED definition. */
    public SemanticDefinition deactivate(PrincipalRef by, String reason, Instant now) {
        if (status == DefinitionStatus.INACTIVE) {
            throw new InvalidStateTransitionException(status, "deactivate");
        }
        return inactive(TextRules.requireText(reason, "reason", MAX_REASON_LENGTH), by, now);
    }

    /** An APPROVED definition whose references are no longer valid becomes INACTIVE until it is revised. */
    public SemanticDefinition invalidate(String reason, PrincipalRef by, Instant now) {
        if (status != DefinitionStatus.APPROVED) {
            throw new InvalidStateTransitionException(status, "invalidate");
        }
        return inactive(reason, by, now);
    }

    private SemanticDefinition inactive(String reason, PrincipalRef by, Instant now) {
        return new SemanticDefinition(id, tenantId, dataSourceId, key, name, description, spec,
                DefinitionStatus.INACTIVE, origin, createdBy, createdAt, by, now, approvedBy, approvedAt, reason,
                version);
    }
}
