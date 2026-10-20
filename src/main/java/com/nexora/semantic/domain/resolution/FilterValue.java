package com.nexora.semantic.domain.resolution;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** A filter value typed against the field it filters (S4.1). {@link #canonical()} is stable and unambiguous. */
public sealed interface FilterValue {

    /** Deterministic textual form (ISO-8601 for temporal values, plain string for numbers). */
    String canonical();

    record TextValue(String value) implements FilterValue {
        public TextValue {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String canonical() {
            return value;
        }
    }

    record NumberValue(BigDecimal value) implements FilterValue {
        public NumberValue {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String canonical() {
            return value.stripTrailingZeros().toPlainString();
        }
    }

    record BooleanValue(boolean value) implements FilterValue {
        @Override
        public String canonical() {
            return Boolean.toString(value);
        }
    }

    record DateValue(LocalDate value) implements FilterValue {
        public DateValue {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String canonical() {
            return value.toString();
        }
    }

    /** Wall-clock value for {@code timestamp without time zone} columns. */
    record TimestampValue(LocalDateTime value) implements FilterValue {
        public TimestampValue {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String canonical() {
            return value.toString();
        }
    }

    /** Instant for {@code timestamp with time zone} columns, normalized to UTC. */
    record InstantValue(OffsetDateTime value) implements FilterValue {
        public InstantValue {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String canonical() {
            return value.toString();
        }
    }

    record TimeValue(LocalTime value) implements FilterValue {
        public TimeValue {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String canonical() {
            return value.toString();
        }
    }

    record UuidValue(UUID value) implements FilterValue {
        public UuidValue {
            Objects.requireNonNull(value, "value");
        }

        @Override
        public String canonical() {
            return value.toString();
        }
    }
}
