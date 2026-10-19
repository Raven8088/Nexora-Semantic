package com.nexora.semantic.domain.source;

public enum DataSourceStatus {
    ACTIVE,
    /** Kept for history and auditing; data sources are never deleted. */
    ARCHIVED
}
