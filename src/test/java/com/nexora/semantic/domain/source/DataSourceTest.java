package com.nexora.semantic.domain.source;

import com.nexora.semantic.domain.shared.InvalidValueException;
import com.nexora.semantic.domain.shared.TenantId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataSourceTest {

    private static final Instant T0 = Instant.parse("2026-10-19T08:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);
    private static final TenantId TENANT = TenantId.newId();

    private static DataSource erp() {
        return DataSource.register(DataSourceId.newId(), TENANT, new DataSourceKey("erp"), "ERP", new EngineCode("postgresql"),
                "erp", "Main ERP", "steward", T0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"erp", "erp_prod", "erp-2026", "0x"})
    void acceptsValidKeys(String key) {
        assertThat(new DataSourceKey(key).value()).isEqualTo(key);
    }

    @ParameterizedTest
    @ValueSource(strings = {"e", "ERP", "_erp", "erp prod", "erp.prod", "erp/../x"})
    void rejectsInvalidKeys(String key) {
        assertThatThrownBy(() -> new DataSourceKey(key)).isInstanceOf(InvalidValueException.class);
    }

    @Test
    void engineIsNormalizedToLowercase() {
        assertThat(new EngineCode(" PostgreSQL ").value()).isEqualTo("postgresql");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "postgres ql", "pg;drop", "engine-name-longer-than-32-characters"})
    void rejectsInvalidEngines(String engine) {
        assertThatThrownBy(() -> new EngineCode(engine)).isInstanceOf(InvalidValueException.class)
                .extracting("field").isEqualTo("engine");
    }

    @Test
    void registersActiveDataSourceWithVersionZero() {
        DataSource ds = erp();
        assertThat(ds.status()).isEqualTo(DataSourceStatus.ACTIVE);
        assertThat(ds.version()).isZero();
        assertThat(ds.createdBy()).isEqualTo("steward");
        assertThat(ds.updatedAt()).isEqualTo(T0);
    }

    @Test
    void rejectsInvalidAttributes() {
        assertThatThrownBy(() -> DataSource.register(DataSourceId.newId(), TENANT, new DataSourceKey("erp"), " ",
                new EngineCode("postgresql"), "erp", null, "steward", T0))
                .isInstanceOf(InvalidValueException.class).extracting("field").isEqualTo("name");
        assertThatThrownBy(() -> DataSource.register(DataSourceId.newId(), TENANT, new DataSourceKey("erp"), "ERP",
                new EngineCode("postgresql"), "x".repeat(129), null, "steward", T0))
                .isInstanceOf(InvalidValueException.class).extracting("field").isEqualTo("databaseName");
        assertThatThrownBy(() -> DataSource.register(DataSourceId.newId(), TENANT, new DataSourceKey("erp"), "ERP",
                new EngineCode("postgresql"), "erp", "x".repeat(1001), "steward", T0))
                .isInstanceOf(InvalidValueException.class).extracting("field").isEqualTo("description");
    }

    @Test
    void applyChangesRequestedFieldsOnly() {
        DataSource ds = erp();

        DataSource changed = ds.apply(new DataSourceChanges("ERP Producción", null, null), "editor", T1);

        assertThat(changed.name()).isEqualTo("ERP Producción");
        assertThat(changed.description()).isEqualTo("Main ERP");
        assertThat(changed.status()).isEqualTo(DataSourceStatus.ACTIVE);
        assertThat(changed.updatedBy()).isEqualTo("editor");
        assertThat(changed.updatedAt()).isEqualTo(T1);
        assertThat(changed.version()).isEqualTo(ds.version());
        assertThat(changed.changedDetailsComparedTo(ds)).containsExactly("name");
    }

    @Test
    void emptyDescriptionRemovesIt() {
        DataSource changed = erp().apply(new DataSourceChanges(null, "", null), "editor", T1);
        assertThat(changed.description()).isNull();
        assertThat(changed.changedDetailsComparedTo(erp())).containsExactly("description");
    }

    @Test
    void archiveAndReactivate() {
        DataSource archived = erp().apply(new DataSourceChanges(null, null, DataSourceStatus.ARCHIVED), "s", T1);
        assertThat(archived.isActive()).isFalse();
        assertThat(archived.changedDetailsComparedTo(erp())).isEmpty();

        DataSource reactivated = archived.apply(new DataSourceChanges(null, null, DataSourceStatus.ACTIVE), "s", T1);
        assertThat(reactivated.isActive()).isTrue();
    }

    @Test
    void applyWithoutDifferencesReturnsSameInstance() {
        DataSource ds = erp();
        assertThat(ds.apply(DataSourceChanges.NONE, "editor", T1)).isSameAs(ds);
        assertThat(ds.apply(new DataSourceChanges("ERP", "Main ERP", DataSourceStatus.ACTIVE), "editor", T1)).isSameAs(ds);
    }
}
