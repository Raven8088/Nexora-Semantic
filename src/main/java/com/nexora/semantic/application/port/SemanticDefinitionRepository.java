package com.nexora.semantic.application.port;

import com.nexora.semantic.domain.catalog.DefinitionKind;
import com.nexora.semantic.domain.catalog.DefinitionStatus;
import com.nexora.semantic.domain.catalog.SemanticDefinition;
import com.nexora.semantic.domain.shared.Page;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DataSourceId;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Semantic catalog of a data source; every operation is scoped to tenant and data source. No deletes. */
public interface SemanticDefinitionRepository {

    /** Optional filters; null means "any". */
    record Filter(DefinitionKind kind, DefinitionStatus status) {
        public static final Filter ALL = new Filter(null, null);
    }

    /** @throws com.nexora.semantic.domain.shared.DuplicateResourceException if kind and key are already used */
    void insert(SemanticDefinition definition);

    /**
     * @return the definition with its new version
     * @throws com.nexora.semantic.domain.shared.StaleResourceException if the stored version differs
     */
    SemanticDefinition update(SemanticDefinition definition, long expectedVersion);

    Optional<SemanticDefinition> findById(TenantId tenantId, DataSourceId dataSourceId, UUID id);

    List<SemanticDefinition> findByIds(TenantId tenantId, DataSourceId dataSourceId, Collection<UUID> ids);

    List<SemanticDefinition> findByStatus(TenantId tenantId, DataSourceId dataSourceId, DefinitionStatus status);

    /** Ordered by kind and key. */
    Page<SemanticDefinition> findPage(TenantId tenantId, DataSourceId dataSourceId, Filter filter, PageQuery pageQuery);
}
