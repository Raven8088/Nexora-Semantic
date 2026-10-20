package com.nexora.semantic.domain.resolution;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * A filter value as received (JSON string, number, boolean or null), before it is typed against the field. Text is
 * always data: it is never parsed as an expression or SQL.
 */
public sealed interface FilterLiteral {

    record Text(String value) implements FilterLiteral {
        public Text {
            Objects.requireNonNull(value, "value");
        }
    }

    record Number(BigDecimal value) implements FilterLiteral {
        public Number {
            Objects.requireNonNull(value, "value");
        }
    }

    record Bool(boolean value) implements FilterLiteral {
    }

    /** An explicit JSON {@code null}: never a valid comparison value (use IS_NULL / IS_NOT_NULL). */
    record Null() implements FilterLiteral {
    }
}
