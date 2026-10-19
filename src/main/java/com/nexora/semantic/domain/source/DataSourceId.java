package com.nexora.semantic.domain.source;

import java.util.Objects;
import java.util.UUID;

public record DataSourceId(UUID value) {

    public DataSourceId {
        Objects.requireNonNull(value, "value");
    }

    public static DataSourceId newId() {
        return new DataSourceId(UUID.randomUUID());
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
