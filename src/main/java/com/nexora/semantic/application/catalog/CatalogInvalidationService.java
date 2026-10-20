package com.nexora.semantic.application.catalog;

import com.nexora.semantic.application.port.AuditLog;
import com.nexora.semantic.application.port.PhysicalObjectRepository;
import com.nexora.semantic.application.port.SemanticDefinitionRepository;
import com.nexora.semantic.domain.audit.AuditAction;
import com.nexora.semantic.domain.audit.AuditEvent;
import com.nexora.semantic.domain.audit.AuditTargetType;
import com.nexora.semantic.domain.catalog.DefinitionIssue;
import com.nexora.semantic.domain.catalog.DefinitionKind;
import com.nexora.semantic.domain.catalog.DefinitionStatus;
import com.nexora.semantic.domain.catalog.DefinitionValidator;
import com.nexora.semantic.domain.catalog.SemanticDefinition;
import com.nexora.semantic.domain.shared.CallerContext;
import com.nexora.semantic.domain.source.DataSource;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Keeps the catalog consistent with the physical metadata: after an import, every APPROVED definition that no
 * longer validates (a referenced object is MISSING, or a column changed to an incompatible type) becomes INACTIVE,
 * and so do approved metrics and dimensions of a concept that became INACTIVE. They stay INACTIVE even if the
 * metadata is later restored, until a person revises and approves them again ("until reviewed").
 * Runs inside the import transaction, which already holds the data source lock.
 */
@Transactional
public class CatalogInvalidationService {

    static final String REASON_PREFIX = SemanticDefinition.IMPORT_INVALIDATION_PREFIX;

    private final SemanticDefinitionRepository definitions;
    private final AuditLog auditLog;
    private final CatalogContextLoader loader;
    private final DefinitionValidator validator = new DefinitionValidator();

    public CatalogInvalidationService(SemanticDefinitionRepository definitions, PhysicalObjectRepository objects,
                                      AuditLog auditLog) {
        this.definitions = definitions;
        this.auditLog = auditLog;
        this.loader = new CatalogContextLoader(objects, definitions);
    }

    /** @return number of definitions invalidated */
    public int invalidateAfterImport(CallerContext caller, DataSource dataSource, UUID snapshotId, Instant now) {
        List<SemanticDefinition> approved = definitions.findByStatus(caller.tenantId(), dataSource.id(),
                DefinitionStatus.APPROVED);
        if (approved.isEmpty()) {
            return 0;
        }
        DefinitionValidator.Context loaded = loader.load(caller.tenantId(), dataSource.id(), dataSource.engine(),
                approved.stream().map(SemanticDefinition::spec).toList(), approved);
        Map<UUID, SemanticDefinition> current = new HashMap<>(loaded.definitions());
        int invalidated = 0;
        // Concepts first, so that metrics and dimensions see the new state of their concept.
        for (DefinitionKind kind : List.of(DefinitionKind.CONCEPT, DefinitionKind.METRIC, DefinitionKind.DIMENSION)) {
            for (SemanticDefinition definition : approved) {
                if (definition.kind() != kind) {
                    continue;
                }
                DefinitionValidator.Context context = new DefinitionValidator.Context(loaded.engine(),
                        loaded.objects(), current);
                List<DefinitionIssue> issues = validator.usageIssues(definition.spec(), context);
                if (issues.isEmpty()) {
                    continue;
                }
                List<String> codes = new ArrayList<>(issues.stream().map(i -> i.code().name()).distinct().toList());
                SemanticDefinition inactive = definitions.update(definition.invalidate(
                        REASON_PREFIX + ": " + String.join(", ", codes), caller.principal(), now),
                        definition.version());
                current.put(inactive.id(), inactive);
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("dataSourceId", dataSource.id().value().toString());
                details.put("snapshotId", snapshotId.toString());
                details.put("issues", codes);
                auditLog.append(AuditEvent.of(caller.tenantId(), now, caller.principal(), caller.correlationId(),
                        AuditAction.DEFINITION_INVALIDATED, AuditTargetType.SEMANTIC_DEFINITION, inactive.id(), details));
                invalidated++;
            }
        }
        return invalidated;
    }
}
