package com.nexora.semantic.domain.source;

import com.nexora.semantic.domain.shared.TenantId;
import com.nexora.semantic.domain.shared.TextRules;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A customer database whose metadata Semantic interprets. It deliberately holds no connection data: no host,
 * user or password. Metadata reaches Semantic as imported Discovery snapshots.
 *
 * @param engine       engine code as reported by Discovery; immutable
 * @param databaseName database name as reported by Discovery; immutable, used to match imported snapshots
 * @param description  optional free text; null when absent
 * @param version      optimistic locking version as stored; incremented by the repository on each update
 */
public record DataSource(
        DataSourceId id,
        TenantId tenantId,
        DataSourceKey key,
        String name,
        EngineCode engine,
        String databaseName,
        String description,
        DataSourceStatus status,
        Instant createdAt,
        String createdBy,
        Instant updatedAt,
        String updatedBy,
        long version) {

    public static final int MAX_NAME_LENGTH = 200;
    public static final int MAX_DATABASE_NAME_LENGTH = 128;
    public static final int MAX_DESCRIPTION_LENGTH = 1000;

    public DataSource {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(key, "key");
        name = TextRules.requireText(name, "name", MAX_NAME_LENGTH);
        Objects.requireNonNull(engine, "engine");
        databaseName = TextRules.requireText(databaseName, "databaseName", MAX_DATABASE_NAME_LENGTH);
        description = TextRules.optionalText(description, "description", MAX_DESCRIPTION_LENGTH);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(createdBy, "createdBy");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(updatedBy, "updatedBy");
    }

    public static DataSource register(DataSourceId id, TenantId tenantId, DataSourceKey key, String name,
                                      EngineCode engine, String databaseName, String description,
                                      String actor, Instant now) {
        return new DataSource(id, tenantId, key, name, engine, databaseName, description,
                DataSourceStatus.ACTIVE, now, actor, now, actor, 0);
    }

    /** Applies the changes; returns {@code this} when they do not modify anything. */
    public DataSource apply(DataSourceChanges changes, String actor, Instant now) {
        String newName = changes.name() == null ? name : TextRules.requireText(changes.name(), "name", MAX_NAME_LENGTH);
        String newDescription = changes.description() == null ? description
                : TextRules.optionalText(changes.description(), "description", MAX_DESCRIPTION_LENGTH);
        DataSourceStatus newStatus = changes.status() == null ? status : changes.status();
        if (newName.equals(name) && Objects.equals(newDescription, description) && newStatus == status) {
            return this;
        }
        return new DataSource(id, tenantId, key, newName, engine, databaseName, newDescription, newStatus,
                createdAt, createdBy, now, actor, version);
    }

    /** Names of the attributes that differ from {@code other} (status excluded), for auditing. */
    public List<String> changedDetailsComparedTo(DataSource other) {
        List<String> changed = new ArrayList<>();
        if (!name.equals(other.name)) {
            changed.add("name");
        }
        if (!Objects.equals(description, other.description)) {
            changed.add("description");
        }
        return changed;
    }

    public boolean isActive() {
        return status == DataSourceStatus.ACTIVE;
    }
}
