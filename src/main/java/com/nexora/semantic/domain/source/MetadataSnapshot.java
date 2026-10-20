package com.nexora.semantic.domain.source;

import com.nexora.semantic.domain.shared.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable record of one import of Discovery metadata into a data source: what was imported (scope, content hash)
 * and what it changed. Stored append-only; together with the objects' first/last-seen references it is the import
 * history.
 *
 * @param requestedSchemas schemas requested from Discovery; empty means every accessible schema
 * @param contentSha256    hash of the normalized observations (independent of JSON formatting and ordering)
 */
public record MetadataSnapshot(
        UUID id,
        TenantId tenantId,
        DataSourceId dataSourceId,
        String contractVersion,
        EngineCode engine,
        String databaseName,
        String productVersion,
        Instant discoveredAt,
        Set<String> requestedSchemas,
        String contentSha256,
        Outcome outcome,
        Summary summary,
        String importedBy,
        Instant importedAt) {

    public enum Outcome {
        /** The import changed at least one object. */
        APPLIED,
        /** Everything matched the stored metadata; only last-seen references were refreshed. */
        UNCHANGED
    }

    /**
     * @param observed           objects present in the payload
     * @param unavailableSchemas schemas in scope but absent from the payload (possibly a privilege loss)
     */
    public record Summary(int observed, int added, int updated, int unchanged, int missing, int reappeared,
                          List<String> unavailableSchemas) {
        public Summary {
            unavailableSchemas = List.copyOf(unavailableSchemas);
        }
    }

    public MetadataSnapshot {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(dataSourceId, "dataSourceId");
        Objects.requireNonNull(contractVersion, "contractVersion");
        Objects.requireNonNull(engine, "engine");
        Objects.requireNonNull(databaseName, "databaseName");
        requestedSchemas = Set.copyOf(requestedSchemas);
        Objects.requireNonNull(contentSha256, "contentSha256");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(summary, "summary");
        Objects.requireNonNull(importedBy, "importedBy");
        Objects.requireNonNull(importedAt, "importedAt");
    }
}
