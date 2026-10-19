package com.nexora.semantic.domain.shared;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TextRulesTest {

    @Test
    void requireTextTrims() {
        assertThat(TextRules.requireText("  Ventas  ", "name", 10)).isEqualTo("Ventas");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "\t"})
    void requireTextRejectsBlank(String value) {
        assertThatThrownBy(() -> TextRules.requireText(value, "name", 10))
                .isInstanceOf(InvalidValueException.class)
                .hasMessage("must not be blank")
                .extracting("field").isEqualTo("name");
    }

    @Test
    void requireTextRejectsTooLongWithoutEchoingValue() {
        assertThatThrownBy(() -> TextRules.requireText("secret-value-xyz", "name", 5))
                .isInstanceOf(InvalidValueException.class)
                .hasMessage("must be at most 5 characters")
                .message().doesNotContain("secret");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a\u0000b", "a\nb", "a\u001Bb", "a\u007Fb"})
    void requireTextRejectsControlCharacters(String value) {
        assertThatThrownBy(() -> TextRules.requireText(value, "name", 10))
                .isInstanceOf(InvalidValueException.class)
                .hasMessage("must not contain control characters");
    }

    @Test
    void optionalTextAllowsLineBreaksAndNormalizesBlankToNull() {
        assertThat(TextRules.optionalText("line 1\nline 2\ttab", "description", 50)).isEqualTo("line 1\nline 2\ttab");
        assertThat(TextRules.optionalText("   ", "description", 50)).isNull();
        assertThat(TextRules.optionalText(null, "description", 50)).isNull();
    }

    @Test
    void optionalTextRejectsOtherControlCharacters() {
        assertThatThrownBy(() -> TextRules.optionalText("a\u0007b", "description", 50))
                .isInstanceOf(InvalidValueException.class);
    }
}
