package com.nexora.semantic.domain.source;

import com.nexora.semantic.domain.shared.TextRules;

import java.util.regex.Pattern;

/** Business key of a data source, unique within its tenant and immutable after registration. */
public record DataSourceKey(String value) {

    private static final Pattern FORMAT = Pattern.compile("[a-z0-9][a-z0-9_-]{1,62}");

    public DataSourceKey {
        TextRules.requirePattern(value, "key", FORMAT,
                "must be 2-63 lowercase letters, digits, '_' or '-', starting with a letter or digit");
    }

    @Override
    public String toString() {
        return value;
    }
}
