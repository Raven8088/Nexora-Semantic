package com.nexora.semantic.domain.source;

import com.nexora.semantic.domain.shared.TenantId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A database object of a data source as last observed through Discovery. Its {@link #id()} is the stable reference
 * used by the rest of Semantic: it is bound to {@code (dataSource, type, naturalKey)} and survives re-imports,
 * disappearance and reappearance. Rows are never deleted.
 *
 * @param parentId        schema of a relation, relation of a column or foreign key; null for schemas
 * @param objectName      relation name (null for schemas)
 * @param childName       column or foreign key name (null for schemas and relations)
 * @param comment         comment from the source database: untrusted data, sanitized and truncated
 * @param missingSince    snapshot in which the object was first found missing; null unless MISSING
 */
public record PhysicalObject(
        UUID id,
        TenantId tenantId,
        DataSourceId dataSourceId,
        PhysicalObjectType type,
        UUID parentId,
        String schemaName,
        String objectName,
        String childName,
        NaturalKey naturalKey,
        ObjectAttributes attributes,
        String comment,
        String fingerprint,
        PhysicalObjectStatus status,
        UUID firstSeenSnapshotId,
        UUID lastSeenSnapshotId,
        UUID missingSinceSnapshotId,
        Instant createdAt,
        Instant updatedAt) {

    public PhysicalObject {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(schemaName, "schemaName");
        Objects.requireNonNull(naturalKey, "naturalKey");
        Objects.requireNonNull(attributes, "attributes");
        Objects.requireNonNull(fingerprint, "fingerprint");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(firstSeenSnapshotId, "firstSeenSnapshotId");
        Objects.requireNonNull(lastSeenSnapshotId, "lastSeenSnapshotId");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if ((type == PhysicalObjectType.SCHEMA) != (parentId == null)) {
            throw new IllegalArgumentException("only schemas have no parent");
        }
        if (status == PhysicalObjectStatus.UNAVAILABLE && type != PhysicalObjectType.SCHEMA) {
            throw new IllegalArgumentException("only schemas can be UNAVAILABLE");
        }
        if ((status == PhysicalObjectStatus.MISSING) != (missingSinceSnapshotId != null)) {
            throw new IllegalArgumentException("missingSinceSnapshotId is set exactly when the object is MISSING");
        }
    }

    /** Observed again in {@code snapshotId}: becomes ACTIVE with the observed attributes and parent. */
    PhysicalObject observed(UUID newParentId, ObjectAttributes newAttributes, String newComment, String newFingerprint,
                            UUID snapshotId, Instant now) {
        return new PhysicalObject(id, tenantId, dataSourceId, type, newParentId, schemaName, objectName, childName,
                naturalKey, newAttributes, newComment, newFingerprint, PhysicalObjectStatus.ACTIVE, firstSeenSnapshotId,
                snapshotId, null, createdAt, now);
    }

    PhysicalObject missing(UUID snapshotId, Instant now) {
        return new PhysicalObject(id, tenantId, dataSourceId, type, parentId, schemaName, objectName, childName,
                naturalKey, attributes, comment, fingerprint, PhysicalObjectStatus.MISSING, firstSeenSnapshotId,
                lastSeenSnapshotId, snapshotId, createdAt, now);
    }

    PhysicalObject unavailable(Instant now) {
        return new PhysicalObject(id, tenantId, dataSourceId, type, parentId, schemaName, objectName, childName,
                naturalKey, attributes, comment, fingerprint, PhysicalObjectStatus.UNAVAILABLE, firstSeenSnapshotId,
                lastSeenSnapshotId, null, createdAt, now);
    }

    public boolean isActive() {
        return status == PhysicalObjectStatus.ACTIVE;
    }
}
