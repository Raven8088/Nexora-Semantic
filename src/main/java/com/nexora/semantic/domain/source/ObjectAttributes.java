package com.nexora.semantic.domain.source;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Observed, type-specific attributes of a physical object (e.g. data type and nullability of a column, primary key
 * of a table, columns and target of a foreign key). Values are limited to strings, integers, booleans and lists of
 * strings, kept in key order so the fingerprint is deterministic.
 */
public record ObjectAttributes(SortedMap<String, Object> values) {

    public static final ObjectAttributes NONE = new ObjectAttributes(new TreeMap<>());

    public ObjectAttributes {
        TreeMap<String, Object> copy = new TreeMap<>();
        values.forEach((key, value) -> copy.put(Objects.requireNonNull(key, "key"), normalize(key, value)));
        values = Collections.unmodifiableSortedMap(copy);
    }

    public static ObjectAttributes of(Map<String, ?> values) {
        TreeMap<String, Object> map = new TreeMap<>();
        values.forEach((key, value) -> {
            if (value != null) {
                map.put(key, value);
            }
        });
        return new ObjectAttributes(map);
    }

    /** SHA-256 over the canonical attributes and the comment: detects any observable change. */
    public String fingerprint(String comment) {
        StringBuilder canonical = new StringBuilder();
        values.forEach((key, value) -> canonical.append(key).append('=').append(canonicalValue(value)).append('\n'));
        canonical.append("#comment=").append(comment == null ? "" : comment.replace("\n", "\\n"));
        return sha256(canonical.toString());
    }

    static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String canonicalValue(Object value) {
        if (value instanceof List<?> list) {
            StringBuilder joined = new StringBuilder("[");
            list.forEach(item -> joined.append(NaturalKey.quote(item.toString())).append(','));
            return joined.append(']').toString();
        }
        return value instanceof String text ? NaturalKey.quote(text) : String.valueOf(value);
    }

    private static Object normalize(String key, Object value) {
        if (value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Integer || value instanceof Long || value instanceof Short) {
            return ((Number) value).longValue();
        }
        if (value instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            return List.copyOf(list);
        }
        throw new IllegalArgumentException("unsupported attribute value for key " + key);
    }
}
