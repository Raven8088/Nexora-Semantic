package com.nexora.semantic.domain.shared;

import java.util.List;
import java.util.function.Function;

/** One page of results plus the total number of matching items. */
public record Page<T>(List<T> items, int page, int size, long totalItems) {

    public Page {
        items = items == null ? List.of() : List.copyOf(items);
    }

    public <R> Page<R> map(Function<? super T, ? extends R> mapper) {
        return new Page<>(items.stream().<R>map(mapper).toList(), page, size, totalItems);
    }
}
