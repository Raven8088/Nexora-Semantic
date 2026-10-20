package com.nexora.semantic.infrastructure.persistence;

import com.nexora.semantic.application.port.SemanticRelationshipRepository;
import com.nexora.semantic.domain.catalog.DefinitionStatus;
import com.nexora.semantic.domain.relationship.ColumnPair;
import com.nexora.semantic.domain.relationship.DeclaredCardinality;
import com.nexora.semantic.domain.relationship.EquivalentRelationshipException;
import com.nexora.semantic.domain.relationship.JoinType;
import com.nexora.semantic.domain.relationship.RelationshipOrigin;
import com.nexora.semantic.domain.relationship.SemanticRelationship;
import com.nexora.semantic.domain.shared.DuplicateResourceException;
import com.nexora.semantic.domain.shared.Page;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.PrincipalType;
import com.nexora.semantic.domain.shared.StaleResourceException;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DataSourceId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.nexora.semantic.infrastructure.persistence.SqlTypes.instant;
import static com.nexora.semantic.infrastructure.persistence.SqlTypes.toDb;

/**
 * Every statement is scoped by {@code tenant_id} and {@code data_source_id}. Pairs live in an append-only table
 * whose composite foreign keys keep every column inside the tenant and data source; the signature column backs the
 * "no two equivalent APPROVED relationships" unique index.
 */
public class JdbcSemanticRelationshipRepository implements SemanticRelationshipRepository {

    private static final String RESOURCE = "Semantic relationship";
    private static final String COLUMNS = "id, tenant_id, data_source_id, name, description, origin, source_object_id, "
            + "target_object_id, foreign_key_object_id, pair_count, signature, declared_cardinality, allow_inner_join, "
            + "allow_left_join, replaces_id, status, created_by, created_by_type, created_at, updated_by, "
            + "updated_by_type, updated_at, approved_by, approved_at, inactive_reason, row_version";

    /** Row without its pairs, completed by {@link #withPairs}. */
    private record Row(UUID id, TenantId tenantId, DataSourceId dataSourceId, String name, String description,
                       RelationshipOrigin origin, UUID sourceObjectId, UUID targetObjectId, UUID foreignKeyId,
                       DeclaredCardinality cardinality, Set<JoinType> joinTypes, UUID replacesId,
                       DefinitionStatus status, PrincipalRef createdBy, Instant createdAt,
                       PrincipalRef updatedBy, Instant updatedAt, String approvedBy,
                       Instant approvedAt, String inactiveReason, long version) {
    }

    private final JdbcClient jdbc;

    public JdbcSemanticRelationshipRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insert(SemanticRelationship r) {
        try {
            jdbc.sql("INSERT INTO semantic_relationship (" + COLUMNS + ") VALUES (:id, :tenantId, :dataSourceId, "
                            + ":name, :description, :origin, :sourceId, :targetId, :foreignKeyId, :pairCount, "
                            + ":signature, :cardinality, :allowInner, :allowLeft, :replacesId, :status, :createdBy, "
                            + ":createdByType, :createdAt, :updatedBy, :updatedByType, :updatedAt, :approvedBy, "
                            + ":approvedAt, :inactiveReason, :version)")
                    .paramSource(parameters(r).addValue("version", r.version()))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateResourceException(RESOURCE);
        }
        for (int i = 0; i < r.pairs().size(); i++) {
            ColumnPair pair = r.pairs().get(i);
            jdbc.sql("INSERT INTO semantic_relationship_pair (tenant_id, data_source_id, relationship_id, ordinal, "
                            + "source_column_id, target_column_id) VALUES (:tenantId, :dataSourceId, :id, :ordinal, "
                            + ":sourceColumnId, :targetColumnId)")
                    .param("tenantId", r.tenantId().value())
                    .param("dataSourceId", r.dataSourceId().value())
                    .param("id", r.id())
                    .param("ordinal", i)
                    .param("sourceColumnId", pair.sourceColumnId())
                    .param("targetColumnId", pair.targetColumnId())
                    .update();
        }
    }

