package com.nexora.semantic.application.port;

import com.nexora.semantic.domain.shared.Page;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DataSource;
import com.nexora.semantic.domain.source.DataSourceId;
import com.nexora.semantic.domain.source.DataSourceStatus;

import java.util.Optional;

/** Every read and write is scoped to a tenant: there is no way to reach another tenant's data sources. */
public interface DataSourceRepository {

    /** @throws com.nexora.semantic.domain.shared.DuplicateResourceException if the key is used in the tenant */
    void insert(DataSource dataSource);

    Optional<DataSource> findById(TenantId tenantId, DataSourceId id);

    /**
     * Like {@link #findById} but locks the row until the end of the transaction, serializing concurrent metadata
     * imports into the same data source.
     */
    Optional<DataSource> lockById(TenantId tenantId, DataSourceId id);

    /** @param status optional filter; null returns every status */
    Page<DataSource> findPage(TenantId tenantId, DataSourceStatus status, PageQuery pageQuery);

    /**
     * Persists {@code dataSource} (within its own tenant) if the stored version equals {@code expectedVersion}.
     *
     * @return the data source with its new version
     * @throws com.nexora.semantic.domain.shared.StaleResourceException otherwise
     */
    DataSource update(DataSource dataSource, long expectedVersion);
}
