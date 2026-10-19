package com.nexora.semantic.domain.source;

import com.nexora.semantic.domain.shared.TextRules;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Database engine code as used by Nexora Discovery (e.g. {@code postgresql}). A value object rather than an
 * enum, like Discovery's {@code DatabaseEngine}, so new engines need no change here. Normalized to lowercase.
 */
public record EngineCode(String value) {

    private static final Pattern FORMAT = Pattern.compile("[a-z0-9_-]{1,32}");

    public EngineCode {
        value = TextRules.requirePattern(value == null ? null : value.strip().toLowerCase(Locale.ROOT),
                "engine", FORMAT, "must be 1-32 letters, digits, '_' or '-'");
    }

    @Override
    public String toString() {
        return value;
    }
}
