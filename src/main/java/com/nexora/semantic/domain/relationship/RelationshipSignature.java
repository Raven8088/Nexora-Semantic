package com.nexora.semantic.domain.relationship;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Canonical identity of the join a relationship describes, independent of pair order and of direction:
 * {@code a.x = b.y} and {@code b.y = a.x} are the same join. Two relationships with the same signature are
 * equivalent; only one of them may be APPROVED at a time (enforced by a unique index in the database).
 */
public final class RelationshipSignature {

    private RelationshipSignature() {
    }

    /** SHA-256 (hex) of the smaller of the two oriented canonical forms. */
    public static String of(UUID sourceObjectId, UUID targetObjectId, List<ColumnPair> pairs) {
        String forward = canonical(sourceObjectId, targetObjectId, pairs.stream()
                .map(p -> p.sourceColumnId() + "=" + p.targetColumnId()).toList());
        String reverse = canonical(targetObjectId, sourceObjectId, pairs.stream()
                .map(p -> p.targetColumnId() + "=" + p.sourceColumnId()).toList());
        return sha256(forward.compareTo(reverse) <= 0 ? forward : reverse);
    }

    private static String canonical(UUID from, UUID to, List<String> equalities) {
        return from + ">" + to + ":" + equalities.stream().sorted().collect(Collectors.joining(","));
    }

    private static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
