package com.nexora.semantic.api.security;

/**
 * Fine-grained permissions. They are the Spring Security authorities checked by endpoints; roles only group them.
 */
public enum Permission {
    CATALOG_READ(Names.CATALOG_READ),
    PROPOSAL_WRITE(Names.PROPOSAL_WRITE),
    PROPOSAL_APPROVE(Names.PROPOSAL_APPROVE),
    SOURCE_MANAGE(Names.SOURCE_MANAGE),
    AUDIT_READ(Names.AUDIT_READ),
    RESOLVE(Names.RESOLVE),
    PLATFORM_TENANT_MANAGE(Names.PLATFORM_TENANT_MANAGE);

    private final String authority;

    Permission(String authority) {
        this.authority = authority;
    }

    public String authority() {
        return authority;
    }

    /** Compile-time constants for {@code @PreAuthorize} expressions. */
    public static final class Names {
        public static final String CATALOG_READ = "semantic:catalog:read";
        public static final String PROPOSAL_WRITE = "semantic:proposal:write";
        public static final String PROPOSAL_APPROVE = "semantic:proposal:approve";
        public static final String SOURCE_MANAGE = "semantic:source:manage";
        public static final String AUDIT_READ = "semantic:audit:read";
        public static final String RESOLVE = "semantic:resolve";
        public static final String PLATFORM_TENANT_MANAGE = "platform:tenant:manage";

        private Names() {
        }
    }

    /** {@code @PreAuthorize} expressions. */
    public static final class Require {
        public static final String CATALOG_READ = "hasAuthority('" + Names.CATALOG_READ + "')";
        public static final String SOURCE_MANAGE = "hasAuthority('" + Names.SOURCE_MANAGE + "')";
        public static final String AUDIT_READ = "hasAuthority('" + Names.AUDIT_READ + "')";
        public static final String PLATFORM_TENANT_MANAGE = "hasAuthority('" + Names.PLATFORM_TENANT_MANAGE + "')";

        private Require() {
        }
    }
}
