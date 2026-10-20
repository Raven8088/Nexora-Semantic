package com.nexora.semantic.api.catalog;

import com.nexora.semantic.api.catalog.DefinitionDtos.DeactivateRequest;
import com.nexora.semantic.api.catalog.RelationshipDtos.CandidatesResponse;
import com.nexora.semantic.api.catalog.RelationshipDtos.ContractResponse;
import com.nexora.semantic.api.catalog.RelationshipDtos.CreateFromForeignKeyRequest;
import com.nexora.semantic.api.catalog.RelationshipDtos.CreateRelationshipRequest;
import com.nexora.semantic.api.catalog.RelationshipDtos.RelationshipResponse;
import com.nexora.semantic.api.catalog.RelationshipDtos.UpdateRelationshipRequest;
import com.nexora.semantic.api.security.Permission;
import com.nexora.semantic.api.web.ETags;
import com.nexora.semantic.api.web.PageResponse;
import com.nexora.semantic.application.catalog.SemanticRelationshipService;
import com.nexora.semantic.application.catalog.SemanticRelationshipService.EvaluatedRelationship;
import com.nexora.semantic.application.port.SemanticRelationshipRepository;
import com.nexora.semantic.domain.catalog.DefinitionStatus;
import com.nexora.semantic.domain.relationship.RelationshipCandidateFinder;
import com.nexora.semantic.domain.relationship.RelationshipOrigin;
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
 * Semantic relationships of a data source of the caller's tenant (S3.1). Data sources of other tenants are 404.
 * Same permissions as the semantic catalog: reads require {@code semantic:catalog:read}, creation and edition
 * {@code semantic:proposal:write}, approval and deactivation {@code semantic:proposal:approve} and, in the domain, a
 * HUMAN principal other than the author and last editor.
 */
@RestController
@RequestMapping(path = "/api/v1/data-sources/{dataSourceId}", produces = MediaType.APPLICATION_JSON_VALUE)
public class RelationshipController {

    private static final String RESOURCE = "Semantic relationship";

    private final SemanticRelationshipService relationships;

    public RelationshipController(SemanticRelationshipService relationships) {
        this.relationships = relationships;
    }

    @PostMapping(path = "/relationships", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Permission.Require.PROPOSAL_WRITE)
    public ResponseEntity<RelationshipResponse> create(CallerContext caller, @PathVariable UUID dataSourceId,
                                                       @Valid @RequestBody CreateRelationshipRequest request) {
        return created(dataSourceId, relationships.create(caller, new DataSourceId(dataSourceId),
                new SemanticRelationshipService.SemanticDraft(request.name(), request.description(),
                        request.sourceObjectId(), request.targetObjectId(), request.columnPairs(),
                        request.declaredCardinality(), request.allowedJoinTypes(), request.replacesId())));
    }

    @PostMapping(path = "/relationships/from-foreign-key", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Permission.Require.PROPOSAL_WRITE)
    public ResponseEntity<RelationshipResponse> createFromForeignKey(
            CallerContext caller, @PathVariable UUID dataSourceId,
            @Valid @RequestBody CreateFromForeignKeyRequest request) {
        return created(dataSourceId, relationships.createFromForeignKey(caller, new DataSourceId(dataSourceId),
                new SemanticRelationshipService.ForeignKeyDraft(request.foreignKeyId(), request.name(),
                        request.description(), request.declaredCardinality(), request.allowedJoinTypes(),
                        request.replacesId())));
    }

