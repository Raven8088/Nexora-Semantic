package com.nexora.semantic.domain.catalog;

import com.nexora.semantic.domain.shared.TextRules;

import java.util.regex.Pattern;

/** Stable technical key of a definition (e.g. {@code ventas_netas}), unique per data source and kind; immutable. */
public record DefinitionKey(String value) {

    private static final Pattern FORMAT = Pattern.compile("[a-z0-9][a-z0-9_-]{1,62}");

    public DefinitionKey {
        TextRules.requirePattern(value, "key", FORMAT,
                "must be 2-63 lowercase letters, digits, '_' or '-', starting with a letter or digit");
    }

    @Override
    public String toString() {
        return value;
    }
}
