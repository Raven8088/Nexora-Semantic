package com.nexora.semantic.domain.shared;

/**
 * Offset pagination request.
 *
 * @param page zero-based page number
 * @param size page size, between 1 and {@link #MAX_SIZE}
 */
public record PageQuery(int page, int size) {

    public static final int MAX_SIZE = 200;
    public static final int DEFAULT_SIZE = 50;

    public PageQuery {
        if (page < 0) {
            throw new InvalidValueException("page", "must be >= 0");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new InvalidValueException("size", "must be between 1 and " + MAX_SIZE);
        }
    }

    public long offset() {
        return (long) page * size;
    }
}
