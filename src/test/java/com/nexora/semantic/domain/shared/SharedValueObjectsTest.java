package com.nexora.semantic.domain.shared;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SharedValueObjectsTest {

    @Test
    void pageQueryValidatesBounds() {
        assertThat(new PageQuery(2, 50).offset()).isEqualTo(100);
        assertThatThrownBy(() -> new PageQuery(-1, 10)).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> new PageQuery(0, 0)).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> new PageQuery(0, PageQuery.MAX_SIZE + 1)).isInstanceOf(InvalidValueException.class);
    }

    @Test
    void pageMapsItemsAndKeepsCounts() {
        Page<Integer> page = new Page<>(List.of(1, 2), 1, 2, 5);
        Page<String> mapped = page.map(String::valueOf);
        assertThat(mapped.items()).containsExactly("1", "2");
        assertThat(mapped.totalItems()).isEqualTo(5);
        assertThat(mapped.page()).isEqualTo(1);
    }

    @Test
    void principalRefRequiresIdentifier() {
        assertThat(new PrincipalRef("user-1", PrincipalType.HUMAN).isHuman()).isTrue();
        assertThat(new PrincipalRef("svc", PrincipalType.SERVICE).isHuman()).isFalse();
        assertThatThrownBy(() -> new PrincipalRef(" ", PrincipalType.HUMAN)).isInstanceOf(InvalidValueException.class);
        assertThatThrownBy(() -> new PrincipalRef("x".repeat(256), PrincipalType.AI))
                .isInstanceOf(InvalidValueException.class);
    }

    @Test
    void callerContextRequiresTenantAndPrincipal() {
        PrincipalRef principal = new PrincipalRef("user-1", PrincipalType.HUMAN);
        assertThatThrownBy(() -> new CallerContext(null, principal, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CallerContext(TenantId.newId(), null, null))
                .isInstanceOf(NullPointerException.class);
    }
}
