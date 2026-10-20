package com.nexora.semantic.application.catalog;

import com.nexora.semantic.application.port.AuditLog;
import com.nexora.semantic.application.port.DataSourceRepository;
import com.nexora.semantic.application.port.PhysicalObjectRepository;
import com.nexora.semantic.application.port.SemanticDefinitionRepository;
import com.nexora.semantic.domain.audit.AuditAction;
import com.nexora.semantic.domain.audit.AuditEvent;
import com.nexora.semantic.domain.audit.AuditTargetType;
import com.nexora.semantic.domain.catalog.DefinitionIssue;
import com.nexora.semantic.domain.catalog.DefinitionKey;
import com.nexora.semantic.domain.catalog.DefinitionSpec;
import com.nexora.semantic.domain.catalog.DefinitionStatus;
import com.nexora.semantic.domain.catalog.DefinitionValidator;
import com.nexora.semantic.domain.catalog.InvalidDefinitionException;
import com.nexora.semantic.domain.catalog.SemanticDefinition;
import com.nexora.semantic.domain.shared.CallerContext;
import com.nexora.semantic.domain.shared.Page;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.shared.ResourceNotFoundException;
import com.nexora.semantic.domain.shared.StaleResourceException;
import com.nexora.semantic.domain.source.DataSource;
import com.nexora.semantic.domain.source.DataSourceArchivedException;
import com.nexora.semantic.domain.source.DataSourceId;
import com.nexora.semantic.domain.source.PhysicalObject;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Semantic catalog of a data source (S3 Basic): create, revise, approve, deactivate and read definitions.
 * Writes lock the data source row, so they never interleave with a metadata import of the same source.
 * Every change is audited in the same transaction.
 */
@Transactional
public class SemanticCatalogService {

    private static final String RESOURCE = "Semantic definition";

    /**
     * A definition with its current evaluation against the physical metadata.
     *
     * @param issues     why it cannot be approved or used now (empty when valid)
     * @param usable     APPROVED and without issues: may be used by S4 resolution
     * @param references referenced physical objects, by id (for natural keys in responses)
     */
    public record EvaluatedDefinition(SemanticDefinition definition, List<DefinitionIssue> issues, boolean usable,
                                      Map<UUID, PhysicalObject> references) {
    }

    private final DataSourceRepository dataSources;
    private final SemanticDefinitionRepository definitions;
    private final AuditLog auditLog;
    private final Clock clock;
    private final CatalogContextLoader loader;
    private final DefinitionValidator validator = new DefinitionValidator();

    public SemanticCatalogService(DataSourceRepository dataSources, SemanticDefinitionRepository definitions,
                                  PhysicalObjectRepository objects, AuditLog auditLog, Clock clock) {
        this.dataSources = dataSources;
        this.definitions = definitions;
        this.auditLog = auditLog;
        this.clock = clock;
        this.loader = new CatalogContextLoader(objects, definitions);
    }

    public EvaluatedDefinition create(CallerContext caller, DataSourceId dataSourceId, String key, String name,
                                      String description, DefinitionSpec spec) {
        DataSource dataSource = lockActive(caller, dataSourceId);
        DefinitionKey definitionKey = new DefinitionKey(key);
        DefinitionSpec normalized = validated(caller, dataSource, spec);
        SemanticDefinition created = SemanticDefinition.create(UUID.randomUUID(), caller.tenantId(), dataSourceId,
                definitionKey, name, description, normalized, caller.principal(), now());
        definitions.insert(created);
        audit(caller, AuditAction.DEFINITION_CREATED, created,
                Map.of("kind", created.kind().name(), "key", created.key().value(), "origin", created.origin().name()));
        return evaluate(dataSource, created);
    }

    /** Replaces name, description and spec; the result is a DRAFT that must be approved again. */
    public EvaluatedDefinition update(CallerContext caller, DataSourceId dataSourceId, UUID id, long expectedVersion,
                                      String name, String description, DefinitionSpec spec) {
        DataSource dataSource = lockActive(caller, dataSourceId);
        SemanticDefinition current = current(caller, dataSourceId, id, expectedVersion);
        DefinitionSpec normalized = validated(caller, dataSource, spec);
        SemanticDefinition revised = definitions.update(
                current.revise(name, description, normalized, caller.principal(), now()), expectedVersion);
        audit(caller, AuditAction.DEFINITION_UPDATED, revised,
                Map.of("previousStatus", current.status().name(), "status", revised.status().name()));
        return evaluate(dataSource, revised);
    }

    /** Requires a valid definition (and an APPROVED, valid concept for metrics and dimensions). */
    public EvaluatedDefinition approve(CallerContext caller, DataSourceId dataSourceId, UUID id, long expectedVersion) {
        DataSource dataSource = lockActive(caller, dataSourceId);
        SemanticDefinition current = current(caller, dataSourceId, id, expectedVersion);
        List<DefinitionIssue> issues = validator.usageIssues(current.spec(), context(caller, dataSource, current));
        if (!issues.isEmpty()) {
            throw new InvalidDefinitionException(issues);
        }
        SemanticDefinition approved = definitions.update(current.approve(caller.principal(), now()), expectedVersion);
        audit(caller, AuditAction.DEFINITION_APPROVED, approved,
                Map.of("kind", approved.kind().name(), "key", approved.key().value()));
        return evaluate(dataSource, approved);
    }

