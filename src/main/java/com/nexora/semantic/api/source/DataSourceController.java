package com.nexora.semantic.api.source;

import com.nexora.semantic.api.security.Permission;
import com.nexora.semantic.api.source.DataSourceDtos.CreateDataSourceRequest;
import com.nexora.semantic.api.source.DataSourceDtos.DataSourceResponse;
import com.nexora.semantic.api.source.DataSourceDtos.UpdateDataSourceRequest;
import com.nexora.semantic.api.web.ETags;
import com.nexora.semantic.api.web.PageResponse;
import com.nexora.semantic.application.source.DataSourceService;
import com.nexora.semantic.application.source.RegisterDataSourceCommand;
import com.nexora.semantic.domain.shared.CallerContext;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.source.DataSource;
import com.nexora.semantic.domain.source.DataSourceChanges;
import com.nexora.semantic.domain.source.DataSourceId;
import com.nexora.semantic.domain.source.DataSourceStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * Data sources of the caller's tenant. The tenant is resolved from the token ({@link CallerContext}); resources of
 * other tenants are reported as 404.
 */
@RestController
@RequestMapping(path = "/api/v1/data-sources", produces = MediaType.APPLICATION_JSON_VALUE)
public class DataSourceController {

    private static final String RESOURCE = "Data source";

    private final DataSourceService dataSourceService;

    public DataSourceController(DataSourceService dataSourceService) {
        this.dataSourceService = dataSourceService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Permission.Require.SOURCE_MANAGE)
    public ResponseEntity<DataSourceResponse> register(CallerContext caller,
                                                       @Valid @RequestBody CreateDataSourceRequest request) {
        DataSource created = dataSourceService.register(caller, new RegisterDataSourceCommand(
                request.key(), request.name(), request.engine(), request.databaseName(), request.description()));
        return ResponseEntity.created(URI.create("/api/v1/data-sources/" + created.id()))
                .eTag(ETags.of(created.version()))
                .body(DataSourceResponse.from(created));
    }

    @GetMapping
    @PreAuthorize(Permission.Require.CATALOG_READ)
    public PageResponse<DataSourceResponse> list(CallerContext caller,
                                                 @RequestParam(required = false) DataSourceStatus status,
                                                 @RequestParam(defaultValue = "0") @Min(0) int page,
                                                 @RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE)
                                                 @Min(1) @Max(PageQuery.MAX_SIZE) int size) {
        return PageResponse.of(dataSourceService.list(caller, status, new PageQuery(page, size)),
                DataSourceResponse::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize(Permission.Require.CATALOG_READ)
    public ResponseEntity<DataSourceResponse> get(CallerContext caller, @PathVariable UUID id) {
        DataSource dataSource = dataSourceService.get(caller, new DataSourceId(id));
        return ResponseEntity.ok().eTag(ETags.of(dataSource.version())).body(DataSourceResponse.from(dataSource));
    }

    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Permission.Require.SOURCE_MANAGE)
    public ResponseEntity<DataSourceResponse> update(CallerContext caller, @PathVariable UUID id,
                                                     @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                                     String ifMatch,
                                                     @Valid @RequestBody UpdateDataSourceRequest request) {
        long expectedVersion = ETags.expectedVersion(ifMatch, RESOURCE);
        DataSource updated = dataSourceService.update(caller, new DataSourceId(id), expectedVersion,
                new DataSourceChanges(request.name(), request.description(), request.status()));
        return ResponseEntity.ok().eTag(ETags.of(updated.version())).body(DataSourceResponse.from(updated));
    }
}
