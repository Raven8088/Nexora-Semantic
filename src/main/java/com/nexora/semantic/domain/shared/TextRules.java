package com.nexora.semantic.domain.shared;

import java.util.regex.Pattern;

/** Validation helpers for free text and identifiers. Error messages never include the rejected value. */
public final class TextRules {

    private TextRules() {
    }

    /** Trimmed, non-blank, at most {@code maxLength} characters and without control characters. */
    public static String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new InvalidValueException(field, "must not be blank");
        }
        String trimmed = value.strip();
        checkLength(trimmed, field, maxLength);
        if (trimmed.chars().anyMatch(Character::isISOControl)) {
            throw new InvalidValueException(field, "must not contain control characters");
        }
        return trimmed;
    }

    /**
     * Optional multi-line text: null or blank becomes null; line breaks and tabs are allowed, other control
     * characters are not.
     */
    public static String optionalText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        checkLength(trimmed, field, maxLength);
        if (trimmed.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
            throw new InvalidValueException(field, "must not contain control characters");
        }
        return trimmed;
    }

    /** Value must fully match {@code pattern}; {@code rule} describes the format without echoing the value. */
    public static String requirePattern(String value, String field, Pattern pattern, String rule) {
        if (value == null || !pattern.matcher(value).matches()) {
            throw new InvalidValueException(field, rule);
        }
        return value;
    }

    private static void checkLength(String value, String field, int maxLength) {
        if (value.length() > maxLength) {
            throw new InvalidValueException(field, "must be at most " + maxLength + " characters");
        }
    }
}
