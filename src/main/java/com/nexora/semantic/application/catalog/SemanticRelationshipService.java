package com.nexora.semantic.application.catalog;

import com.nexora.semantic.application.port.AuditLog;
import com.nexora.semantic.application.port.DataSourceRepository;
import com.nexora.semantic.application.port.PhysicalObjectRepository;
import com.nexora.semantic.application.port.SemanticRelationshipRepository;
import com.nexora.semantic.domain.audit.AuditAction;
import com.nexora.semantic.domain.audit.AuditEvent;
import com.nexora.semantic.domain.audit.AuditTargetType;
import com.nexora.semantic.domain.catalog.DefinitionIssue;
import com.nexora.semantic.domain.catalog.DefinitionStatus;
import com.nexora.semantic.domain.relationship.ColumnPair;
import com.nexora.semantic.domain.relationship.DeclaredCardinality;
import com.nexora.semantic.domain.relationship.EquivalentRelationshipException;
import com.nexora.semantic.domain.relationship.ForeignKeyResolver;
import com.nexora.semantic.domain.relationship.InvalidRelationshipException;
import com.nexora.semantic.domain.relationship.JoinType;
import com.nexora.semantic.domain.relationship.RelationshipCandidateFinder;
import com.nexora.semantic.domain.relationship.RelationshipContract;
import com.nexora.semantic.domain.relationship.RelationshipOrigin;
import com.nexora.semantic.domain.relationship.RelationshipValidator;
import com.nexora.semantic.domain.relationship.SemanticRelationship;
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
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Catalog of semantic relationships of a data source (S3.1): create (manually or from a foreign key), revise
 * descriptive fields, approve (with controlled replacement), deactivate, read, suggest candidates and expose the
 * typed contract for S4. Writes lock the data source row, so they never interleave with a metadata import of the
 * same source; every change is audited in the same transaction.
 */
@Transactional
public class SemanticRelationshipService {

    private static final String RESOURCE = "Semantic relationship";
    private static final List<DefinitionStatus> IN_USE = List.of(DefinitionStatus.DRAFT, DefinitionStatus.APPROVED);

    /**
     * A relationship with its current physical evaluation.
     *
     * @param usable     APPROVED and physically valid: may be used by S4
     * @param references referenced physical objects by id (for natural keys in responses)
     */
    public record EvaluatedRelationship(SemanticRelationship relationship, RelationshipValidator.Evaluation evaluation,
                                        boolean usable, Map<UUID, PhysicalObject> references) {

        public RelationshipContract contract() {
            return RelationshipContract.of(relationship, evaluation);
        }
    }

    /** Content of a new SEMANTIC relationship. */
    public record SemanticDraft(String name, String description, UUID sourceObjectId, UUID targetObjectId,
                                List<ColumnPair> pairs, DeclaredCardinality cardinality, Set<JoinType> joinTypes,
                                UUID replacesId) {
    }

    /** Content of a new DECLARED_FK relationship; a null name defaults to the foreign key name. */
    public record ForeignKeyDraft(UUID foreignKeyId, String name, String description, DeclaredCardinality cardinality,
                                  Set<JoinType> joinTypes, UUID replacesId) {
    }

    private final DataSourceRepository dataSources;
    private final SemanticRelationshipRepository relationships;
    private final PhysicalObjectRepository objects;
    private final AuditLog auditLog;
    private final Clock clock;
    private final RelationshipValidator validator = new RelationshipValidator();
    private final RelationshipCandidateFinder candidateFinder = new RelationshipCandidateFinder();

