package com.nexora.semantic.api.source;

import com.nexora.semantic.domain.source.MetadataSnapshot;
import com.nexora.semantic.domain.source.PhysicalObject;
import com.nexora.semantic.domain.source.PhysicalObjectStatus;
import com.nexora.semantic.domain.source.PhysicalObjectType;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** HTTP contract of imported Discovery metadata. */
public final class MetadataDtos {

    private MetadataDtos() {
    }

    /**
     * @param requestedSchemas schemas requested from Discovery, sorted; empty when {@code allSchemas}
     */
    public record SnapshotResponse(
            UUID id,
            UUID dataSourceId,
            String contractVersion,
            String engine,
            String databaseName,
            String productVersion,
            Instant discoveredAt,
            boolean allSchemas,
            List<String> requestedSchemas,
            String contentSha256,
            MetadataSnapshot.Outcome outcome,
            Summary summary,
            String importedBy,
            Instant importedAt) {

        static SnapshotResponse from(MetadataSnapshot s) {
            MetadataSnapshot.Summary summary = s.summary();
            return new SnapshotResponse(s.id(), s.dataSourceId().value(), s.contractVersion(), s.engine().value(),
                    s.databaseName(), s.productVersion(), s.discoveredAt(), s.requestedSchemas().isEmpty(),
                    s.requestedSchemas().stream().sorted().toList(), s.contentSha256(), s.outcome(),
                    new Summary(summary.observed(), summary.added(), summary.updated(), summary.unchanged(),
                            summary.missing(), summary.reappeared(), summary.unavailableSchemas()),
                    s.importedBy(), s.importedAt());
        }
    }

    public record Summary(int observed, int added, int updated, int unchanged, int missing, int reappeared,
                          List<String> unavailableSchemas) {
    }

    /** @param comment comment from the customer database: untrusted text, sanitized and truncated */
    public record PhysicalObjectResponse(
            UUID id,
            PhysicalObjectType type,
            String naturalKey,
            String schemaName,
            String objectName,
            String childName,
            UUID parentId,
            PhysicalObjectStatus status,
            Map<String, Object> attributes,
            String comment,
            UUID firstSeenSnapshotId,
            UUID lastSeenSnapshotId,
            UUID missingSinceSnapshotId,
            Instant createdAt,
            Instant updatedAt) {

        static PhysicalObjectResponse from(PhysicalObject o) {
            return new PhysicalObjectResponse(o.id(), o.type(), o.naturalKey().value(), o.schemaName(), o.objectName(),
                    o.childName(), o.parentId(), o.status(), o.attributes().values(), o.comment(),
                    o.firstSeenSnapshotId(), o.lastSeenSnapshotId(), o.missingSinceSnapshotId(), o.createdAt(),
                    o.updatedAt());
        }
    }
}
