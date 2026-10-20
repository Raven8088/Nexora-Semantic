package com.nexora.semantic.infrastructure.persistence;

import com.nexora.semantic.application.port.MetadataSnapshotRepository;
import com.nexora.semantic.domain.shared.Page;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DataSourceId;
import com.nexora.semantic.domain.source.EngineCode;
import com.nexora.semantic.domain.source.MetadataSnapshot;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

import static com.nexora.semantic.infrastructure.persistence.SqlTypes.instant;
import static com.nexora.semantic.infrastructure.persistence.SqlTypes.toDb;

/** Append-only: there is no update method, and the table rejects UPDATE/DELETE/TRUNCATE. */
public class JdbcMetadataSnapshotRepository implements MetadataSnapshotRepository {

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final String COLUMNS = "id, tenant_id, data_source_id, contract_version, engine, database_name, "
            + "product_version, discovered_at, requested_schemas, content_sha256, outcome, observed_count, added_count, "
            + "updated_count, unchanged_count, missing_count, reappeared_count, unavailable_schemas, imported_by, "
            + "imported_at";

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<MetadataSnapshot> mapper;

    public JdbcMetadataSnapshotRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.mapper = (rs, rowNum) -> new MetadataSnapshot(
                rs.getObject("id", UUID.class),
                new TenantId(rs.getObject("tenant_id", UUID.class)),
                new DataSourceId(rs.getObject("data_source_id", UUID.class)),
                rs.getString("contract_version"),
                new EngineCode(rs.getString("engine")),
                rs.getString("database_name"),
                rs.getString("product_version"),
                instant(rs, "discovered_at"),
                new HashSet<>(readList(rs.getString("requested_schemas"))),
                rs.getString("content_sha256"),
                MetadataSnapshot.Outcome.valueOf(rs.getString("outcome")),
                new MetadataSnapshot.Summary(rs.getInt("observed_count"), rs.getInt("added_count"),
                        rs.getInt("updated_count"), rs.getInt("unchanged_count"), rs.getInt("missing_count"),
                        rs.getInt("reappeared_count"), readList(rs.getString("unavailable_schemas"))),
                rs.getString("imported_by"),
                instant(rs, "imported_at"));
    }

    @Override
    public void insert(MetadataSnapshot s) {
        jdbc.sql("INSERT INTO metadata_snapshot (" + COLUMNS + ") VALUES (:id, :tenantId, :dataSourceId, "
                        + ":contractVersion, :engine, :databaseName, :productVersion, :discoveredAt, "
                        + "CAST(:requestedSchemas AS jsonb), :contentSha256, :outcome, :observed, :added, :updated, "
                        + ":unchanged, :missing, :reappeared, CAST(:unavailableSchemas AS jsonb), :importedBy, :importedAt)")
                .param("id", s.id())
                .param("tenantId", s.tenantId().value())
                .param("dataSourceId", s.dataSourceId().value())
                .param("contractVersion", s.contractVersion())
                .param("engine", s.engine().value())
                .param("databaseName", s.databaseName())
                .param("productVersion", s.productVersion())
                .param("discoveredAt", toDb(s.discoveredAt()))
                .param("requestedSchemas", objectMapper.writeValueAsString(new TreeSet<>(s.requestedSchemas())))
                .param("contentSha256", s.contentSha256())
                .param("outcome", s.outcome().name())
                .param("observed", s.summary().observed())
                .param("added", s.summary().added())
                .param("updated", s.summary().updated())
                .param("unchanged", s.summary().unchanged())
                .param("missing", s.summary().missing())
                .param("reappeared", s.summary().reappeared())
                .param("unavailableSchemas", objectMapper.writeValueAsString(s.summary().unavailableSchemas()))
                .param("importedBy", s.importedBy())
                .param("importedAt", toDb(s.importedAt()))
                .update();
    }

    @Override
    public Optional<MetadataSnapshot> findById(TenantId tenantId, DataSourceId dataSourceId, UUID snapshotId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM metadata_snapshot "
                        + "WHERE tenant_id = :tenantId AND data_source_id = :dataSourceId AND id = :id")
                .param("tenantId", tenantId.value())
                .param("dataSourceId", dataSourceId.value())
                .param("id", snapshotId)
                .query(mapper)
                .optional();
    }

    @Override
    public Page<MetadataSnapshot> findPage(TenantId tenantId, DataSourceId dataSourceId, PageQuery pageQuery) {
        long total = jdbc.sql("SELECT count(*) FROM metadata_snapshot "
                        + "WHERE tenant_id = :tenantId AND data_source_id = :dataSourceId")
                .param("tenantId", tenantId.value())
                .param("dataSourceId", dataSourceId.value())
                .query(Long.class).single();
        List<MetadataSnapshot> items = jdbc.sql("SELECT " + COLUMNS + " FROM metadata_snapshot "
                        + "WHERE tenant_id = :tenantId AND data_source_id = :dataSourceId "
                        + "ORDER BY imported_at DESC, id LIMIT :limit OFFSET :offset")
                .param("tenantId", tenantId.value())
                .param("dataSourceId", dataSourceId.value())
                .param("limit", pageQuery.size())
                .param("offset", pageQuery.offset())
                .query(mapper)
                .list();
        return new Page<>(items, pageQuery.page(), pageQuery.size(), total);
    }

    private List<String> readList(String json) {
        return json == null ? List.of() : objectMapper.readValue(json, STRINGS);
    }
}
