package com.nexora.semantic.infrastructure.persistence;

import com.nexora.semantic.application.port.SemanticDefinitionRepository;
import com.nexora.semantic.domain.catalog.Aggregation;
import com.nexora.semantic.domain.catalog.DefinitionKey;
import com.nexora.semantic.domain.catalog.DefinitionKind;
import com.nexora.semantic.domain.catalog.DefinitionOrigin;
import com.nexora.semantic.domain.catalog.DefinitionSpec;
import com.nexora.semantic.domain.catalog.DefinitionSpec.ConceptSpec;
import com.nexora.semantic.domain.catalog.DefinitionSpec.DimensionSpec;
import com.nexora.semantic.domain.catalog.DefinitionSpec.MetricSpec;
import com.nexora.semantic.domain.catalog.DefinitionStatus;
import com.nexora.semantic.domain.catalog.DimensionType;
import com.nexora.semantic.domain.catalog.SemanticDefinition;
import com.nexora.semantic.domain.catalog.TimeGranularity;
import com.nexora.semantic.domain.shared.DuplicateResourceException;
import com.nexora.semantic.domain.shared.Page;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.PrincipalType;
import com.nexora.semantic.domain.shared.StaleResourceException;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DataSourceId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.ZoneId;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.nexora.semantic.infrastructure.persistence.SqlTypes.instant;
import static com.nexora.semantic.infrastructure.persistence.SqlTypes.toDb;

/**
 * Every statement is scoped by {@code tenant_id} and {@code data_source_id}. The typed spec is stored in typed
 * columns (no JSON blob of expressions): composite foreign keys then guarantee in the database that references
 * stay within the tenant and data source.
 */
public class JdbcSemanticDefinitionRepository implements SemanticDefinitionRepository {