    @GetMapping("/relationships")
    @PreAuthorize(Permission.Require.CATALOG_READ)
    public PageResponse<RelationshipResponse> list(CallerContext caller, @PathVariable UUID dataSourceId,
                                                   @RequestParam(required = false) DefinitionStatus status,
                                                   @RequestParam(required = false) RelationshipOrigin origin,
                                                   @RequestParam(required = false) UUID objectId,
                                                   @RequestParam(defaultValue = "0") @Min(0) int page,
                                                   @RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE)
                                                   @Min(1) @Max(PageQuery.MAX_SIZE) int size) {
        return PageResponse.of(relationships.list(caller, new DataSourceId(dataSourceId),
                        new SemanticRelationshipRepository.Filter(status, origin, objectId), new PageQuery(page, size)),
                RelationshipResponse::from);
    }

    @GetMapping("/relationships/{relationshipId}")
    @PreAuthorize(Permission.Require.CATALOG_READ)
    public ResponseEntity<RelationshipResponse> get(CallerContext caller, @PathVariable UUID dataSourceId,
                                                    @PathVariable UUID relationshipId) {
        return respond(relationships.get(caller, new DataSourceId(dataSourceId), relationshipId));
    }

    /** Validity and evidence as the typed contract S4 will consume. */
    @GetMapping("/relationships/{relationshipId}/contract")
    @PreAuthorize(Permission.Require.CATALOG_READ)
    public ResponseEntity<ContractResponse> contract(CallerContext caller, @PathVariable UUID dataSourceId,
                                                     @PathVariable UUID relationshipId) {
        EvaluatedRelationship evaluated = relationships.get(caller, new DataSourceId(dataSourceId), relationshipId);
        return ResponseEntity.ok().eTag(ETags.of(evaluated.relationship().version()))
                .body(ContractResponse.from(evaluated.contract()));
    }

    @PutMapping(path = "/relationships/{relationshipId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Permission.Require.PROPOSAL_WRITE)
    public ResponseEntity<RelationshipResponse> update(CallerContext caller, @PathVariable UUID dataSourceId,
                                                       @PathVariable UUID relationshipId,
                                                       @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                                       String ifMatch,
                                                       @Valid @RequestBody UpdateRelationshipRequest request) {
        long expectedVersion = ETags.expectedVersion(ifMatch, RESOURCE);
        return respond(relationships.update(caller, new DataSourceId(dataSourceId), relationshipId, expectedVersion,
                request.name(), request.description(), request.declaredCardinality(), request.allowedJoinTypes()));
    }

    @PostMapping("/relationships/{relationshipId}/approve")
    @PreAuthorize(Permission.Require.PROPOSAL_APPROVE)
    public ResponseEntity<RelationshipResponse> approve(CallerContext caller, @PathVariable UUID dataSourceId,
                                                        @PathVariable UUID relationshipId,
                                                        @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                                        String ifMatch) {
        long expectedVersion = ETags.expectedVersion(ifMatch, RESOURCE);
        return respond(relationships.approve(caller, new DataSourceId(dataSourceId), relationshipId,
                expectedVersion));
    }

    @PostMapping(path = "/relationships/{relationshipId}/deactivate", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Permission.Require.PROPOSAL_APPROVE)
    public ResponseEntity<RelationshipResponse> deactivate(CallerContext caller, @PathVariable UUID dataSourceId,
                                                           @PathVariable UUID relationshipId,
                                                           @RequestHeader(name = HttpHeaders.IF_MATCH,
                                                                   required = false) String ifMatch,
                                                           @Valid @RequestBody DeactivateRequest request) {
        long expectedVersion = ETags.expectedVersion(ifMatch, RESOURCE);
        return respond(relationships.deactivate(caller, new DataSourceId(dataSourceId), relationshipId,
                expectedVersion, request.reason()));
    }

    /** On-demand heuristic suggestions; nothing is stored and nothing becomes usable without a DRAFT and approval. */
    @GetMapping("/relationship-candidates")
    @PreAuthorize(Permission.Require.CATALOG_READ)
    public CandidatesResponse candidates(CallerContext caller, @PathVariable UUID dataSourceId,
                                         @RequestParam(required = false) UUID objectId,
                                         @RequestParam(defaultValue = "50") @Min(1)
                                         @Max(RelationshipCandidateFinder.MAX_RESULTS) int limit) {
        return CandidatesResponse.from(relationships.candidates(caller, new DataSourceId(dataSourceId), objectId,
                limit));
    }

    private static ResponseEntity<RelationshipResponse> created(UUID dataSourceId, EvaluatedRelationship created) {
        return ResponseEntity.created(URI.create("/api/v1/data-sources/" + dataSourceId + "/relationships/"
                        + created.relationship().id()))
                .eTag(ETags.of(created.relationship().version()))
                .body(RelationshipResponse.from(created));
    }

    private static ResponseEntity<RelationshipResponse> respond(EvaluatedRelationship evaluated) {
        return ResponseEntity.ok().eTag(ETags.of(evaluated.relationship().version()))
                .body(RelationshipResponse.from(evaluated));
    }
}
