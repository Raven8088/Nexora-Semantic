package com.nexora.semantic.infrastructure.persistence;

import com.nexora.semantic.application.port.TenantRepository;
import com.nexora.semantic.domain.shared.DuplicateResourceException;
import com.nexora.semantic.domain.shared.Page;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.shared.StaleResourceException;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.tenant.Tenant;
import com.nexora.semantic.domain.tenant.TenantKey;
import com.nexora.semantic.domain.tenant.TenantStatus;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.Optional;
import java.util.UUID;

import static com.nexora.semantic.infrastructure.persistence.SqlTypes.instant;
import static com.nexora.semantic.infrastructure.persistence.SqlTypes.toDb;

public class JdbcTenantRepository implements TenantRepository {

    private static final String RESOURCE = "Tenant";
    private static final String COLUMNS =
            "id, key, name, status, created_at, created_by, updated_at, updated_by, row_version";

    private static final RowMapper<Tenant> MAPPER = (rs, rowNum) -> new Tenant(
            new TenantId(rs.getObject("id", UUID.class)),
            new TenantKey(rs.getString("key")),
            rs.getString("name"),
            TenantStatus.valueOf(rs.getString("status")),
            instant(rs, "created_at"),
            rs.getString("created_by"),
            instant(rs, "updated_at"),
            rs.getString("updated_by"),
            rs.getLong("row_version"));

    private final JdbcClient jdbc;

    public JdbcTenantRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(Tenant tenant) {
        try {
            jdbc.sql("INSERT INTO tenant (" + COLUMNS + ") VALUES "
                            + "(:id, :key, :name, :status, :createdAt, :createdBy, :updatedAt, :updatedBy, :version)")
                    .param("id", tenant.id().value())
                    .param("key", tenant.key().value())
                    .param("name", tenant.name())
                    .param("status", tenant.status().name())
                    .param("createdAt", toDb(tenant.createdAt()))
                    .param("createdBy", tenant.createdBy())
                    .param("updatedAt", toDb(tenant.updatedAt()))
                    .param("updatedBy", tenant.updatedBy())
                    .param("version", tenant.version())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateResourceException(RESOURCE);
        }
    }

    @Override
    public Optional<Tenant> findById(TenantId id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tenant WHERE id = :id")
                .param("id", id.value())
                .query(MAPPER)
                .optional();
    }

    @Override
    public Optional<Tenant> findByKey(TenantKey key) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tenant WHERE key = :key")
                .param("key", key.value())
                .query(MAPPER)
                .optional();
    }

    @Override
    public Page<Tenant> findAll(PageQuery pageQuery) {
        long total = jdbc.sql("SELECT count(*) FROM tenant").query(Long.class).single();
        var items = jdbc.sql("SELECT " + COLUMNS + " FROM tenant ORDER BY key LIMIT :limit OFFSET :offset")
                .param("limit", pageQuery.size())
                .param("offset", pageQuery.offset())
                .query(MAPPER)
                .list();
        return new Page<>(items, pageQuery.page(), pageQuery.size(), total);
    }

    @Override
    public Tenant update(Tenant tenant, long expectedVersion) {
        int updated = jdbc.sql("""
                        UPDATE tenant
                           SET name = :name, status = :status, updated_at = :updatedAt, updated_by = :updatedBy,
                               row_version = row_version + 1
                         WHERE id = :id AND row_version = :expectedVersion""")
                .param("name", tenant.name())
                .param("status", tenant.status().name())
                .param("updatedAt", toDb(tenant.updatedAt()))
                .param("updatedBy", tenant.updatedBy())
                .param("id", tenant.id().value())
                .param("expectedVersion", expectedVersion)
                .update();
        if (updated != 1) {
            throw new StaleResourceException(RESOURCE);
        }
        return new Tenant(tenant.id(), tenant.key(), tenant.name(), tenant.status(), tenant.createdAt(),
                tenant.createdBy(), tenant.updatedAt(), tenant.updatedBy(), expectedVersion + 1);
    }
}
