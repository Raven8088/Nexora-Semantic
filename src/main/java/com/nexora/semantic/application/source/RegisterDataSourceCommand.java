package com.nexora.semantic.application.source;

/**
 * Input for registering a data source. Intentionally has no connection fields: Semantic never stores hosts,
 * users or passwords.
 */
public record RegisterDataSourceCommand(
        String key,
        String name,
        String engine,
        String databaseName,
        String description) {
}
