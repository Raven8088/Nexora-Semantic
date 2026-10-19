package com.nexora.semantic.api.web;

import com.nexora.semantic.domain.shared.StaleResourceException;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strong ETags built from the optimistic locking version, e.g. {@code "3"}. */
public final class ETags {

    private static final Pattern STRONG = Pattern.compile("\"(\\d{1,18})\"");

    private ETags() {
    }

    public static String of(long version) {
        return "\"" + version + "\"";
    }

    /**
     * Expected version from an {@code If-Match} header.
     *
     * @throws PreconditionRequiredException if the header is missing
     * @throws StaleResourceException        if it is not a single strong ETag produced by this API, which can
     *                                       therefore never match the current version
     */
    public static long expectedVersion(String ifMatch, String resource) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new PreconditionRequiredException();
        }
        Matcher matcher = STRONG.matcher(ifMatch.strip());
        if (!matcher.matches()) {
            throw new StaleResourceException(resource);
        }
        return Long.parseLong(matcher.group(1));
    }
}
