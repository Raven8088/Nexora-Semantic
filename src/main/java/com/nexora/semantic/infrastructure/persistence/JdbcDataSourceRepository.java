package com.nexora.semantic.infrastructure.persistence;

import com.nexora.semantic.application.port.DataSourceRepository;
import com.nexora.semantic.domain.shared.DuplicateResourceException;
import com.nexora.semantic.domain.shared.Page;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.shared.StaleResourceException;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DataSource;
import com.nexora.semantic.domain.source.DataSourceId;
import com.nexora.semantic.domain.source.DataSourceKey;
import com.nexora.semantic.domain.source.DataSourceStatus;
import com.nexora.semantic.domain.source.EngineCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;
import java.util.UUID;

import static com.nexora.semantic.infrastructure.persistence.SqlTypes.instant;
import static com.nexora.semantic.infrastructure.persistence.SqlTypes.toDb;

/** Every statement filters by {@code tenant_id}; there is no query that can return another tenant's rows. */
public class JdbcDataSourceRepository implements DataSourceRepository {

    private static final String RESOURCE = "Data source";
    private static final String COLUMNS = "id, tenant_id, key, name, engine, database_name, description, status, "
            + "created_at, created_by, updated_at, updated_by, row_version";

    private static final RowMapper<DataSource> MAPPER = (rs, rowNum) -> new DataSource(
            new DataSourceId(rs.getObject("id", UUID.class)),
            new TenantId(rs.getObject("tenant_id", UUID.class)),
            new DataSourceKey(rs.getString("key")),
            rs.getString("name"),
            new EngineCode(rs.getString("engine")),
            rs.getString("database_name"),
            rs.getString("description"),
            DataSourceStatus.valueOf(rs.getString("status")),
            instant(rs, "created_at"),
            rs.getString("created_by"),
            instant(rs, "updated_at"),
            rs.getString("updated_by"),
            rs.getLong("row_version"));

    private final JdbcClient jdbc;

    public JdbcDataSourceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(DataSource ds) {
        try {
            jdbc.sql("INSERT INTO data_source (" + COLUMNS + ") VALUES (:id, :tenantId, :key, :name, :engine, "
                            + ":databaseName, :description, :status, :createdAt, :createdBy, :updatedAt, :updatedBy, "
                            + ":version)")
                    .param("id", ds.id().value())
                    .param("tenantId", ds.tenantId().value())
                    .param("key", ds.key().value())
                    .param("name", ds.name())
                    .param("engine", ds.engine().value())
                    .param("databaseName", ds.databaseName())
                    .param("description", ds.description())
                    .param("status", ds.status().name())
                    .param("createdAt", toDb(ds.createdAt()))
                    .param("createdBy", ds.createdBy())
                    .param("updatedAt", toDb(ds.updatedAt()))
                    .param("updatedBy", ds.updatedBy())
                    .param("version", ds.version())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateResourceException(RESOURCE);
        }
    }

    @Override
    public Optional<DataSource> findById(TenantId tenantId, DataSourceId id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM data_source WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId.value())
                .param("id", id.value())
                .query(MAPPER)
                .optional();
    }

    @Override
    public Page<DataSource> findPage(TenantId tenantId, DataSourceStatus status, PageQuery pageQuery) {
        String where = " WHERE tenant_id = :tenantId" + (status == null ? "" : " AND status = :status");
        var count = jdbc.sql("SELECT count(*) FROM data_source" + where).param("tenantId", tenantId.value());
        var select = jdbc.sql("SELECT " + COLUMNS + " FROM data_source" + where
                        + " ORDER BY key LIMIT :limit OFFSET :offset")
                .param("tenantId", tenantId.value())
                .param("limit", pageQuery.size())
                .param("offset", pageQuery.offset());
        if (status != null) {
            count = count.param("status", status.name());
            select = select.param("status", status.name());
        }
        long total = count.query(Long.class).single();
        return new Page<>(select.query(MAPPER).list(), pageQuery.page(), pageQuery.size(), total);
    }

    @Override
    public DataSource update(DataSource ds, long expectedVersion) {
        int updated = jdbc.sql("""
                        UPDATE data_source
                           SET name = :name, description = :description, status = :status,
                               updated_at = :updatedAt, updated_by = :updatedBy, row_version = row_version + 1
                         WHERE tenant_id = :tenantId AND id = :id AND row_version = :expectedVersion""")
                .param("name", ds.name())
                .param("description", ds.description())
                .param("status", ds.status().name())
                .param("updatedAt", toDb(ds.updatedAt()))
                .param("updatedBy", ds.updatedBy())
                .param("tenantId", ds.tenantId().value())
                .param("id", ds.id().value())
                .param("expectedVersion", expectedVersion)
                .update();
        if (updated != 1) {
            throw new StaleResourceException(RESOURCE);
        }
        return new DataSource(ds.id(), ds.tenantId(), ds.key(), ds.name(), ds.engine(), ds.databaseName(),
                ds.description(), ds.status(), ds.createdAt(), ds.createdBy(), ds.updatedAt(), ds.updatedBy(),
                expectedVersion + 1);
    }
}