    @Override
    public SemanticRelationship update(SemanticRelationship r, long expectedVersion) {
        int updated;
        try {
            updated = jdbc.sql("""
                            UPDATE semantic_relationship
                               SET name = :name, description = :description, declared_cardinality = :cardinality,
                                   allow_inner_join = :allowInner, allow_left_join = :allowLeft, status = :status,
                                   updated_by = :updatedBy, updated_by_type = :updatedByType, updated_at = :updatedAt,
                                   approved_by = :approvedBy, approved_at = :approvedAt,
                                   inactive_reason = :inactiveReason, row_version = row_version + 1
                             WHERE tenant_id = :tenantId AND data_source_id = :dataSourceId AND id = :id
                               AND row_version = :expectedVersion""")
                    .paramSource(parameters(r).addValue("expectedVersion", expectedVersion))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new EquivalentRelationshipException(null);
        }
        if (updated != 1) {
            throw new StaleResourceException(RESOURCE);
        }
        return r.withVersion(expectedVersion + 1);
    }

    @Override
    public Optional<SemanticRelationship> findById(TenantId tenantId, DataSourceId dataSourceId, UUID id) {
        return query(" WHERE tenant_id = :tenantId AND data_source_id = :dataSourceId AND id = :id", "",
                scope(tenantId, dataSourceId).addValue("id", id)).stream().findFirst();
    }

    @Override
    public List<SemanticRelationship> findByStatus(TenantId tenantId, DataSourceId dataSourceId,
                                                   DefinitionStatus status) {
        return query(" WHERE tenant_id = :tenantId AND data_source_id = :dataSourceId AND status = :status",
                " ORDER BY name, id", scope(tenantId, dataSourceId).addValue("status", status.name()));
    }

    @Override
    public List<SemanticRelationship> findBySignature(TenantId tenantId, DataSourceId dataSourceId, String signature,
                                                      Collection<DefinitionStatus> statuses) {
        if (statuses.isEmpty()) {
            return List.of();
        }
        return query(" WHERE tenant_id = :tenantId AND data_source_id = :dataSourceId AND signature = :signature "
                        + "AND status IN (:statuses)", " ORDER BY created_at, id",
                scope(tenantId, dataSourceId).addValue("signature", signature)
                        .addValue("statuses", statuses.stream().map(Enum::name).toList()));
    }

    @Override
    public List<String> findSignatures(TenantId tenantId, DataSourceId dataSourceId,
                                       Collection<DefinitionStatus> statuses) {
        if (statuses.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT DISTINCT signature FROM semantic_relationship WHERE tenant_id = :tenantId "
                        + "AND data_source_id = :dataSourceId AND status IN (:statuses)")
                .paramSource(scope(tenantId, dataSourceId)
                        .addValue("statuses", statuses.stream().map(Enum::name).toList()))
                .query(String.class)
                .list();
    }

    @Override
    public Page<SemanticRelationship> findPage(TenantId tenantId, DataSourceId dataSourceId, Filter filter,
                                               PageQuery pageQuery) {
        StringBuilder where = new StringBuilder(" WHERE tenant_id = :tenantId AND data_source_id = :dataSourceId");
        MapSqlParameterSource params = scope(tenantId, dataSourceId);
        if (filter.status() != null) {
            where.append(" AND status = :status");
            params.addValue("status", filter.status().name());
        }
        if (filter.origin() != null) {
            where.append(" AND origin = :origin");
            params.addValue("origin", filter.origin().name());
        }
        if (filter.objectId() != null) {
            where.append(" AND (source_object_id = :objectId OR target_object_id = :objectId)");
            params.addValue("objectId", filter.objectId());
        }
        long total = jdbc.sql("SELECT count(*) FROM semantic_relationship" + where).paramSource(params)
                .query(Long.class).single();
        params.addValue("limit", pageQuery.size()).addValue("offset", pageQuery.offset());
        List<SemanticRelationship> items = query(where.toString(), " ORDER BY name, id LIMIT :limit OFFSET :offset",
                params);
        return new Page<>(items, pageQuery.page(), pageQuery.size(), total);
    }

    // --- helpers ---

