package com.nexora.semantic.api.source;

import com.nexora.semantic.domain.source.DataSource;
import com.nexora.semantic.domain.source.DataSourceStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.UUID;

/**
 * HTTP contract of data sources. There are no connection fields, and unknown JSON properties are rejected, so
 * credentials cannot be submitted. The domain re-validates every value.
 */
public final class DataSourceDtos {

    private DataSourceDtos() {
    }

    public record CreateDataSourceRequest(
            @NotBlank @Size(max = 63) @Pattern(regexp = "[a-z0-9][a-z0-9_-]{1,62}") String key,
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,32}") String engine,
            @NotBlank @Size(max = 128) String databaseName,
            @Size(max = 1000) String description) {
    }

    /**
     * Partial update: absent (null) fields are unchanged; an empty {@code description} removes it.
     * Key, engine and database name are immutable and therefore not part of this contract.
     */
    public record UpdateDataSourceRequest(
            @Size(min = 1, max = 200) String name,
            @Size(max = 1000) String description,
            DataSourceStatus status) {
    }

    public record DataSourceResponse(
            UUID id,
            String key,
            String name,
            String engine,
            String databaseName,
            String description,
            DataSourceStatus status,
            Instant createdAt,
            String createdBy,
            Instant updatedAt,
            String updatedBy,
            long version) {

        static DataSourceResponse from(DataSource ds) {
            return new DataSourceResponse(ds.id().value(), ds.key().value(), ds.name(), ds.engine().value(),
                    ds.databaseName(), ds.description(), ds.status(), ds.createdAt(), ds.createdBy(),
                    ds.updatedAt(), ds.updatedBy(), ds.version());
        }
    }
}
