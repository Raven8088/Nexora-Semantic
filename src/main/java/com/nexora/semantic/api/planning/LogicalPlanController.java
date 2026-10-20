package com.nexora.semantic.api.planning;

import com.nexora.semantic.api.planning.LogicalPlanDtos.PlanRequestBody;
import com.nexora.semantic.api.planning.LogicalPlanDtos.PlanResponse;
import com.nexora.semantic.api.planning.LogicalPlanDtos.ValidityResponse;
import com.nexora.semantic.api.security.Permission;
import com.nexora.semantic.application.planning.LogicalPlanService;
import com.nexora.semantic.domain.planning.StoredPlan;
import com.nexora.semantic.domain.shared.CallerContext;
import com.nexora.semantic.domain.source.DataSourceId;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.UUID;

/**
 * Logical plans (S4.2) of a data source of the caller's tenant; other tenants' data sources and plans are 404.
 * Every operation requires the existing {@code semantic:resolve} permission (role REPORTS_CLIENT): plans are
 * Reports' input and may carry filter values. No SQL is generated or executed.
 */
@RestController
@RequestMapping(path = "/api/v1/data-sources/{dataSourceId}/logical-plans", produces = MediaType.APPLICATION_JSON_VALUE)
public class LogicalPlanController {

    private final LogicalPlanService plans;
    private final ObjectMapper objectMapper;

    public LogicalPlanController(LogicalPlanService plans, ObjectMapper objectMapper) {
        this.plans = plans;
        this.objectMapper = objectMapper;
    }

    /** 201 with the persisted plan; 422 {@code semantic_resolution_failed} or {@code logical_plan_rejected}. */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Permission.Require.RESOLVE)
    public ResponseEntity<PlanResponse> create(CallerContext caller, @PathVariable UUID dataSourceId,
                                               @Valid @RequestBody PlanRequestBody request) {
        StoredPlan stored = plans.create(caller, new DataSourceId(dataSourceId), request.toDomain());
        return ResponseEntity.created(URI.create("/api/v1/data-sources/" + dataSourceId + "/logical-plans/"
                + stored.id())).body(response(stored));
    }

    @GetMapping("/{planId}")
    @PreAuthorize(Permission.Require.RESOLVE)
    public PlanResponse get(CallerContext caller, @PathVariable UUID dataSourceId, @PathVariable UUID planId) {
        return response(plans.get(caller, new DataSourceId(dataSourceId), planId));
    }

    @GetMapping("/{planId}/validity")
    @PreAuthorize(Permission.Require.RESOLVE)
    public ValidityResponse validity(CallerContext caller, @PathVariable UUID dataSourceId,
                                     @PathVariable UUID planId) {
        return ValidityResponse.from(plans.validity(caller, new DataSourceId(dataSourceId), planId));
    }

    private PlanResponse response(StoredPlan stored) {
        return PlanResponse.from(stored, objectMapper.readTree(stored.document()));
    }
}