    private List<SemanticRelationship> query(String where, String suffix, MapSqlParameterSource params) {
        List<Row> rows = jdbc.sql("SELECT " + COLUMNS + " FROM semantic_relationship" + where + suffix)
                .paramSource(params)
                .query((rs, rowNum) -> row(rs))
                .list();
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<ColumnPair>> pairs = new HashMap<>();
        jdbc.sql("SELECT relationship_id, source_column_id, target_column_id FROM semantic_relationship_pair "
                        + "WHERE tenant_id = :tenantId AND data_source_id = :dataSourceId "
                        + "AND relationship_id IN (:ids) ORDER BY relationship_id, ordinal")
                .param("tenantId", rows.getFirst().tenantId().value())
                .param("dataSourceId", rows.getFirst().dataSourceId().value())
                .param("ids", rows.stream().map(Row::id).toList())
                .query(rs -> {
                    pairs.computeIfAbsent(rs.getObject("relationship_id", UUID.class), k -> new ArrayList<>())
                            .add(new ColumnPair(rs.getObject("source_column_id", UUID.class),
                                    rs.getObject("target_column_id", UUID.class)));
                });
        return rows.stream().map(row -> withPairs(row, pairs.getOrDefault(row.id(), List.of()))).toList();
    }

    private static SemanticRelationship withPairs(Row r, List<ColumnPair> pairs) {
        return new SemanticRelationship(r.id(), r.tenantId(), r.dataSourceId(), r.name(), r.description(),
                r.origin(), r.sourceObjectId(), r.targetObjectId(), pairs, r.foreignKeyId(), r.cardinality(),
                r.joinTypes(), r.replacesId(), r.status(), r.createdBy(), r.createdAt(), r.updatedBy(),
                r.updatedAt(), r.approvedBy(), r.approvedAt(), r.inactiveReason(), r.version());
    }

    private static Row row(ResultSet rs) throws SQLException {
        Set<JoinType> joins = EnumSet.noneOf(JoinType.class);
        if (rs.getBoolean("allow_inner_join")) {
            joins.add(JoinType.INNER);
        }
        if (rs.getBoolean("allow_left_join")) {
            joins.add(JoinType.LEFT);
        }
        return new Row(rs.getObject("id", UUID.class),
                new TenantId(rs.getObject("tenant_id", UUID.class)),
                new DataSourceId(rs.getObject("data_source_id", UUID.class)),
                rs.getString("name"),
                rs.getString("description"),
                RelationshipOrigin.valueOf(rs.getString("origin")),
                rs.getObject("source_object_id", UUID.class),
                rs.getObject("target_object_id", UUID.class),
                rs.getObject("foreign_key_object_id", UUID.class),
                DeclaredCardinality.valueOf(rs.getString("declared_cardinality")),
                joins,
                rs.getObject("replaces_id", UUID.class),
                DefinitionStatus.valueOf(rs.getString("status")),
                new PrincipalRef(rs.getString("created_by"), PrincipalType.valueOf(rs.getString("created_by_type"))),
                instant(rs, "created_at"),
                new PrincipalRef(rs.getString("updated_by"), PrincipalType.valueOf(rs.getString("updated_by_type"))),
                instant(rs, "updated_at"),
                rs.getString("approved_by"),
                instant(rs, "approved_at"),
                rs.getString("inactive_reason"),
                rs.getLong("row_version"));
    }

    private static MapSqlParameterSource scope(TenantId tenantId, DataSourceId dataSourceId) {
        return new MapSqlParameterSource()
                .addValue("tenantId", tenantId.value())
                .addValue("dataSourceId", dataSourceId.value());
    }

    private static MapSqlParameterSource parameters(SemanticRelationship r) {
        return scope(r.tenantId(), r.dataSourceId())
                .addValue("id", r.id())
                .addValue("name", r.name())
                .addValue("description", r.description())
                .addValue("origin", r.origin().name())
                .addValue("sourceId", r.sourceObjectId())
                .addValue("targetId", r.targetObjectId())
                .addValue("foreignKeyId", r.foreignKeyId())
                .addValue("pairCount", r.pairs().size())
                .addValue("signature", r.signature())
                .addValue("cardinality", r.declaredCardinality().name())
                .addValue("allowInner", r.allowedJoinTypes().contains(JoinType.INNER))
                .addValue("allowLeft", r.allowedJoinTypes().contains(JoinType.LEFT))
                .addValue("replacesId", r.replacesId())
                .addValue("status", r.status().name())
                .addValue("createdBy", r.createdBy().id())
                .addValue("createdByType", r.createdBy().type().name())
                .addValue("createdAt", toDb(r.createdAt()))
                .addValue("updatedBy", r.updatedBy().id())
                .addValue("updatedByType", r.updatedBy().type().name())
                .addValue("updatedAt", toDb(r.updatedAt()))
                .addValue("approvedBy", r.approvedBy())
                .addValue("approvedAt", toDb(r.approvedAt()))
                .addValue("inactiveReason", r.inactiveReason());
    }
}
