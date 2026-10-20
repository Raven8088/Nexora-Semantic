package com.nexora.semantic.domain.source;

import com.nexora.semantic.domain.shared.DomainException;

import java.util.List;
import java.util.Map;

/**
 * An imported Discovery payload cannot be accepted. {@link #violations()} name JSON paths and rules, never values
 * from the payload (which is untrusted and may contain sensitive text).
 */
public class InvalidSnapshotException extends DomainException {

    public enum Kind {
        /** Not parseable as JSON. */
        MALFORMED,
        /** Parseable, but violates the import contract. */
        INVALID,
        /** The envelope declares a contract version this Semantic release does not support. */
        UNSUPPORTED_CONTRACT_VERSION
    }

    private final Kind kind;
    private final List<Map<String, String>> violations;

    public InvalidSnapshotException(Kind kind, String message, List<Map<String, String>> violations) {
        super(message);
        this.kind = kind;
        this.violations = List.copyOf(violations);
    }

    public Kind kind() {
        return kind;
    }

    /** Entries with {@code field} (JSON path) and {@code message}. */
    public List<Map<String, String>> violations() {
        return violations;
    }
}
