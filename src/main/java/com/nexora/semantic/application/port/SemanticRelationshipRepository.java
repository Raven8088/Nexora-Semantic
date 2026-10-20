package com.nexora.semantic.application.port;

import com.nexora.semantic.domain.catalog.DefinitionStatus;
import com.nexora.semantic.domain.relationship.RelationshipOrigin;
import com.nexora.semantic.domain.relationship.SemanticRelationship;
import com.nexora.semantic.domain.shared.Page;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DataSourceId;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Semantic relationships of a data source (S3.1); every operation is scoped to tenant and data source. Pairs are
 * written once with the relationship and never updated; rows are never deleted.
 */
public interface SemanticRelationshipRepository {

    /** Optional filters; null means "any". {@code objectId} matches the source or the target. */
    record Filter(DefinitionStatus status, RelationshipOrigin origin, UUID objectId) {
        public static final Filter ALL = new Filter(null, null, null);
    }

    /** Inserts the relationship and its pairs. */
    void insert(SemanticRelationship relationship);

    /**
     * Persists the mutable state (descriptive fields, cardinality, JOIN types, lifecycle) with optimistic locking.
     *
     * @return the stored relationship with its new version
     * @throws com.nexora.semantic.domain.shared.StaleResourceException if the version does not match
     * @throws com.nexora.semantic.domain.relationship.EquivalentRelationshipException if it would leave two
     *         equivalent APPROVED relationships
     */
    SemanticRelationship update(SemanticRelationship relationship, long expectedVersion);

    Optional<SemanticRelationship> findById(TenantId tenantId, DataSourceId dataSourceId, UUID id);

    List<SemanticRelationship> findByStatus(TenantId tenantId, DataSourceId dataSourceId, DefinitionStatus status);

    /** Relationships with this signature whose status is one of {@code statuses}. */
    List<SemanticRelationship> findBySignature(TenantId tenantId, DataSourceId dataSourceId, String signature,
                                               Collection<DefinitionStatus> statuses);

    /** Signatures of relationships whose status is one of {@code statuses}. */
    List<String> findSignatures(TenantId tenantId, DataSourceId dataSourceId, Collection<DefinitionStatus> statuses);

    /** Ordered by name, then id. */
    Page<SemanticRelationship> findPage(TenantId tenantId, DataSourceId dataSourceId, Filter filter,
                                        PageQuery pageQuery);
}