    private static final String RESOURCE = "Semantic definition";
    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };
    private static final String COLUMNS = "id, tenant_id, data_source_id, kind, key, name, description, status, origin, "
            + "relation_object_id, column_object_id, concept_id, aggregation, dimension_type, granularities, time_zone, "
            + "created_by, created_by_type, created_at, updated_by, updated_by_type, updated_at, approved_by, "
            + "approved_at, inactive_reason, row_version";

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;
    private final RowMapper<SemanticDefinition> mapper;

    public JdbcSemanticDefinitionRepository(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.mapper = (rs, rowNum) -> new SemanticDefinition(
                rs.getObject("id", UUID.class),
                new TenantId(rs.getObject("tenant_id", UUID.class)),
                new DataSourceId(rs.getObject("data_source_id", UUID.class)),
                new DefinitionKey(rs.getString("key")),
                rs.getString("name"),
                rs.getString("description"),
                spec(rs),
                DefinitionStatus.valueOf(rs.getString("status")),
                DefinitionOrigin.valueOf(rs.getString("origin")),
                new PrincipalRef(rs.getString("created_by"), PrincipalType.valueOf(rs.getString("created_by_type"))),
                instant(rs, "created_at"),
                new PrincipalRef(rs.getString("updated_by"), PrincipalType.valueOf(rs.getString("updated_by_type"))),
                instant(rs, "updated_at"),
                rs.getString("approved_by"),
                instant(rs, "approved_at"),
                rs.getString("inactive_reason"),
                rs.getLong("row_version"));
    }

    @Override
    public void insert(SemanticDefinition d) {
        try {
            jdbc.sql("INSERT INTO semantic_definition (" + COLUMNS + ") VALUES (:id, :tenantId, :dataSourceId, :kind, "
                            + ":key, :name, :description, :status, :origin, :relationId, :columnId, :conceptId, "
                            + ":aggregation, :dimensionType, CAST(:granularities AS jsonb), :timeZone, :createdBy, "
                            + ":createdByType, :createdAt, :updatedBy, :updatedByType, :updatedAt, :approvedBy, "
                            + ":approvedAt, :inactiveReason, :version)")
                    .paramSource(parameters(d).addValue("version", d.version()))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateResourceException(RESOURCE);
        }
    }

    @Override
    public SemanticDefinition update(SemanticDefinition d, long expectedVersion) {
        int updated = jdbc.sql("""
                        UPDATE semantic_definition
                           SET name = :name, description = :description, status = :status,
                               relation_object_id = :relationId, column_object_id = :columnId, concept_id = :conceptId,
                               aggregation = :aggregation, dimension_type = :dimensionType,
                               granularities = CAST(:granularities AS jsonb), time_zone = :timeZone,
                               updated_by = :updatedBy, updated_by_type = :updatedByType, updated_at = :updatedAt,
                               approved_by = :approvedBy, approved_at = :approvedAt, inactive_reason = :inactiveReason,
                               row_version = row_version + 1
                         WHERE tenant_id = :tenantId AND data_source_id = :dataSourceId AND id = :id
                           AND row_version = :expectedVersion""")
                .paramSource(parameters(d).addValue("expectedVersion", expectedVersion))
                .update();
        if (updated != 1) {
            throw new StaleResourceException(RESOURCE);
        }
        return new SemanticDefinition(d.id(), d.tenantId(), d.dataSourceId(), d.key(), d.name(), d.description(),
                d.spec(), d.status(), d.origin(), d.createdBy(), d.createdAt(), d.updatedBy(), d.updatedAt(),
                d.approvedBy(), d.approvedAt(), d.inactiveReason(), expectedVersion + 1);
    }

    @Override
    public Optional<SemanticDefinition> findById(TenantId tenantId, DataSourceId dataSourceId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM semantic_definition "
                        + "WHERE tenant_id = :tenantId AND data_source_id = :dataSourceId AND id = :id")
                .param("tenantId", tenantId.value())
                .param("dataSourceId", dataSourceId.value())
                .param("id", id)
                .query(mapper)
                .optional();
    }

    @Override
    public List<SemanticDefinition> findByIds(TenantId tenantId, DataSourceId dataSourceId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM semantic_definition "
                        + "WHERE tenant_id = :tenantId AND data_source_id = :dataSourceId AND id IN (:ids)")
                .param("tenantId", tenantId.value())
                .param("dataSourceId", dataSourceId.value())
                .param("ids", List.copyOf(ids))
                .query(mapper)
                .list();
    }

    @Override
    public List<SemanticDefinition> findByStatus(TenantId tenantId, DataSourceId dataSourceId, DefinitionStatus status) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM semantic_definition "
                        + "WHERE tenant_id = :tenantId AND data_source_id = :dataSourceId AND status = :status "
                        + "ORDER BY kind, key")
                .param("tenantId", tenantId.value())
                .param("dataSourceId", dataSourceId.value())
                .param("status", status.name())
                .query(mapper)
                .list();
    }

    @Override
    public Page<SemanticDefinition> findPage(TenantId tenantId, DataSourceId dataSourceId, Filter filter,
                                             PageQuery pageQuery) {
        StringBuilder where = new StringBuilder(" WHERE tenant_id = :tenantId AND data_source_id = :dataSourceId");
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("dataSourceId", dataSourceId.value());
        if (filter.kind() != null) {
            where.append(" AND kind = :kind");
            params.addValue("kind", filter.kind().name());
        }
        if (filter.status() != null) {
            where.append(" AND status = :status");
            params.addValue("status", filter.status().name());
        }
        long total = jdbc.sql("SELECT count(*) FROM semantic_definition" + where).paramSource(params)
                .query(Long.class).single();
        params.addValue("limit", pageQuery.size()).addValue("offset", pageQuery.offset());
        List<SemanticDefinition> items = jdbc.sql("SELECT " + COLUMNS + " FROM semantic_definition" + where
                        + " ORDER BY kind, key LIMIT :limit OFFSET :offset")
                .paramSource(params)
                .query(mapper)
                .list();
        return new Page<>(items, pageQuery.page(), pageQuery.size(), total);
    }

    private MapSqlParameterSource parameters(SemanticDefinition d) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", d.id())
                .addValue("tenantId", d.tenantId().value())
                .addValue("dataSourceId", d.dataSourceId().value())
                .addValue("kind", d.kind().name())
                .addValue("key", d.key().value())
                .addValue("name", d.name())
                .addValue("description", d.description())
                .addValue("status", d.status().name())
                .addValue("origin", d.origin().name())
                .addValue("relationId", null)
                .addValue("columnId", null)
                .addValue("conceptId", null)
                .addValue("aggregation", null)
                .addValue("dimensionType", null)
                .addValue("granularities", null)
                .addValue("timeZone", null)
                .addValue("createdBy", d.createdBy().id())
                .addValue("createdByType", d.createdBy().type().name())
                .addValue("createdAt", toDb(d.createdAt()))
                .addValue("updatedBy", d.updatedBy().id())
                .addValue("updatedByType", d.updatedBy().type().name())
                .addValue("updatedAt", toDb(d.updatedAt()))
                .addValue("approvedBy", d.approvedBy())
                .addValue("approvedAt", toDb(d.approvedAt()))
                .addValue("inactiveReason", d.inactiveReason());
        switch (d.spec()) {
            case ConceptSpec concept -> params.addValue("relationId", concept.relationId());
            case MetricSpec metric -> params.addValue("conceptId", metric.conceptId())
                    .addValue("aggregation", metric.aggregation().name())
                    .addValue("columnId", metric.columnId());
            case DimensionSpec dimension -> params.addValue("conceptId", dimension.conceptId())
                    .addValue("columnId", dimension.columnId())
                    .addValue("dimensionType", dimension.type().name())
                    .addValue("granularities", dimension.granularities().isEmpty() ? null
                            : objectMapper.writeValueAsString(dimension.granularities().stream()
                            .sorted().map(Enum::name).toList()))
                    .addValue("timeZone", dimension.timeZone() == null ? null : dimension.timeZone().getId());
        }
        return params;
    }

    private DefinitionSpec spec(ResultSet rs) throws SQLException {
        DefinitionKind kind = DefinitionKind.valueOf(rs.getString("kind"));
        return switch (kind) {
            case CONCEPT -> new ConceptSpec(rs.getObject("relation_object_id", UUID.class));
            case METRIC -> new MetricSpec(rs.getObject("concept_id", UUID.class),
                    Aggregation.valueOf(rs.getString("aggregation")), rs.getObject("column_object_id", UUID.class));
            case DIMENSION -> {
                String granularities = rs.getString("granularities");
                Set<TimeGranularity> parsed = EnumSet.noneOf(TimeGranularity.class);
                if (granularities != null) {
                    objectMapper.readValue(granularities, STRINGS).forEach(g -> parsed.add(TimeGranularity.valueOf(g)));
                }
                String timeZone = rs.getString("time_zone");
                yield new DimensionSpec(rs.getObject("concept_id", UUID.class),
                        rs.getObject("column_object_id", UUID.class),
                        DimensionType.valueOf(rs.getString("dimension_type")), parsed,
                        timeZone == null ? null : ZoneId.of(timeZone));
            }
        };
    }
}