    public EvaluatedDefinition deactivate(CallerContext caller, DataSourceId dataSourceId, UUID id,
                                          long expectedVersion, String reason) {
        DataSource dataSource = lockDataSource(caller, dataSourceId);
        SemanticDefinition current = current(caller, dataSourceId, id, expectedVersion);
        SemanticDefinition deactivated = definitions.update(
                current.deactivate(caller.principal(), reason, now()), expectedVersion);
        audit(caller, AuditAction.DEFINITION_DEACTIVATED, deactivated,
                Map.of("previousStatus", current.status().name(), "reason", deactivated.inactiveReason()));
        return evaluate(dataSource, deactivated);
    }

    @Transactional(readOnly = true)
    public EvaluatedDefinition get(CallerContext caller, DataSourceId dataSourceId, UUID id) {
        DataSource dataSource = dataSource(caller, dataSourceId);
        SemanticDefinition definition = definitions.findById(caller.tenantId(), dataSourceId, id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE));
        return evaluate(dataSource, definition);
    }

    @Transactional(readOnly = true)
    public Page<EvaluatedDefinition> list(CallerContext caller, DataSourceId dataSourceId,
                                          SemanticDefinitionRepository.Filter filter, PageQuery pageQuery) {
        DataSource dataSource = dataSource(caller, dataSourceId);
        Page<SemanticDefinition> page = definitions.findPage(caller.tenantId(), dataSourceId, filter, pageQuery);
        DefinitionValidator.Context context = loader.load(caller.tenantId(), dataSourceId, dataSource.engine(),
                page.items().stream().map(SemanticDefinition::spec).toList(), page.items());
        return page.map(definition -> evaluate(definition, context));
    }

    // --- helpers ---

    private DefinitionSpec validated(CallerContext caller, DataSource dataSource, DefinitionSpec spec) {
        DefinitionValidator.Context context = loader.load(caller.tenantId(), dataSource.id(), dataSource.engine(),
                List.of(spec), List.of());
        DefinitionSpec normalized = validator.normalize(spec, context);
        List<DefinitionIssue> issues = validator.issues(normalized, context);
        if (!issues.isEmpty()) {
            throw new InvalidDefinitionException(issues);
        }
        return normalized;
    }

    private SemanticDefinition current(CallerContext caller, DataSourceId dataSourceId, UUID id, long expectedVersion) {
        SemanticDefinition current = definitions.findById(caller.tenantId(), dataSourceId, id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE));
        if (current.version() != expectedVersion) {
            throw new StaleResourceException(RESOURCE);
        }
        return current;
    }

    private DefinitionValidator.Context context(CallerContext caller, DataSource dataSource,
                                                SemanticDefinition definition) {
        return loader.load(caller.tenantId(), dataSource.id(), dataSource.engine(), List.of(definition.spec()),
                List.of(definition));
    }

    private EvaluatedDefinition evaluate(DataSource dataSource, SemanticDefinition definition) {
        return evaluate(definition, loader.load(definition.tenantId(), dataSource.id(), dataSource.engine(),
                List.of(definition.spec()), List.of(definition)));
    }

    private EvaluatedDefinition evaluate(SemanticDefinition definition, DefinitionValidator.Context context) {
        List<DefinitionIssue> issues = definition.status() == DefinitionStatus.INACTIVE
                ? validator.issues(definition.spec(), context)
                : validator.usageIssues(definition.spec(), context);
        boolean usable = definition.status() == DefinitionStatus.APPROVED && issues.isEmpty();
        Map<UUID, PhysicalObject> references = new LinkedHashMap<>();
        context.objects().forEach(references::put);
        return new EvaluatedDefinition(definition, new ArrayList<>(issues), usable, references);
    }

    private DataSource lockActive(CallerContext caller, DataSourceId dataSourceId) {
        DataSource dataSource = lockDataSource(caller, dataSourceId);
        if (!dataSource.isActive()) {
            throw new DataSourceArchivedException();
        }
        return dataSource;
    }

    private DataSource lockDataSource(CallerContext caller, DataSourceId dataSourceId) {
        return dataSources.lockById(caller.tenantId(), dataSourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Data source"));
    }

    private DataSource dataSource(CallerContext caller, DataSourceId dataSourceId) {
        return dataSources.findById(caller.tenantId(), dataSourceId)
                .orElseThrow(() -> new ResourceNotFoundException("Data source"));
    }

    private void audit(CallerContext caller, AuditAction action, SemanticDefinition definition,
                       Map<String, Object> details) {
        Map<String, Object> all = new LinkedHashMap<>(details);
        all.put("dataSourceId", definition.dataSourceId().value().toString());
        auditLog.append(AuditEvent.of(caller.tenantId(), definition.updatedAt(), caller.principal(),
                caller.correlationId(), action, AuditTargetType.SEMANTIC_DEFINITION, definition.id(), all));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
