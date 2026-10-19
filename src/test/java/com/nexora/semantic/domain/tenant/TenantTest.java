package com.nexora.semantic.domain.tenant;

import com.nexora.semantic.domain.shared.InvalidValueException;
import com.nexora.semantic.domain.shared.TenantId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TenantTest {

    private static final Instant T0 = Instant.parse("2026-10-19T08:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);

    @ParameterizedTest
    @ValueSource(strings = {"acme", "acme-corp", "a1", "0-company"})
    void acceptsValidKeys(String key) {
        assertThat(new TenantKey(key).value()).isEqualTo(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "Acme", "-acme", "acme_corp", "acme corp", "acme.corp", "ácme", ""})
    void rejectsInvalidKeys(String key) {
        assertThatThrownBy(() -> new TenantKey(key)).isInstanceOf(InvalidValueException.class)
                .extracting("field").isEqualTo("key");
    }

    @Test
    void registersActiveTenant() {
        Tenant tenant = Tenant.register(TenantId.newId(), new TenantKey("acme"), " Acme ", "admin", T0);
        assertThat(tenant.status()).isEqualTo(TenantStatus.ACTIVE);
        assertThat(tenant.name()).isEqualTo("Acme");
        assertThat(tenant.version()).isZero();
        assertThat(tenant.isActive()).isTrue();
    }

    @Test
    void changeAppliesOnlyRequestedValues() {
        Tenant tenant = Tenant.register(TenantId.newId(), new TenantKey("acme"), "Acme", "admin", T0);

        Tenant suspended = tenant.change(null, TenantStatus.SUSPENDED, "ops", T1);

        assertThat(suspended.name()).isEqualTo("Acme");
        assertThat(suspended.status()).isEqualTo(TenantStatus.SUSPENDED);
        assertThat(suspended.updatedBy()).isEqualTo("ops");
        assertThat(suspended.updatedAt()).isEqualTo(T1);
        assertThat(suspended.createdAt()).isEqualTo(T0);
        assertThat(suspended.isActive()).isFalse();
    }

    @Test
    void changeWithoutDifferencesReturnsSameInstance() {
        Tenant tenant = Tenant.register(TenantId.newId(), new TenantKey("acme"), "Acme", "admin", T0);
        assertThat(tenant.change("Acme", TenantStatus.ACTIVE, "ops", T1)).isSameAs(tenant);
        assertThat(tenant.change(null, null, "ops", T1)).isSameAs(tenant);
    }
}
