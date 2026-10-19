package com.nexora.semantic.api.web;

import com.nexora.semantic.domain.shared.Page;

import java.util.List;
import java.util.function.Function;

/** JSON envelope of a page of results. */
public record PageResponse<T>(List<T> items, int page, int size, long totalItems) {

    public static <D, T> PageResponse<T> of(Page<D> page, Function<? super D, ? extends T> mapper) {
        return new PageResponse<>(page.items().stream().<T>map(mapper).toList(), page.page(), page.size(),
                page.totalItems());
    }
}
