package com.nexora.semantic.api.catalog;

import com.nexora.semantic.api.catalog.DefinitionDtos.CreateDefinitionRequest;
import com.nexora.semantic.api.catalog.DefinitionDtos.DeactivateRequest;
import com.nexora.semantic.api.catalog.DefinitionDtos.DefinitionResponse;
import com.nexora.semantic.api.catalog.DefinitionDtos.UpdateDefinitionRequest;
import com.nexora.semantic.api.security.Permission;
import com.nexora.semantic.api.web.ETags;
import com.nexora.semantic.api.web.PageResponse;
import com.nexora.semantic.application.catalog.SemanticCatalogService;
import com.nexora.semantic.application.catalog.SemanticCatalogService.EvaluatedDefinition;
import com.nexora.semantic.application.port.SemanticDefinitionRepository;
import com.nexora.semantic.domain.catalog.DefinitionKind;
import com.nexora.semantic.domain.catalog.DefinitionStatus;
import com.nexora.semantic.domain.shared.CallerContext;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.source.DataSourceId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * Semantic catalog of a data source of the caller's tenant (S3 Basic). Data sources of other tenants are 404.
 * Writes require {@code semantic:proposal:write}; approval and deactivation require
 * {@code semantic:proposal:approve} and, in the domain, a HUMAN principal other than the author and last editor.
 */
@RestController
@RequestMapping(path = "/api/v1/data-sources/{dataSourceId}/definitions", produces = MediaType.APPLICATION_JSON_VALUE)
public class DefinitionController {

    private static final String RESOURCE = "Semantic definition";

    private final SemanticCatalogService catalog;

    public DefinitionController(SemanticCatalogService catalog) {
        this.catalog = catalog;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Permission.Require.PROPOSAL_WRITE)
    public ResponseEntity<DefinitionResponse> create(CallerContext caller, @PathVariable UUID dataSourceId,
                                                     @Valid @RequestBody CreateDefinitionRequest request) {
        EvaluatedDefinition created = catalog.create(caller, new DataSourceId(dataSourceId), request.key(),
                request.name(), request.description(),
                DefinitionDtos.spec(request.kind(), request.concept(), request.metric(), request.dimension()));
        return ResponseEntity.created(URI.create("/api/v1/data-sources/" + dataSourceId + "/definitions/"
                        + created.definition().id()))
                .eTag(ETags.of(created.definition().version()))
                .body(DefinitionResponse.from(created));
    }

    @GetMapping
    @PreAuthorize(Permission.Require.CATALOG_READ)
    public PageResponse<DefinitionResponse> list(CallerContext caller, @PathVariable UUID dataSourceId,
                                                 @RequestParam(required = false) DefinitionKind kind,
                                                 @RequestParam(required = false) DefinitionStatus status,
                                                 @RequestParam(defaultValue = "0") @Min(0) int page,
                                                 @RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE)
                                                 @Min(1) @Max(PageQuery.MAX_SIZE) int size) {
        return PageResponse.of(catalog.list(caller, new DataSourceId(dataSourceId),
                new SemanticDefinitionRepository.Filter(kind, status), new PageQuery(page, size)),
                DefinitionResponse::from);
    }

    @GetMapping("/{definitionId}")
    @PreAuthorize(Permission.Require.CATALOG_READ)
    public ResponseEntity<DefinitionResponse> get(CallerContext caller, @PathVariable UUID dataSourceId,
                                                  @PathVariable UUID definitionId) {
        return respond(catalog.get(caller, new DataSourceId(dataSourceId), definitionId));
    }

    @PutMapping(path = "/{definitionId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Permission.Require.PROPOSAL_WRITE)
    public ResponseEntity<DefinitionResponse> update(CallerContext caller, @PathVariable UUID dataSourceId,
                                                     @PathVariable UUID definitionId,
                                                     @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                                     String ifMatch,
                                                     @Valid @RequestBody UpdateDefinitionRequest request) {
        long expectedVersion = ETags.expectedVersion(ifMatch, RESOURCE);
        return respond(catalog.update(caller, new DataSourceId(dataSourceId), definitionId, expectedVersion,
                request.name(), request.description(),
                DefinitionDtos.spec(null, request.concept(), request.metric(), request.dimension())));
    }

    @PostMapping("/{definitionId}/approve")
    @PreAuthorize(Permission.Require.PROPOSAL_APPROVE)
    public ResponseEntity<DefinitionResponse> approve(CallerContext caller, @PathVariable UUID dataSourceId,
                                                      @PathVariable UUID definitionId,
                                                      @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                                      String ifMatch) {
        long expectedVersion = ETags.expectedVersion(ifMatch, RESOURCE);
        return respond(catalog.approve(caller, new DataSourceId(dataSourceId), definitionId, expectedVersion));
    }

    @PostMapping(path = "/{definitionId}/deactivate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Permission.Require.PROPOSAL_APPROVE)
    public ResponseEntity<DefinitionResponse> deactivate(CallerContext caller, @PathVariable UUID dataSourceId,
                                                         @PathVariable UUID definitionId,
                                                         @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                                         String ifMatch,
                                                         @Valid @RequestBody DeactivateRequest request) {
        long expectedVersion = ETags.expectedVersion(ifMatch, RESOURCE);
        return respond(catalog.deactivate(caller, new DataSourceId(dataSourceId), definitionId, expectedVersion,
                request.reason()));
    }

    private static ResponseEntity<DefinitionResponse> respond(EvaluatedDefinition evaluated) {
        return ResponseEntity.ok().eTag(ETags.of(evaluated.definition().version()))
                .body(DefinitionResponse.from(evaluated));
    }
}
