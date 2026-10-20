package com.nexora.semantic.application.source;

import com.nexora.semantic.application.port.DataSourceRepository;
import com.nexora.semantic.application.port.MetadataSnapshotRepository;
import com.nexora.semantic.application.port.PhysicalObjectRepository;
import com.nexora.semantic.domain.shared.CallerContext;
import com.nexora.semantic.domain.shared.Page;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.shared.ResourceNotFoundException;
import com.nexora.semantic.domain.source.DataSourceId;
import com.nexora.semantic.domain.source.MetadataSnapshot;
import com.nexora.semantic.domain.source.PhysicalObject;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Read access to imported metadata. The data source must exist in the caller's tenant (404 otherwise). */
@Transactional(readOnly = true)
public class PhysicalMetadataQueryService {

    private final DataSourceRepository dataSources;
    private final MetadataSnapshotRepository snapshots;
    private final PhysicalObjectRepository objects;

    public PhysicalMetadataQueryService(DataSourceRepository dataSources, MetadataSnapshotRepository snapshots,
                                        PhysicalObjectRepository objects) {
        this.dataSources = dataSources;
        this.snapshots = snapshots;
        this.objects = objects;
    }

    public Page<MetadataSnapshot> snapshots(CallerContext caller, DataSourceId dataSourceId, PageQuery pageQuery) {
        requireDataSource(caller, dataSourceId);
        return snapshots.findPage(caller.tenantId(), dataSourceId, pageQuery);
    }

    public MetadataSnapshot snapshot(CallerContext caller, DataSourceId dataSourceId, UUID snapshotId) {
        requireDataSource(caller, dataSourceId);
        return snapshots.findById(caller.tenantId(), dataSourceId, snapshotId)
                .orElseThrow(() -> new ResourceNotFoundException("Metadata snapshot"));
    }

    public Page<PhysicalObject> objects(CallerContext caller, DataSourceId dataSourceId,
                                        PhysicalObjectRepository.Filter filter, PageQuery pageQuery) {
        requireDataSource(caller, dataSourceId);
        return objects.findPage(caller.tenantId(), dataSourceId, filter, pageQuery);
    }

    public PhysicalObject object(CallerContext caller, DataSourceId dataSourceId, UUID objectId) {
        requireDataSource(caller, dataSourceId);
        return objects.findById(caller.tenantId(), dataSourceId, objectId)
                .orElseThrow(() -> new ResourceNotFoundException("Physical object"));
    }

    private void requireDataSource(CallerContext caller, DataSourceId dataSourceId) {
        if (dataSources.findById(caller.tenantId(), dataSourceId).isEmpty()) {
            throw new ResourceNotFoundException("Data source");
        }
    }
}
