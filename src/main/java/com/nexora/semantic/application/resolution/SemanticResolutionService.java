package com.nexora.semantic.application.resolution;

import com.nexora.semantic.application.catalog.CatalogContextLoader;
import com.nexora.semantic.application.catalog.SemanticRelationshipService;
import com.nexora.semantic.application.port.DataSourceRepository;
import com.nexora.semantic.application.port.MetadataSnapshotRepository;
import com.nexora.semantic.application.port.PhysicalObjectRepository;
import com.nexora.semantic.application.port.SemanticDefinitionRepository;
import com.nexora.semantic.domain.catalog.DefinitionValidator;
import com.nexora.semantic.domain.catalog.SemanticDefinition;
import com.nexora.semantic.domain.relationship.RelationshipContract;
import com.nexora.semantic.domain.resolution.ResolutionOutcome;
import com.nexora.semantic.domain.resolution.ResolutionRequest;
import com.nexora.semantic.domain.resolution.SemanticResolver;
import com.nexora.semantic.domain.shared.CallerContext;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.shared.ResourceNotFoundException;
import com.nexora.semantic.domain.source.DataSource;
import com.nexora.semantic.domain.source.DataSourceArchivedException;
import com.nexora.semantic.domain.source.DataSourceId;
import com.nexora.semantic.domain.source.MetadataSnapshot;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * S4.1 use case: resolves a structured request against the semantic catalog of one data source of the caller's
 * tenant. Read-only: it approves, modifies, persists and audits nothing, and generates no SQL.
 * <p>
 * Consistency: everything is read in one read-only {@code REPEATABLE READ} transaction. In PostgreSQL that is a
 * single snapshot, so a concurrent revision, approval, invalidation or import is either entirely visible or not at
 * all; a resolution never mixes definitions, relationships and physical metadata from different moments. The
 * revisions observed are returned (versions, object fingerprints, latest snapshot, overall fingerprint) so that S4.2
 * can detect later changes.
 */
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class SemanticResolutionService {

    private final DataSourceRepository dataSources;
    private final MetadataSnapshotRepository snapshots;
    private final SemanticDefinitionRepository definitions;
    private final SemanticRelationshipService relationships;
    private final CatalogContextLoader loader;
    private final SemanticResolver resolver = new SemanticResolver();

    public SemanticResolutionService(DataSourceRepository dataSources, MetadataSnapshotRepository snapshots,
                                     SemanticDefinitionRepository definitions, PhysicalObjectRepository objects,
                                     SemanticRelationshipService relationships) {
        this.dataSources = dataSources;
        this.snapshots = snapshots;
        this.definitions = definitions;
        this.relationships = relationships;
        this.loader = new CatalogContextLoader(objects, definitions);
    }

    /**
     * @throws ResourceNotFoundException   if the data source does not exist in the caller's tenant (no disclosure)
     * @throws DataSourceArchivedException if the data source is archived
     */
    public ResolutionOutcome resolve(CallerContext caller, DataSourceId dataSourceId, ResolutionRequest request) {
        DataSource dataSource = dataSources.findById(caller.tenantId(), dataSourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Data source"));
        if (!dataSource.isActive()) {
            throw new DataSourceArchivedException();
        }
        UUID latestSnapshot = snapshots.findPage(caller.tenantId(), dataSourceId, new PageQuery(0, 1)).items()
                .stream().findFirst().map(MetadataSnapshot::id).orElse(null);

        Set<UUID> ids = new LinkedHashSet<>(request.metricIds());
        ids.addAll(request.dimensionIds());
        request.filters().forEach(f -> ids.add(f.definitionId()));
        List<SemanticDefinition> requested = ids.isEmpty() ? List.of()
                : definitions.findByIds(caller.tenantId(), dataSourceId, ids);
        DefinitionValidator.Context catalog = loader.load(caller.tenantId(), dataSourceId, dataSource.engine(),
                requested.stream().map(SemanticDefinition::spec).toList(), requested);
        Map<UUID, RelationshipContract> contracts = request.preferredRelationshipIds().isEmpty() ? Map.of()
                : relationships.contracts(caller, dataSourceId, request.preferredRelationshipIds());

        return resolver.resolve(request, new SemanticResolver.Context(caller.tenantId(), dataSourceId, latestSnapshot,
                catalog, contracts));
    }
}
