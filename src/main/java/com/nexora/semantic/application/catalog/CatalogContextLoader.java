package com.nexora.semantic.application.catalog;

import com.nexora.semantic.application.port.PhysicalObjectRepository;
import com.nexora.semantic.application.port.SemanticDefinitionRepository;
import com.nexora.semantic.domain.catalog.DefinitionSpec;
import com.nexora.semantic.domain.catalog.DefinitionValidator;
import com.nexora.semantic.domain.catalog.SemanticDefinition;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.source.DataSourceId;
import com.nexora.semantic.domain.source.EngineCode;
import com.nexora.semantic.domain.source.PhysicalObject;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Loads, in a few queries, everything {@link DefinitionValidator} needs for a batch of specs: the referenced physical
 * objects and concepts, and the relations of those concepts. Lookups are scoped to the tenant and data source, so a
 * reference to another tenant's or data source's object is simply "not found".
 * Shared by the catalog services and the semantic resolution (S4.1).
 */
public final class CatalogContextLoader {

    private final PhysicalObjectRepository objects;
    private final SemanticDefinitionRepository definitions;

    public CatalogContextLoader(PhysicalObjectRepository objects, SemanticDefinitionRepository definitions) {
        this.objects = objects;
        this.definitions = definitions;
    }

    public DefinitionValidator.Context load(TenantId tenantId, DataSourceId dataSourceId, EngineCode engine,
                                            Collection<DefinitionSpec> specs, Collection<SemanticDefinition> known) {
        Map<UUID, SemanticDefinition> definitionsById = new HashMap<>();
        known.forEach(d -> definitionsById.put(d.id(), d));
        Set<UUID> conceptIds = new HashSet<>();
        specs.forEach(spec -> conceptId(spec).ifPresent(conceptIds::add));
        conceptIds.removeAll(definitionsById.keySet());
        if (!conceptIds.isEmpty()) {
            definitions.findByIds(tenantId, dataSourceId, conceptIds).forEach(d -> definitionsById.put(d.id(), d));
        }

        Set<UUID> objectIds = new HashSet<>();
        specs.forEach(spec -> objectIds.addAll(physicalIds(spec)));
        definitionsById.values().forEach(d -> objectIds.addAll(physicalIds(d.spec())));
        Map<UUID, PhysicalObject> objectsById = new HashMap<>();
        if (!objectIds.isEmpty()) {
            objects.findByIds(tenantId, dataSourceId, objectIds).forEach(o -> objectsById.put(o.id(), o));
        }
        return new DefinitionValidator.Context(engine, objectsById, definitionsById);
    }

    private static Optional<UUID> conceptId(DefinitionSpec spec) {
        return switch (spec) {
            case DefinitionSpec.ConceptSpec ignored -> Optional.empty();
            case DefinitionSpec.MetricSpec metric -> Optional.of(metric.conceptId());
            case DefinitionSpec.DimensionSpec dimension -> Optional.of(dimension.conceptId());
        };
    }

    private static List<UUID> physicalIds(DefinitionSpec spec) {
        return switch (spec) {
            case DefinitionSpec.ConceptSpec concept -> List.of(concept.relationId());
            case DefinitionSpec.MetricSpec metric -> metric.columnId() == null ? List.of() : List.of(metric.columnId());
            case DefinitionSpec.DimensionSpec dimension -> List.of(dimension.columnId());
        };
    }
}
