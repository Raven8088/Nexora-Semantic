package com.nexora.semantic.application.catalog;

import com.nexora.semantic.application.port.AuditLog;
import com.nexora.semantic.application.port.PhysicalObjectRepository;
import com.nexora.semantic.application.port.SemanticRelationshipRepository;
import com.nexora.semantic.domain.audit.AuditAction;
import com.nexora.semantic.domain.audit.AuditEvent;
import com.nexora.semantic.domain.audit.AuditTargetType;
import com.nexora.semantic.domain.catalog.DefinitionIssue;
import com.nexora.semantic.domain.catalog.DefinitionStatus;
import com.nexora.semantic.domain.relationship.ForeignKeyEvidence;
import com.nexora.semantic.domain.relationship.RelationshipValidator;
import com.nexora.semantic.domain.relationship.SemanticRelationship;
import com.nexora.semantic.domain.shared.CallerContext;
import com.nexora.semantic.domain.source.DataSource;
import com.nexora.semantic.domain.source.PhysicalObject;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Keeps relationships consistent with the physical metadata (S3.1): after an import, every APPROVED relationship that
 * no longer validates becomes INACTIVE — a participating object or column is MISSING, a column type changed
 * incompatibly, or the foreign key backing a DECLARED_FK relationship was lost (missing or changed) even if the
 * columns are still there. The relationship and its history are kept; it stays INACTIVE even if the metadata is
 * later restored, until a person revises it and another approves it. Runs inside the import transaction, which
 * already holds the data source lock; a second run over the same metadata finds nothing to do (idempotent).
 * <p>
 * No semantic definition references a relationship in S3/S3.1, so there are no dependent definitions to propagate
 * to; the audit event records the invalidation for S4, which will consume relationships through their contract.
 */
@Transactional
public class RelationshipInvalidationService {

    static final String REASON_PREFIX = "Invalidated by metadata import";

    private final SemanticRelationshipRepository relationships;
    private final PhysicalObjectRepository objects;
    private final AuditLog auditLog;
    private final RelationshipValidator validator = new RelationshipValidator();

    public RelationshipInvalidationService(SemanticRelationshipRepository relationships,
                                           PhysicalObjectRepository objects, AuditLog auditLog) {
        this.relationships = relationships;
        this.objects = objects;
        this.auditLog = auditLog;
    }

    /** @return number of relationships invalidated */
    public int invalidateAfterImport(CallerContext caller, DataSource dataSource, UUID snapshotId, Instant now) {
        List<SemanticRelationship> approved = relationships.findByStatus(caller.tenantId(), dataSource.id(),
                DefinitionStatus.APPROVED);
        if (approved.isEmpty()) {
            return 0;
        }
        Set<UUID> ids = new HashSet<>();
        approved.forEach(r -> ids.addAll(SemanticRelationshipService.referencedIds(r)));
        Map<UUID, PhysicalObject> loaded = new HashMap<>();
        objects.findByIds(caller.tenantId(), dataSource.id(), ids).forEach(o -> loaded.put(o.id(), o));

        int invalidated = 0;
        for (SemanticRelationship relationship : approved) {
            RelationshipValidator.Evaluation evaluation = validator.evaluate(dataSource.engine(), loaded,
                    relationship);
            if (evaluation.valid()) {
                continue;
            }
            List<String> codes = new ArrayList<>(evaluation.issues().stream().map(DefinitionIssue::code)
                    .map(Enum::name).distinct().toList());
            boolean foreignKeyLost = evaluation.evidence().foreignKey() == ForeignKeyEvidence.LOST;
            SemanticRelationship inactive = relationships.update(relationship.invalidate(
                    REASON_PREFIX + ": " + String.join(", ", codes), caller.principal(), now), relationship.version());
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("dataSourceId", dataSource.id().value().toString());
            details.put("snapshotId", snapshotId.toString());
            details.put("issues", codes);
            details.put("foreignKeyLost", foreignKeyLost);
            auditLog.append(AuditEvent.of(caller.tenantId(), now, caller.principal(), caller.correlationId(),
                    AuditAction.RELATIONSHIP_INVALIDATED, AuditTargetType.SEMANTIC_RELATIONSHIP, inactive.id(),
                    details));
            invalidated++;
        }
        return invalidated;
    }
}
