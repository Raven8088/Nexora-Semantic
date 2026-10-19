package com.nexora.semantic.application.port;

import com.nexora.semantic.domain.shared.Page;
import com.nexora.semantic.domain.shared.PageQuery;
import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.tenant.Tenant;
import com.nexora.semantic.domain.tenant.TenantKey;

import java.util.Optional;

public interface TenantRepository {

    /** @throws com.nexora.semantic.domain.shared.DuplicateResourceException if the key is already used */
    void insert(Tenant tenant);

    Optional<Tenant> findById(TenantId id);

    Optional<Tenant> findByKey(TenantKey key);

    Page<Tenant> findAll(PageQuery pageQuery);

    /**
     * Persists {@code tenant} if the stored version still equals {@code expectedVersion}.
     *
     * @return the tenant with its new version
     * @throws com.nexora.semantic.domain.shared.StaleResourceException otherwise
     */
    Tenant update(Tenant tenant, long expectedVersion);
}
