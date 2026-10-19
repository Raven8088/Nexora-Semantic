package com.nexora.semantic.api.security;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import static com.nexora.semantic.api.security.Permission.AUDIT_READ;
import static com.nexora.semantic.api.security.Permission.CATALOG_READ;
import static com.nexora.semantic.api.security.Permission.PLATFORM_TENANT_MANAGE;
import static com.nexora.semantic.api.security.Permission.PROPOSAL_APPROVE;
import static com.nexora.semantic.api.security.Permission.PROPOSAL_WRITE;
import static com.nexora.semantic.api.security.Permission.RESOLVE;
import static com.nexora.semantic.api.security.Permission.SOURCE_MANAGE;

/** Roles carried by access tokens and the permissions they grant (docs/ARCHITECTURE.md §8.1). */
public enum Role {
    VIEWER(CATALOG_READ),
    EDITOR(CATALOG_READ, PROPOSAL_WRITE),
    APPROVER(CATALOG_READ, PROPOSAL_WRITE, PROPOSAL_APPROVE),
    STEWARD(CATALOG_READ, SOURCE_MANAGE),
    AUDITOR(CATALOG_READ, AUDIT_READ),
    REPORTS_CLIENT(CATALOG_READ, RESOLVE),
    AI_AGENT(CATALOG_READ, PROPOSAL_WRITE),
    PLATFORM_ADMIN(PLATFORM_TENANT_MANAGE);

    private final Set<Permission> permissions;

    Role(Permission first, Permission... rest) {
        this.permissions = EnumSet.of(first, rest);
    }

    public Set<Permission> permissions() {
        return EnumSet.copyOf(permissions);
    }

    /** Case-insensitive lookup; unknown names (e.g. identity provider defaults) are ignored. */
    public static Optional<Role> fromName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(name.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
