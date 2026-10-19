package com.nexora.semantic.domain.audit;

import com.nexora.semantic.domain.shared.PrincipalRef;
import com.nexora.semantic.domain.shared.PrincipalType;
import com.nexora.semantic.domain.shared.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditEventTest {

    private static final PrincipalRef ACTOR = new PrincipalRef("user-1", PrincipalType.HUMAN);

    private static AuditEvent event(Map<String, Object> details) {
        return AuditEvent.of(TenantId.newId(), Instant.now(), ACTOR, "cid-1", AuditAction.DATA_SOURCE_UPDATED,
                AuditTargetType.DATA_SOURCE, UUID.randomUUID(), details);
    }

    @Test
    void acceptsSimpleDetailValues() {
        AuditEvent event = event(Map.of("key", "erp", "count", 2, "flag", true, "changedFields", List.of("name")));
        assertThat(event.details()).containsEntry("changedFields", List.of("name")).hasSize(4);
    }

    @Test
    void detailsAreImmutableCopies() {
        List<String> fields = new ArrayList<>(List.of("name"));
        Map<String, Object> details = new HashMap<>(Map.of("changedFields", fields));
        AuditEvent event = event(details);
        fields.add("description");
        details.put("other", "x");

        assertThat(event.details()).hasSize(1).containsEntry("changedFields", List.of("name"));
        assertThatThrownBy(() -> event.details().put("x", "y")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rejectsComplexDetailValues() {
        assertThatThrownBy(() -> event(Map.of("nested", Map.of("a", "b")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> event(Map.of("objects", List.of(new Object()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullDetailsBecomeEmpty() {
        assertThat(event(null).details()).isEmpty();
    }
}