    public SemanticRelationshipService(DataSourceRepository dataSources, SemanticRelationshipRepository relationships,
                                       PhysicalObjectRepository objects, AuditLog auditLog, Clock clock) {
        this.dataSources = dataSources;
        this.relationships = relationships;
        this.objects = objects;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    /** Creates a SEMANTIC relationship as DRAFT after physical validation. */
    public EvaluatedRelationship create(CallerContext caller, DataSourceId dataSourceId, SemanticDraft draft) {
        DataSource dataSource = lockActive(caller, dataSourceId);
        SemanticRelationship created = SemanticRelationship.create(UUID.randomUUID(), caller.tenantId(), dataSourceId,
                draft.name(), draft.description(), RelationshipOrigin.SEMANTIC, draft.sourceObjectId(),
                draft.targetObjectId(), draft.pairs(), null, draft.cardinality(), draft.joinTypes(),
                draft.replacesId(), caller.principal(), now());
        return insert(caller, dataSource, created);
    }

    /**
     * Creates a DECLARED_FK relationship as DRAFT from a foreign key observed in the metadata, resolved by exact
     * natural key. Nothing is approved automatically; an unresolvable foreign key is rejected with its reason.
     */
    public EvaluatedRelationship createFromForeignKey(CallerContext caller, DataSourceId dataSourceId,
                                                      ForeignKeyDraft draft) {
        DataSource dataSource = lockActive(caller, dataSourceId);
        PhysicalObject fk = objects.findById(caller.tenantId(), dataSourceId, draft.foreignKeyId()).orElse(null);
        List<PhysicalObject> known = new ArrayList<>();
        if (fk != null) {
            known.add(fk);
            PhysicalObject owner = fk.parentId() == null ? null
                    : objects.findById(caller.tenantId(), dataSourceId, fk.parentId()).orElse(null);
            if (owner != null) {
                known.add(owner);
            }
            known.addAll(objects.findByNaturalKeys(caller.tenantId(), dataSourceId,
                    ForeignKeyResolver.requiredNaturalKeys(fk, owner)));
        }
        ForeignKeyResolver.Resolution resolution = new ForeignKeyResolver(known).resolve(draft.foreignKeyId());
        if (!resolution.resolved()) {
            throw new InvalidRelationshipException(List.of(resolution.issue()));
        }
        String name = draft.name() != null && !draft.name().isBlank() ? draft.name() : defaultName(fk);
        SemanticRelationship created = SemanticRelationship.create(UUID.randomUUID(), caller.tenantId(), dataSourceId,
                name, draft.description(), RelationshipOrigin.DECLARED_FK, resolution.sourceObjectId(),
                resolution.targetObjectId(), resolution.pairs(), fk.id(), draft.cardinality(), draft.joinTypes(),
                draft.replacesId(), caller.principal(), now());
        return insert(caller, dataSource, created);
    }

    /**
     * Replaces name, description, declared cardinality and allowed JOIN types. Objects and pairs are immutable. The
     * result is a DRAFT that must be approved again (also when revising an INACTIVE relationship).
     */
    public EvaluatedRelationship update(CallerContext caller, DataSourceId dataSourceId, UUID id, long expectedVersion,
                                        String name, String description, DeclaredCardinality cardinality,
                                        Set<JoinType> joinTypes) {
        DataSource dataSource = lockActive(caller, dataSourceId);
        SemanticRelationship current = current(caller, dataSourceId, id, expectedVersion);
        SemanticRelationship revised = current.revise(name, description, cardinality, joinTypes, caller.principal(),
                now());
        if (current.status() == DefinitionStatus.INACTIVE) {
            rejectEquivalent(revised);
        }
        SemanticRelationship stored = relationships.update(revised, expectedVersion);
        audit(caller, AuditAction.RELATIONSHIP_UPDATED, stored,
                Map.of("previousStatus", current.status().name(), "status", stored.status().name()));
        return evaluate(dataSource, stored);
    }

    /**
     * Approves a DRAFT: approver rules (D3), physical validity, and no other equivalent APPROVED relationship. When
     * it replaces another relationship, that one becomes INACTIVE in the same transaction, before the approval, so
     * two equivalent relationships are never APPROVED at the same time.
     */
    public EvaluatedRelationship approve(CallerContext caller, DataSourceId dataSourceId, UUID id,
                                         long expectedVersion) {
        DataSource dataSource = lockActive(caller, dataSourceId);
        SemanticRelationship current = current(caller, dataSourceId, id, expectedVersion);
        Instant now = now();
        SemanticRelationship approved = current.approve(caller.principal(), now);
        RelationshipValidator.Evaluation evaluation = evaluation(dataSource, current);
        if (!evaluation.valid()) {
            throw new InvalidRelationshipException(evaluation.issues());
        }
        for (SemanticRelationship other : relationships.findBySignature(caller.tenantId(), dataSourceId,
                current.signature(), List.of(DefinitionStatus.APPROVED))) {
            if (!other.id().equals(current.replacesId())) {
                throw new EquivalentRelationshipException(other.id());
            }
        }
        if (current.replacesId() != null) {
            SemanticRelationship replaced = relationships.findById(caller.tenantId(), dataSourceId,
                    current.replacesId()).orElseThrow(() -> new ResourceNotFoundException(RESOURCE));
            if (replaced.status() != DefinitionStatus.INACTIVE) {
                SemanticRelationship inactive = relationships.update(replaced.deactivate(caller.principal(),
                        "Replaced by relationship " + current.id(), now), replaced.version());
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("previousStatus", replaced.status().name());
                details.put("replacedBy", current.id().toString());
                audit(caller, AuditAction.RELATIONSHIP_REPLACED, inactive, details);
            }
        }
        SemanticRelationship stored = relationships.update(approved, expectedVersion);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("origin", stored.origin().name());
        details.put("declaredCardinality", stored.declaredCardinality().name());
        details.put("cardinalityAssurance", evaluation.evidence().cardinalityAssurance().name());
        if (stored.replacesId() != null) {
            details.put("replaces", stored.replacesId().toString());
        }
        audit(caller, AuditAction.RELATIONSHIP_APPROVED, stored, details);
        return evaluate(dataSource, stored);
    }

    public EvaluatedRelationship deactivate(CallerContext caller, DataSourceId dataSourceId, UUID id,
                                            long expectedVersion, String reason) {
        DataSource dataSource = lockDataSource(caller, dataSourceId);
        SemanticRelationship current = current(caller, dataSourceId, id, expectedVersion);
        SemanticRelationship stored = relationships.update(current.deactivate(caller.principal(), reason, now()),
                expectedVersion);
        audit(caller, AuditAction.RELATIONSHIP_DEACTIVATED, stored,
                Map.of("previousStatus", current.status().name(), "reason", stored.inactiveReason()));
        return evaluate(dataSource, stored);
    }

    @Transactional(readOnly = true)
    public EvaluatedRelationship get(CallerContext caller, DataSourceId dataSourceId, UUID id) {
        DataSource dataSource = dataSource(caller, dataSourceId);
        SemanticRelationship relationship = relationships.findById(caller.tenantId(), dataSourceId, id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE));
        return evaluate(dataSource, relationship);
    }

