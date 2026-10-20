package com.nexora.semantic.infrastructure.persistence;

import com.nexora.semantic.application.port.PhysicalObjectRepository;
import com.nexora.semantic.domain.shared.Page;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DataSourceId;
import com.nexora.semantic.domain.source.NaturalKey;
import com.nexora.semantic.domain.source.ObjectAttributes;
import com.nexora.semantic.domain.source.PhysicalObject;
import com.nexora.semantic.domain.source.PhysicalObjectStatus;
import com.nexora.semantic.domain.source.PhysicalObjectType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.nexora.semantic.infrastructure.persistence.SqlTypes.instant;
import static com.nexora.semantic.infrastructure.persistence.SqlTypes.toDb;

/**
 * Every statement is scoped by {@code tenant_id} and {@code data_source_id}. Writes are batched (an import can touch
 * tens of thousands of objects) with {@link NamedParameterJdbcOperations}, since {@link JdbcClient} has no batch API.
 * Only mutable columns are updated; identity columns are protected by column privileges and a trigger (V5).
 */
public class JdbcPhysicalObjectRepository implements PhysicalObjectRepository {

    private static final int BATCH_SIZE = 1000;
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {
    };
    private static final String COLUMNS = "id, tenant_id, data_source_id, object_type, parent_id, schema_name, "
            + "object_name, child_name, natural_key, attributes, comment, attributes_sha256, status, "
            + "first_seen_snapshot_id, last_seen_snapshot_id, missing_since_snapshot_id, created_at, updated_at";
    private static final String INSERT = "INSERT INTO physical_object (" + COLUMNS + ") VALUES (:id, :tenantId, "
            + ":dataSourceId, :type, :parentId, :schemaName, :objectName, :childName, :naturalKey, "
            + "CAST(:attributes AS jsonb), :comment, :fingerprint, :status, :firstSeen, :lastSeen, :missingSince, "
            + ":createdAt, :updatedAt)";
    private static final String UPDATE = """
            UPDATE physical_object
               SET parent_id = :parentId, attributes = CAST(:attributes AS jsonb), comment = :comment,
                   attributes_sha256 = :fingerprint, status = :status, last_seen_snapshot_id = :lastSeen,
                   missing_since_snapshot_id = :missingSince, updated_at = :updatedAt
             WHERE tenant_id = :tenantId AND data_source_id = :dataSourceId AND id = :id""";

    private final JdbcClient jdbc;
    private final NamedParameterJdbcOperations batch;
    private final ObjectMapper objectMapper;
    private final RowMapper<PhysicalObject> mapper;

    public JdbcPhysicalObjectRepository(JdbcClient jdbc, NamedParameterJdbcOperations batch, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.batch = batch;
        this.objectMapper = objectMapper;
        this.mapper = (rs, rowNum) -> new PhysicalObject(
                rs.getObject("id", UUID.class),
                new TenantId(rs.getObject("tenant_id", UUID.class)),
                new DataSourceId(rs.getObject("data_source_id", UUID.class)),
                PhysicalObjectType.valueOf(rs.getString("object_type")),
                rs.getObject("parent_id", UUID.class),
                rs.getString("schema_name"),
                rs.getString("object_name"),
                rs.getString("child_name"),
                new NaturalKey(rs.getString("natural_key")),
                ObjectAttributes.of(objectMapper.readValue(rs.getString("attributes"), MAP)),
                rs.getString("comment"),
                rs.getString("attributes_sha256"),
                PhysicalObjectStatus.valueOf(rs.getString("status")),
                rs.getObject("first_seen_snapshot_id", UUID.class),
                rs.getObject("last_seen_snapshot_id", UUID.class),
                rs.getObject("missing_since_snapshot_id", UUID.class),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    @Override
    public List<PhysicalObject> findAll(TenantId tenantId, DataSourceId dataSourceId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM physical_object "
                        + "WHERE tenant_id = :tenantId AND data_source_id = :dataSourceId")
                .param("tenantId", tenantId.value())
                .param("dataSourceId", dataSourceId.value())
                .query(mapper)
                .list();
    }

    @Override
    public Optional<PhysicalObject> findById(TenantId tenantId, DataSourceId dataSourceId, UUID objectId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM physical_object "
                        + "WHERE tenant_id = :tenantId AND data_source_id = :dataSourceId AND id = :id")
                .param("tenantId", tenantId.value())
                .param("dataSourceId", dataSourceId.value())
                .param("id", objectId)
                .query(mapper)
                .optional();
    }

    @Override
    public Page<PhysicalObject> findPage(TenantId tenantId, DataSourceId dataSourceId, Filter filter,
                                         PageQuery pageQuery) {
        StringBuilder where = new StringBuilder(" WHERE tenant_id = :tenantId AND data_source_id = :dataSourceId");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("dataSourceId", dataSourceId.value());
        if (filter.type() != null) {
            where.append(" AND object_type = :type");
            params.addValue("type", filter.type().name());
        }
        if (filter.schemaName() != null) {
            where.append(" AND schema_name = :schemaName");
            params.addValue("schemaName", filter.schemaName());
        }
        if (filter.status() != null) {
            where.append(" AND status = :status");
            params.addValue("status", filter.status().name());
        }
        if (filter.parentId() != null) {
            where.append(" AND parent_id = :parentId");
            params.addValue("parentId", filter.parentId());
        }
        long total = jdbc.sql("SELECT count(*) FROM physical_object" + where).paramSource(params)
                .query(Long.class).single();
        params.addValue("limit", pageQuery.size()).addValue("offset", pageQuery.offset());
        List<PhysicalObject> items = jdbc.sql("SELECT " + COLUMNS + " FROM physical_object" + where
                        + " ORDER BY natural_key, object_type LIMIT :limit OFFSET :offset")
                .paramSource(params)
                .query(mapper)
                .list();
        return new Page<>(items, pageQuery.page(), pageQuery.size(), total);
    }

    @Override
    public void insertAll(List<PhysicalObject> objects) {
        executeInBatches(INSERT, objects);
    }

    @Override
    public void updateAll(List<PhysicalObject> objects) {
        executeInBatches(UPDATE, objects);
    }

    private void executeInBatches(String sql, List<PhysicalObject> objects) {
        for (int from = 0; from < objects.size(); from += BATCH_SIZE) {
            List<PhysicalObject> chunk = objects.subList(from, Math.min(from + BATCH_SIZE, objects.size()));
            batch.batchUpdate(sql, chunk.stream().map(this::parameters).toArray(SqlParameterSource[]::new));
        }
    }

    private SqlParameterSource parameters(PhysicalObject o) {
        return new MapSqlParameterSource()
                .addValue("id", o.id())
                .addValue("tenantId", o.tenantId().value())
                .addValue("dataSourceId", o.dataSourceId().value())
                .addValue("type", o.type().name())
                .addValue("parentId", o.parentId())
                .addValue("schemaName", o.schemaName())
                .addValue("objectName", o.objectName())
                .addValue("childName", o.childName())
                .addValue("naturalKey", o.naturalKey().value())
                .addValue("attributes", objectMapper.writeValueAsString(o.attributes().values()))
                .addValue("comment", o.comment())
                .addValue("fingerprint", o.fingerprint())
                .addValue("status", o.status().name())
                .addValue("firstSeen", o.firstSeenSnapshotId())
                .addValue("lastSeen", o.lastSeenSnapshotId())
                .addValue("missingSince", o.missingSinceSnapshotId())
                .addValue("createdAt", toDb(o.createdAt()))
                .addValue("updatedAt", toDb(o.updatedAt()));
    }
}
