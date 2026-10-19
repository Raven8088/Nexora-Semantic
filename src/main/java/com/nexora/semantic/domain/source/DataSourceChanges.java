package com.nexora.semantic.domain.source;

/**
 * Requested changes to a data source. A {@code null} field keeps the current value; an empty
 * {@code description} removes it. Key, engine and database name are immutable and cannot be changed.
 */
public record DataSourceChanges(String name, String description, DataSourceStatus status) {

    public static final DataSourceChanges NONE = new DataSourceChanges(null, null, null);
}
