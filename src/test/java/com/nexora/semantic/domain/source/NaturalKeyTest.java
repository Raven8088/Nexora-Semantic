package com.nexora.semantic.domain.source;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class NaturalKeyTest {

    @Test
    void quotesEverySegment() {
        assertThat(NaturalKey.schema("sales").value()).isEqualTo("\"sales\"");
        assertThat(NaturalKey.relation("sales", "orders").value()).isEqualTo("\"sales\".\"orders\"");
        assertThat(NaturalKey.column("sales", "orders", "amount").value()).isEqualTo("\"sales\".\"orders\".\"amount\"");
        assertThat(NaturalKey.foreignKey("sales", "order_lines", "order_lines_order_fk").value())
                .isEqualTo("\"sales\".\"order_lines\"#\"order_lines_order_fk\"");
    }

    @Test
    void namesContainingSeparatorsCannotCollide() {
        assertThat(NaturalKey.relation("a.b", "c")).isNotEqualTo(NaturalKey.relation("a", "b.c"));
        assertThat(NaturalKey.column("s", "t\".\"x", "c")).isNotEqualTo(NaturalKey.column("s", "t", "x\".\"c"));
        assertThat(NaturalKey.relation("s", "t\"q").value()).isEqualTo("\"s\".\"t\"\"q\"");
        assertThat(NaturalKey.foreignKey("s", "t", "#x")).isNotEqualTo(NaturalKey.column("s", "t", "#x"));
    }

    @Test
    void isExactAndCaseSensitive() {
        assertThat(NaturalKey.relation("sales", "Orders")).isNotEqualTo(NaturalKey.relation("sales", "orders"));
        assertThat(NaturalKey.schema(" sales")).isNotEqualTo(NaturalKey.schema("sales"));
    }
}