    @Transactional(readOnly = true)
    public Page<EvaluatedRelationship> list(CallerContext caller, DataSourceId dataSourceId,
                                            SemanticRelationshipRepository.Filter filter, PageQuery pageQuery) {
        DataSource dataSource = dataSource(caller, dataSourceId);
        Page<SemanticRelationship> page = relationships.findPage(caller.tenantId(), dataSourceId, filter, pageQuery);
        Map<UUID, PhysicalObject> loaded = load(dataSource, page.items());
        return page.map(r -> evaluate(dataSource, r, loaded));
    }

    /**
     * Typed contract for S4: every APPROVED relationship of the data source with its evidence. S4 must still check
     * {@link RelationshipContract#usable()}: an APPROVED relationship can be physically invalid until the next import
     * invalidates it.
     */
    @Transactional(readOnly = true)
    public List<RelationshipContract> approvedContracts(CallerContext caller, DataSourceId dataSourceId) {
        return approved(caller, dataSourceId).stream().map(EvaluatedRelationship::contract).toList();
    }

    /** Every APPROVED relationship of the data source, evaluated, with its referenced objects (S4.2 planning). */
    @Transactional(readOnly = true)
    public List<EvaluatedRelationship> approved(CallerContext caller, DataSourceId dataSourceId) {
        DataSource dataSource = dataSource(caller, dataSourceId);
        List<SemanticRelationship> approved = relationships.findByStatus(caller.tenantId(), dataSourceId,
                DefinitionStatus.APPROVED);
        Map<UUID, PhysicalObject> loaded = load(dataSource, approved);
        return approved.stream().map(r -> evaluate(dataSource, r, loaded)).toList();
    }

    /**
     * Contracts of the given relationships that exist in the data source (any status), keyed by id; unknown ids,
     * and ids of other tenants or data sources, are simply absent. Used by the semantic resolution (S4.1).
     */
    @Transactional(readOnly = true)
    public Map<UUID, RelationshipContract> contracts(CallerContext caller, DataSourceId dataSourceId,
                                                     Collection<UUID> ids) {
        DataSource dataSource = dataSource(caller, dataSourceId);
        List<SemanticRelationship> found = new ArrayList<>();
        for (UUID id : new HashSet<>(ids)) {
            relationships.findById(caller.tenantId(), dataSourceId, id).ifPresent(found::add);
        }
        Map<UUID, PhysicalObject> loaded = load(dataSource, found);
        Map<UUID, RelationshipContract> contracts = new HashMap<>();
        found.forEach(r -> contracts.put(r.id(), evaluate(dataSource, r, loaded).contract()));
        return contracts;
    }

    /** On-demand suggestions; nothing is stored. */
    @Transactional(readOnly = true)
    public RelationshipCandidateFinder.Result candidates(CallerContext caller, DataSourceId dataSourceId,
                                                         UUID focusObjectId, int limit) {
        DataSource dataSource = dataSource(caller, dataSourceId);
        Set<String> catalogued = new HashSet<>(relationships.findSignatures(caller.tenantId(), dataSourceId, IN_USE));
        return candidateFinder.find(dataSource.engine(), objects.findAll(caller.tenantId(), dataSourceId), catalogued,
                focusObjectId, limit);
    }

    // --- helpers ---

