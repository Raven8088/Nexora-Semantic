package com.nexora.semantic.application.port;

import com.nexora.semantic.domain.shared.Page;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DataSourceId;
import com.nexora.semantic.domain.source.NaturalKey;
import com.nexora.semantic.domain.source.PhysicalObject;
import com.nexora.semantic.domain.source.PhysicalObjectStatus;
import com.nexora.semantic.domain.source.PhysicalObjectType;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Physical objects of a data source; every operation is scoped to tenant and data source. Rows are never deleted. */
public interface PhysicalObjectRepository {

    /** Optional filters; null means "any". */
    record Filter(PhysicalObjectType type, String schemaName, PhysicalObjectStatus status, UUID parentId) {
        public static final Filter ALL = new Filter(null, null, null, null);
    }

    List<PhysicalObject> findAll(TenantId tenantId, DataSourceId dataSourceId);

    Optional<PhysicalObject> findById(TenantId tenantId, DataSourceId dataSourceId, UUID objectId);

    /** Objects among {@code ids} that belong to the tenant and data source; others are silently absent. */
    List<PhysicalObject> findByIds(TenantId tenantId, DataSourceId dataSourceId, Collection<UUID> ids);

    /** Objects (of any type and status) whose natural key is exactly one of {@code naturalKeys}. */
    List<PhysicalObject> findByNaturalKeys(TenantId tenantId, DataSourceId dataSourceId,
                                           Collection<NaturalKey> naturalKeys);

    /** Ordered by natural key. */
    Page<PhysicalObject> findPage(TenantId tenantId, DataSourceId dataSourceId, Filter filter, PageQuery pageQuery);

    /** Inserts in list order (parents must precede children). */
    void insertAll(List<PhysicalObject> objects);

    /** Persists the mutable state (parent, attributes, comment, status, seen references) of known objects. */
    void updateAll(List<PhysicalObject> objects);
}
