package com.nexora.semantic.application.source;

import com.nexora.semantic.application.catalog.CatalogInvalidationService;
import com.nexora.semantic.application.catalog.RelationshipInvalidationService;
import com.nexora.semantic.application.port.AuditLog;
import com.nexora.semantic.application.port.DataSourceRepository;
import com.nexora.semantic.application.port.DiscoveredMetadataReader;
import com.nexora.semantic.application.port.MetadataSnapshotRepository;
import com.nexora.semantic.application.port.PhysicalObjectRepository;
import com.nexora.semantic.domain.audit.AuditAction;
import com.nexora.semantic.domain.audit.AuditEvent;
import com.nexora.semantic.domain.audit.AuditTargetType;
import com.nexora.semantic.domain.shared.CallerContext;
import com.nexora.semantic.domain.shared.ResourceNotFoundException;
import com.nexora.semantic.domain.source.DataSource;
import com.nexora.semantic.domain.source.DataSourceArchivedException;
import com.nexora.semantic.domain.source.DataSourceId;
import com.nexora.semantic.domain.source.DiscoveredDatabase;
import com.nexora.semantic.domain.source.MetadataReconciler;
import com.nexora.semantic.domain.source.MetadataSnapshot;
import com.nexora.semantic.domain.source.SnapshotMismatchException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Imports Nexora Discovery metadata into a data source of the caller's tenant (S2 Basic). One transaction: the data
 * source row is locked (imports into the same source are serialized), the payload is reconciled with the known
 * objects, approved semantic definitions (S3 Basic) and relationships (S3.1) that no longer validate are invalidated,
 * and the snapshot, the object changes, the invalidations and the audit events are written together or not at all.
 */
@Transactional
public class MetadataImportService {

    private final DataSourceRepository dataSources;
    private final DiscoveredMetadataReader reader;
    private final MetadataSnapshotRepository snapshots;
    private final PhysicalObjectRepository objects;
    private final AuditLog auditLog;
    private final CatalogInvalidationService catalogInvalidation;
    private final RelationshipInvalidationService relationshipInvalidation;
    private final Clock clock;
    private final MetadataReconciler reconciler = new MetadataReconciler();

    public MetadataImportService(DataSourceRepository dataSources, DiscoveredMetadataReader reader,
                                 MetadataSnapshotRepository snapshots, PhysicalObjectRepository objects,
                                 AuditLog auditLog, CatalogInvalidationService catalogInvalidation,
                                 RelationshipInvalidationService relationshipInvalidation, Clock clock) {
        this.dataSources = dataSources;
        this.reader = reader;
        this.snapshots = snapshots;
        this.objects = objects;
        this.auditLog = auditLog;
        this.catalogInvalidation = catalogInvalidation;
        this.relationshipInvalidation = relationshipInvalidation;
        this.clock = clock;
    }

    /**
     * @param document Semantic's import envelope around a Discovery response (see docs/ARCHITECTURE.md §9.1)
     * @throws ResourceNotFoundException   if the data source does not exist in the caller's tenant
     * @throws DataSourceArchivedException if the data source is archived
     * @throws com.nexora.semantic.domain.source.InvalidSnapshotException if the document is not acceptable
     * @throws SnapshotMismatchException   if the payload describes another engine or database
     */
    public MetadataSnapshot importSnapshot(CallerContext caller, DataSourceId dataSourceId, byte[] document) {
        DiscoveredDatabase database = reader.read(document);
        DataSource dataSource = dataSources.lockById(caller.tenantId(), dataSourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Data source"));
        if (!dataSource.isActive()) {
            throw new DataSourceArchivedException();
        }
        if (!dataSource.engine().equals(database.engine())) {
            throw new SnapshotMismatchException("payload.engine");
        }
        if (!dataSource.databaseName().equals(database.databaseName())) {
            throw new SnapshotMismatchException("payload.database");
        }

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        MetadataReconciler.Result result = reconciler.reconcile(caller.tenantId(), dataSourceId, database,
                objects.findAll(caller.tenantId(), dataSourceId), UUID.randomUUID(), caller.principal().id(), now,
                UUID::randomUUID);
        MetadataSnapshot snapshot = result.snapshot();
        snapshots.insert(snapshot);
        objects.insertAll(result.toInsert());
        objects.updateAll(result.toUpdate());
        int invalidated = catalogInvalidation.invalidateAfterImport(caller, dataSource, snapshot.id(), now);
        int invalidatedRelationships = relationshipInvalidation.invalidateAfterImport(caller, dataSource,
                snapshot.id(), now);

        Map<String, Object> details = new LinkedHashMap<>();
        details.put("dataSourceId", dataSourceId.value().toString());
        details.put("outcome", snapshot.outcome().name());
        details.put("observed", snapshot.summary().observed());
        details.put("added", snapshot.summary().added());
        details.put("updated", snapshot.summary().updated());
        details.put("missing", snapshot.summary().missing());
        details.put("reappeared", snapshot.summary().reappeared());
        details.put("unavailableSchemas", snapshot.summary().unavailableSchemas());
        details.put("invalidatedDefinitions", invalidated);
        details.put("invalidatedRelationships", invalidatedRelationships);
        auditLog.append(AuditEvent.of(caller.tenantId(), now, caller.principal(), caller.correlationId(),
                AuditAction.METADATA_SNAPSHOT_IMPORTED, AuditTargetType.METADATA_SNAPSHOT, snapshot.id(), details));
        return snapshot;
    }
}
