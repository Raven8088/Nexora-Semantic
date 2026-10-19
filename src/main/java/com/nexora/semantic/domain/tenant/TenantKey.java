package com.nexora.semantic.domain.tenant;

import com.nexora.semantic.domain.shared.TextRules;

import java.util.regex.Pattern;

/** Immutable business key of a tenant, used as the tenant claim of access tokens. */
public record TenantKey(String value) {

    private static final Pattern FORMAT = Pattern.compile("[a-z0-9][a-z0-9-]{1,62}");

    public TenantKey {
        TextRules.requirePattern(value, "key", FORMAT,
                "must be 2-63 lowercase letters, digits or '-', starting with a letter or digit");
    }

    @Override
    public String toString() {
        return value;
    }
}
