package com.nexora.semantic.api.tenant;

import com.nexora.semantic.api.security.Permission;
import com.nexora.semantic.api.tenant.TenantDtos.CreateTenantRequest;
import com.nexora.semantic.api.tenant.TenantDtos.TenantResponse;
import com.nexora.semantic.api.tenant.TenantDtos.UpdateTenantRequest;
import com.nexora.semantic.api.web.CorrelationIdFilter;
import com.nexora.semantic.api.web.ETags;
import com.nexora.semantic.api.web.PageResponse;
import com.nexora.semantic.application.tenant.TenantService;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.tenant.Tenant;
import jakarta.servlet.http.HttpServletRequest;
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

/** Platform administration of tenants. Not tenant-scoped; requires {@code platform:tenant:manage}. */
@RestController
@RequestMapping(path = "/api/v1/admin/tenants", produces = MediaType.APPLICATION_JSON_VALUE)
public class TenantAdminController {

    private static final String RESOURCE = "Tenant";

    private final TenantService tenantService;

    public TenantAdminController(TenantService tenantService) {
        this.tenantService = tenantService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Permission.Require.PLATFORM_TENANT_MANAGE)
    public ResponseEntity<TenantResponse> register(PrincipalRef caller, HttpServletRequest httpRequest,
                                                   @Valid @RequestBody CreateTenantRequest request) {
        Tenant tenant = tenantService.register(request.key(), request.name(), caller,
                CorrelationIdFilter.current(httpRequest));
        return ResponseEntity.created(URI.create("/api/v1/admin/tenants/" + tenant.id()))
                .eTag(ETags.of(tenant.version()))
                .body(TenantResponse.from(tenant));
    }

    @GetMapping
    @PreAuthorize(Permission.Require.PLATFORM_TENANT_MANAGE)
    public PageResponse<TenantResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                             @RequestParam(defaultValue = "" + PageQuery.DEFAULT_SIZE)
                                             @Min(1) @Max(PageQuery.MAX_SIZE) int size) {
        return PageResponse.of(tenantService.list(new PageQuery(page, size)), TenantResponse::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize(Permission.Require.PLATFORM_TENANT_MANAGE)
    public ResponseEntity<TenantResponse> get(@PathVariable UUID id) {
        Tenant tenant = tenantService.get(new TenantId(id));
        return ResponseEntity.ok().eTag(ETags.of(tenant.version())).body(TenantResponse.from(tenant));
    }

    @PatchMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(Permission.Require.PLATFORM_TENANT_MANAGE)
    public ResponseEntity<TenantResponse> update(PrincipalRef caller, HttpServletRequest httpRequest,
                                                 @PathVariable UUID id,
                                                 @RequestHeader(name = HttpHeaders.IF_MATCH, required = false)
                                                 String ifMatch,
                                                 @Valid @RequestBody UpdateTenantRequest request) {
        long expectedVersion = ETags.expectedVersion(ifMatch, RESOURCE);
        Tenant tenant = tenantService.update(new TenantId(id), expectedVersion, request.name(), request.status(),
                caller, CorrelationIdFilter.current(httpRequest));
        return ResponseEntity.ok().eTag(ETags.of(tenant.version())).body(TenantResponse.from(tenant));
    }
}
