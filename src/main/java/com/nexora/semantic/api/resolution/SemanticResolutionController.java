package com.nexora.semantic.api.resolution;

import com.nexora.semantic.api.resolution.ResolutionDtos.ResolutionRequestBody;
import com.nexora.semantic.api.resolution.ResolutionDtos.ResolutionResponse;
import com.nexora.semantic.api.security.Permission;
import com.nexora.semantic.application.resolution.SemanticResolutionService;
import com.nexora.semantic.domain.resolution.ResolutionOutcome;
import com.nexora.semantic.domain.resolution.UnresolvableRequestException;
import com.nexora.semantic.domain.shared.CallerContext;
import com.nexora.semantic.domain.source.DataSourceId;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Semantic resolution (S4.1) for a data source of the caller's tenant; data sources of other tenants are 404.
 * Requires the existing {@code semantic:resolve} permission (role REPORTS_CLIENT). POST because the request is a
 * structured document; nothing is created or persisted, so a successful resolution is {@code 200}. An unresolvable
 * request is {@code 422 semantic_resolution_failed} with typed diagnostics. No SQL is generated or executed.
 */
@RestController
@RequestMapping(path = "/api/v1/data-sources/{dataSourceId}/semantic-resolutions",
        produces = MediaType.APPLICATION_JSON_VALUE)
public class SemanticResolutionController {

    private final SemanticResolutionService resolutions;

    public SemanticResolutionController(SemanticResolutionService resolutions) {
        this.resolutions = resolutions;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Permission.Require.RESOLVE)
    public ResolutionResponse resolve(CallerContext caller, @PathVariable UUID dataSourceId,
                                      @Valid @RequestBody ResolutionRequestBody request) {
        ResolutionOutcome outcome = resolutions.resolve(caller, new DataSourceId(dataSourceId), request.toDomain());
        return switch (outcome) {
            case ResolutionOutcome.Resolved resolved -> ResolutionResponse.from(resolved.resolution());
            case ResolutionOutcome.Unresolvable unresolvable ->
                    throw new UnresolvableRequestException(unresolvable.diagnostics());
        };
    }
}
