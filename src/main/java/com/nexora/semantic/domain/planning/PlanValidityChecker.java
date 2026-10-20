package com.nexora.semantic.domain.planning;

import com.nexora.semantic.domain.catalog.DefinitionStatus;
import com.nexora.semantic.domain.catalog.DefinitionValidator;
import com.nexora.semantic.domain.catalog.SemanticDefinition;
import com.nexora.semantic.domain.catalog.TypeCategory;
import com.nexora.semantic.domain.planning.PlanValidity.Code;
import com.nexora.semantic.domain.planning.PlanValidity.Issue;
import com.nexora.semantic.domain.relationship.RelationshipContract;
import com.nexora.semantic.domain.relationship.RowMultiplicationRisk;
import com.nexora.semantic.domain.source.EngineCode;
import com.nexora.semantic.domain.source.PhysicalObject;
import com.nexora.semantic.domain.source.PhysicalObjectStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Compares the revisions a plan recorded with the current catalog (pure domain logic, read-only). It checks the
 * conditions a safe re-execution would need (preparation for Reports); it never executes the plan and never
 * changes it.
 */
public final class PlanValidityChecker {

    /**
     * Current state, scoped to the plan's tenant and data source.
     *
     * @param catalog       current definitions (with their concepts) and physical objects
     * @param relationships current contracts of the plan's relationships (absent when not found)
     */
    public record Current(boolean dataSourceActive, EngineCode engine, DefinitionValidator.Context catalog,
                          Map<UUID, RelationshipContract> relationships, Map<UUID, PhysicalObject> objects) {
        public Current {
            Objects.requireNonNull(engine, "engine");
            Objects.requireNonNull(catalog, "catalog");
            relationships = Map.copyOf(relationships);
            objects = Map.copyOf(objects);
        }
    }

    private final DefinitionValidator validator = new DefinitionValidator();

    public PlanValidity check(StoredPlan plan, Current current) {
        List<Issue> issues = new ArrayList<>();
        if (!current.dataSourceActive()) {
            issues.add(new Issue(Code.DATA_SOURCE_ARCHIVED, true, null, plan.dataSourceId().value(),
                    "the data source is archived"));
        }
        for (PlanDependency dependency : plan.dependencies()) {
            switch (dependency.kind()) {
                case DEFINITION -> definition(dependency, current, issues);
                case RELATIONSHIP -> relationship(dependency, current, issues);
                case PHYSICAL_OBJECT -> object(dependency, current, issues);
            }
        }
        PlanValidity.Status status = issues.stream().anyMatch(Issue::blocking) ? PlanValidity.Status.STALE
                : issues.isEmpty() ? PlanValidity.Status.CURRENT : PlanValidity.Status.CURRENT_WITH_CHANGES;
        return new PlanValidity(plan.id(), status, issues);
    }

    private void definition(PlanDependency dependency, Current current, List<Issue> issues) {
        SemanticDefinition definition = current.catalog().definitions().get(dependency.referenceId());
        if (definition == null || definition.status() != DefinitionStatus.APPROVED) {
            issues.add(blocking(Code.DEFINITION_NOT_USABLE, dependency, "the definition is no longer APPROVED"));
        } else if (definition.version() != dependency.revision()) {
            issues.add(blocking(Code.DEFINITION_CHANGED, dependency,
                    "the definition was revised after the plan was created"));
        } else if (!validator.usageIssues(definition.spec(), current.catalog()).isEmpty()) {
            issues.add(blocking(Code.DEFINITION_NOT_USABLE, dependency,
                    "the definition is no longer valid against the physical metadata"));
        }
    }

    private static void relationship(PlanDependency dependency, Current current, List<Issue> issues) {
        RelationshipContract contract = current.relationships().get(dependency.referenceId());
        if (contract == null || !contract.usable()) {
            issues.add(blocking(Code.RELATIONSHIP_NOT_USABLE, dependency,
                    "the relationship is no longer APPROVED and physically valid"));
            return;
        }
        if (contract.version() != dependency.revision()) {
            issues.add(blocking(Code.RELATIONSHIP_CHANGED, dependency,
                    "the relationship was revised after the plan was created"));
        }
        RowMultiplicationRisk risk = dependency.navigation() == LogicalPlan.Navigation.FORWARD
                ? contract.evidence().sourceToTargetRisk() : contract.evidence().targetToSourceRisk();
        if (risk != RowMultiplicationRisk.NONE_BY_PRIMARY_KEY) {
            issues.add(blocking(Code.UNIQUENESS_NO_LONGER_PROVEN, dependency,
                    "the primary key that proved the join safe no longer backs it"));
        }
    }

    private static void object(PlanDependency dependency, Current current, List<Issue> issues) {
        PhysicalObject object = current.objects().get(dependency.referenceId());
        if (object == null || object.status() != PhysicalObjectStatus.ACTIVE) {
            issues.add(blocking(Code.PHYSICAL_OBJECT_MISSING, dependency,
                    "the object is missing from the latest metadata import"));
            return;
        }
        String dataType = object.attributes().values().get("dataType") instanceof String t ? t : null;
        if (dependency.dataType() != null && TypeCategory.of(current.engine(), dependency.dataType())
                != TypeCategory.of(current.engine(), dataType)) {
            issues.add(blocking(Code.PHYSICAL_TYPE_CHANGED, dependency,
                    "the column type changed to an incompatible category"));
        } else if (!object.fingerprint().equals(dependency.fingerprint())) {
            issues.add(new Issue(Code.PHYSICAL_OBJECT_CHANGED, false, dependency.kind(), dependency.referenceId(),
                    "observed attributes changed without affecting the plan's types"));
        }
    }

    private static Issue blocking(Code code, PlanDependency dependency, String message) {
        return new Issue(code, true, dependency.kind(), dependency.referenceId(), message);
    }
}
