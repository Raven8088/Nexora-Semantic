package com.nexora.semantic.api.source;

import com.nexora.semantic.api.security.Permission;
import com.nexora.semantic.api.source.MetadataDtos.PhysicalObjectResponse;
import com.nexora.semantic.api.source.MetadataDtos.SnapshotResponse;
import com.nexora.semantic.api.web.BoundedBody;
import com.nexora.semantic.api.web.PageResponse;
import com.nexora.semantic.application.port.PhysicalObjectRepository;
import com.nexora.semantic.application.source.MetadataImportService;
import com.nexora.semantic.application.source.PhysicalMetadataQueryService;
import com.nexora.semantic.domain.shared.CallerContext;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.source.DataSourceId;
import com.nexora.semantic.domain.source.MetadataSnapshot;
import com.nexora.semantic.domain.source.PhysicalObjectStatus;
import com.nexora.semantic.domain.source.PhysicalObjectType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

/**
 * Discovery metadata of a data source of the caller's tenant (S2 Basic): import documents and browse the import
 * history and the physical objects. Data sources of other tenants are reported as 404.
 */
@RestController
@RequestMapping(path = "/api/v1/data-sources/{dataSourceId}", produces = MediaType.APPLICATION_JSON_VALUE)
public class MetadataController {

    private final MetadataImportService importService;
    private final PhysicalMetadataQueryService queryService;
    private final ImportProperties properties;

    public MetadataController(MetadataImportService importService, PhysicalMetadataQueryService queryService,
                              ImportProperties properties) {
        this.importService = importService;
        this.queryService = queryService;
        this.properties = properties;
    }

    /**
     * Imports Semantic's envelope around a Discovery response. The body is read here (not with {@code @RequestBody})
     * so that oversized documents are rejected without being buffered, and so that the Discovery payload is parsed
     * only by the tolerant anti-corruption adapter instead of the strict global JSON mapper.
     */
    @PostMapping(path = "/snapshots", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Permission.Require.SOURCE_MANAGE)
    public ResponseEntity<SnapshotResponse> importSnapshot(CallerContext caller, @PathVariable UUID dataSourceId,
                                                           HttpServletRequest request) throws IOException {
        byte[] document = BoundedBody.read(request, properties.maxImportBytes());
        MetadataSnapshot snapshot = importService.importSnapshot(caller, new DataSourceId(dataSourceId), document);
        return ResponseEntity.created(URI.create("/api/v1/data-sources/" + dataSourceId + "/snapshots/" + snapshot.id()))
                .body(SnapshotResponse.from(snapshot));
    }

    @GetMapping("/snapshots")
    @PreAuthorize(Permission.Require.CATALOG_READ)
    public PageResponse<SnapshotResponse> snapshots(CallerContext caller, @PathVariable UUID dataSourceId,
                                                    @RequestParam(defaultValue = "0") @Min(0) int page,
                                                    @RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE)
                                                    @Min(1) @Max(PageQuery.MAX_SIZE) int size) {
        return PageResponse.of(queryService.snapshots(caller, new DataSourceId(dataSourceId), new PageQuery(page, size)),
                SnapshotResponse::from);
    }

    @GetMapping("/snapshots/{snapshotId}")
    @PreAuthorize(Permission.Require.CATALOG_READ)
    public SnapshotResponse snapshot(CallerContext caller, @PathVariable UUID dataSourceId,
                                     @PathVariable UUID snapshotId) {
        return SnapshotResponse.from(queryService.snapshot(caller, new DataSourceId(dataSourceId), snapshotId));
    }

    @GetMapping("/objects")
    @PreAuthorize(Permission.Require.CATALOG_READ)
    public PageResponse<PhysicalObjectResponse> objects(CallerContext caller, @PathVariable UUID dataSourceId,
                                                        @RequestParam(required = false) PhysicalObjectType type,
                                                        @RequestParam(required = false) @Size(min = 1, max = 128)
                                                        String schema,
                                                        @RequestParam(required = false) PhysicalObjectStatus status,
                                                        @RequestParam(required = false) UUID parentId,
                                                        @RequestParam(defaultValue = "0") @Min(0) int page,
                                                        @RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE)
                                                        @Min(1) @Max(PageQuery.MAX_SIZE) int size) {
        PhysicalObjectRepository.Filter filter = new PhysicalObjectRepository.Filter(type, schema, status, parentId);
        return PageResponse.of(queryService.objects(caller, new DataSourceId(dataSourceId), filter,
                new PageQuery(page, size)), PhysicalObjectResponse::from);
    }

    @GetMapping("/objects/{objectId}")
    @PreAuthorize(Permission.Require.CATALOG_READ)
    public PhysicalObjectResponse object(CallerContext caller, @PathVariable UUID dataSourceId,
                                         @PathVariable UUID objectId) {
        return PhysicalObjectResponse.from(queryService.object(caller, new DataSourceId(dataSourceId), objectId));
    }
}
