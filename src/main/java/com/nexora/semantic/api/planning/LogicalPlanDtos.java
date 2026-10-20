package com.nexora.semantic.api.planning;

import com.nexora.semantic.api.resolution.ResolutionDtos;
import com.nexora.semantic.api.resolution.ResolutionDtos.FilterRequest;
import com.nexora.semantic.domain.catalog.TimeGranularity;
import com.nexora.semantic.domain.planning.PlanDependency;
import com.nexora.semantic.domain.planning.PlanRequest;
import com.nexora.semantic.domain.planning.PlanValidity;
import com.nexora.semantic.domain.planning.StoredPlan;
import com.nexora.semantic.domain.resolution.SemanticResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * HTTP contract of logical plans (S4.2). The request is the S4.1 request plus {@code timeGranularities}; unknown
 * properties are rejected. Responses return the plan document exactly as persisted.
 */
public final class LogicalPlanDtos {

    private LogicalPlanDtos() {
    }

    public record GranularityRequest(@NotNull UUID dimensionId, @NotNull TimeGranularity granularity) {
    }

    public record PlanRequestBody(
            @NotNull @Size(max = SemanticResolver.MAX_METRICS) List<@NotNull UUID> metrics,
            @Size(max = SemanticResolver.MAX_DIMENSIONS) List<@NotNull UUID> dimensions,
            @Size(max = SemanticResolver.MAX_FILTERS) List<@Valid @NotNull FilterRequest> filters,
            @Size(max = SemanticResolver.MAX_PREFERRED_RELATIONSHIPS) List<@NotNull UUID> preferredRelationships,
            @Size(max = SemanticResolver.MAX_DIMENSIONS) List<@Valid @NotNull GranularityRequest> timeGranularities) {

        PlanRequest toDomain() {
            return new PlanRequest(new ResolutionDtos.ResolutionRequestBody(metrics, dimensions, filters,
                    preferredRelationships).toDomain(),
                    timeGranularities == null ? List.of() : timeGranularities.stream()
                            .map(g -> new PlanRequest.GranularityChoice(g.dimensionId(), g.granularity())).toList());
        }
    }

    /** @param plan the immutable plan document ({@code formatVersion} {@code nexora-logical-plan/1}) */
    public record PlanResponse(UUID id, UUID dataSourceId, String formatVersion, String fingerprint,
                               String createdBy, Instant createdAt, JsonNode plan) {

        static PlanResponse from(StoredPlan stored, JsonNode document) {
            return new PlanResponse(stored.id(), stored.dataSourceId().value(), stored.formatVersion(),
                    stored.fingerprint(), stored.createdBy().id(), stored.createdAt(), document);
        }
    }

    public record IssueResponse(PlanValidity.Code code, boolean blocking, PlanDependency.Kind kind,
                                UUID referenceId, String message) {
    }

    /** Derived on demand; the plan itself is never modified. */
    public record ValidityResponse(UUID planId, PlanValidity.Status status, List<IssueResponse> issues) {

        static ValidityResponse from(PlanValidity validity) {
            return new ValidityResponse(validity.planId(), validity.status(), validity.issues().stream()
                    .map(i -> new IssueResponse(i.code(), i.blocking(), i.kind(), i.referenceId(), i.message()))
                    .toList());
        }
    }
}
