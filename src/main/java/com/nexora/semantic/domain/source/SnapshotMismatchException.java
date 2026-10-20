package com.nexora.semantic.domain.source;

import com.nexora.semantic.domain.shared.DomainException;

/** The payload describes another database (engine or database name differ from the data source). */
public class SnapshotMismatchException extends DomainException {

    private final String field;

    public SnapshotMismatchException(String field) {
        super("Snapshot does not belong to this data source");
        this.field = field;
    }

    public String field() {
        return field;
    }
}
