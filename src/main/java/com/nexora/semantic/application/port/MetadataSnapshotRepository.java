package com.nexora.semantic.application.port;

import com.nexora.semantic.domain.shared.Page;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DataSourceId;
import com.nexora.semantic.domain.source.MetadataSnapshot;

import java.util.Optional;
import java.util.UUID;

/** Append-only import history, scoped to tenant and data source. */
public interface MetadataSnapshotRepository {

    void insert(MetadataSnapshot snapshot);

    Optional<MetadataSnapshot> findById(TenantId tenantId, DataSourceId dataSourceId, UUID snapshotId);

    /** Newest first. */
    Page<MetadataSnapshot> findPage(TenantId tenantId, DataSourceId dataSourceId, PageQuery pageQuery);
}