    private EvaluatedRelationship insert(CallerContext caller, DataSource dataSource, SemanticRelationship created) {
        RelationshipValidator.Evaluation evaluation = evaluation(dataSource, created);
        List<DefinitionIssue> issues = new ArrayList<>(evaluation.issues());
        if (created.replacesId() != null && relationships.findById(caller.tenantId(), dataSource.id(),
                created.replacesId()).isEmpty()) {
            issues.add(new DefinitionIssue(DefinitionIssue.Code.REPLACEMENT_INVALID, "replacesId",
                    "no such relationship in this data source"));
        }
        if (!issues.isEmpty()) {
            throw new InvalidRelationshipException(issues);
        }
        rejectEquivalent(created);
        relationships.insert(created);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("origin", created.origin().name());
        details.put("sourceObjectId", created.sourceObjectId().toString());
        details.put("targetObjectId", created.targetObjectId().toString());
        details.put("pairs", created.pairs().size());
        details.put("declaredCardinality", created.declaredCardinality().name());
        if (created.foreignKeyId() != null) {
            details.put("foreignKeyId", created.foreignKeyId().toString());
        }
        if (created.replacesId() != null) {
            details.put("replaces", created.replacesId().toString());
        }
        audit(caller, AuditAction.RELATIONSHIP_CREATED, created, details);
        return evaluate(dataSource, created);
    }

    /** No other DRAFT or APPROVED relationship may join the same columns, except the one being replaced. */
    private void rejectEquivalent(SemanticRelationship relationship) {
        for (SemanticRelationship other : relationships.findBySignature(relationship.tenantId(),
                relationship.dataSourceId(), relationship.signature(), IN_USE)) {
            if (!other.id().equals(relationship.id()) && !other.id().equals(relationship.replacesId())) {
                throw new EquivalentRelationshipException(other.id());
            }
        }
    }

    private SemanticRelationship current(CallerContext caller, DataSourceId dataSourceId, UUID id,
                                         long expectedVersion) {
        SemanticRelationship current = relationships.findById(caller.tenantId(), dataSourceId, id)
                .orElseThrow(() -> new ResourceNotFoundException(RESOURCE));
        if (current.version() != expectedVersion) {
            throw new StaleResourceException(RESOURCE);
        }
        return current;
    }

    private RelationshipValidator.Evaluation evaluation(DataSource dataSource, SemanticRelationship relationship) {
        return validator.evaluate(dataSource.engine(), load(dataSource, List.of(relationship)), relationship);
    }

    private EvaluatedRelationship evaluate(DataSource dataSource, SemanticRelationship relationship) {
        return evaluate(dataSource, relationship, load(dataSource, List.of(relationship)));
    }

    private EvaluatedRelationship evaluate(DataSource dataSource, SemanticRelationship relationship,
                                           Map<UUID, PhysicalObject> loaded) {
        RelationshipValidator.Evaluation evaluation = validator.evaluate(dataSource.engine(), loaded, relationship);
        boolean usable = relationship.status() == DefinitionStatus.APPROVED && evaluation.valid();
        Map<UUID, PhysicalObject> references = new HashMap<>();
        referencedIds(relationship).forEach(id -> {
            PhysicalObject object = loaded.get(id);
            if (object != null) {
                references.put(id, object);
            }
        });
        return new EvaluatedRelationship(relationship, evaluation, usable, references);
    }

    private Map<UUID, PhysicalObject> load(DataSource dataSource, Collection<SemanticRelationship> items) {
        Set<UUID> ids = new HashSet<>();
        items.forEach(r -> ids.addAll(referencedIds(r)));
        Map<UUID, PhysicalObject> loaded = new HashMap<>();
        objects.findByIds(dataSource.tenantId(), dataSource.id(), ids).forEach(o -> loaded.put(o.id(), o));
        return loaded;
    }

    static List<UUID> referencedIds(SemanticRelationship relationship) {
        List<UUID> ids = new ArrayList<>();
        ids.add(relationship.sourceObjectId());
        ids.add(relationship.targetObjectId());
        relationship.pairs().forEach(p -> {
            ids.add(p.sourceColumnId());
            ids.add(p.targetColumnId());
        });
        if (relationship.foreignKeyId() != null) {
            ids.add(relationship.foreignKeyId());
        }
        return ids.stream().filter(Objects::nonNull).distinct().toList();
    }

    private static String defaultName(PhysicalObject fk) {
        String name = fk.childName() == null ? "foreign key" : fk.childName().strip();
        name = name.chars().anyMatch(Character::isISOControl) || name.isEmpty() ? "foreign key" : name;
        return name.length() > SemanticRelationship.MAX_NAME_LENGTH
                ? name.substring(0, SemanticRelationship.MAX_NAME_LENGTH) : name;
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

    private void audit(CallerContext caller, AuditAction action, SemanticRelationship relationship,
                       Map<String, Object> details) {
        Map<String, Object> all = new LinkedHashMap<>(details);
        all.put("dataSourceId", relationship.dataSourceId().value().toString());
        auditLog.append(AuditEvent.of(caller.tenantId(), relationship.updatedAt(), caller.principal(),
                caller.correlationId(), action, AuditTargetType.SEMANTIC_RELATIONSHIP, relationship.id(), all));
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }
}
