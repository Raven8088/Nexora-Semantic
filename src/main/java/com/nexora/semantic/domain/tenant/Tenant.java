package com.nexora.semantic.domain.tenant;

import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.shared.TextRules;

import java.time.Instant;
import java.util.Objects;

/**
 * A company using Nexora. Root of the isolation boundary: every business record belongs to exactly one tenant.
 *
 * @param version optimistic locking version as stored; incremented by the repository on each update
 */
public record Tenant(
        TenantId id,
        TenantKey key,
        String name,
        TenantStatus status,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy,
        long version) {

    public static final int MAX_NAME_LENGTH = 200;

    public Tenant {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(key, "key");
        name = TextRules.requireText(name, "name", MAX_NAME_LENGTH);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(updatedBy, "updatedBy");
    }

    public static Tenant register(TenantId id, TenantKey key, String name, String actor, Instant now) {
        return new Tenant(id, key, name, TenantStatus.ACTIVE, now, actor, now, actor, 0);
    }

    /**
     * Applies the requested changes; {@code null} arguments keep the current value.
     * Returns {@code this} when nothing changes.
     */
    public Tenant change(String newName, TenantStatus newStatus, String actor, Instant now) {
        String name = newName == null ? this.name : TextRules.requireText(newName, "name", MAX_NAME_LENGTH);
        TenantStatus status = newStatus == null ? this.status : newStatus;
        if (name.equals(this.name) && status == this.status) {
            return this;
        }
        return new Tenant(id, key, name, status, createdAt, createdBy, now, actor, version);
    }

    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }
}
