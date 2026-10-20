package com.nexora.semantic.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Real Nexora Discovery responses captured from the Discovery 0.1.0-SNAPSHOT jar against its own PostgreSQL 17.6
 * test schema (see {@code src/test/resources/discovery/README.md}), wrapped in Semantic's import envelope without
 * altering the Discovery bytes.
 */
public final class DiscoveryFixtures {

    /** All accessible schemas: inventory, sales (restricted is silently omitted by Discovery: no USAGE). */
    public static final String ALL_SCHEMAS = "erp-all-schemas";
    /** Same database after: +sales.customers.segment, products.name varchar(150→200), comment on sales.orders,
     *  +sales.refunds (3 columns, 1 FK), -sales.v_order_summary. */
    public static final String ALL_SCHEMAS_V2 = "erp-all-schemas-v2";
    /** Discovery request with schemas ["sales"]. */
    public static final String SALES = "erp-sales";
    /** Discovery request with schemas ["sales", "restricted"]: restricted omitted by Discovery. */
    public static final String SALES_RESTRICTED = "erp-sales-restricted";

    private DiscoveryFixtures() {
    }

    public static String payload(String fixture) {
        try (InputStream in = DiscoveryFixtures.class.getResourceAsStream("/discovery/" + fixture + ".json")) {
            if (in == null) {
                throw new IllegalArgumentException("unknown fixture " + fixture);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Envelope for a full import (every accessible schema). */
    public static String envelope(String fixture) {
        return envelopeOf(payload(fixture), List.of());
    }

    public static String envelope(String fixture, List<String> requestedSchemas) {
        return envelopeOf(payload(fixture), requestedSchemas);
    }

    public static String envelopeOf(String payloadJson, List<String> requestedSchemas) {
        String requested = requestedSchemas.isEmpty() ? ""
                : ",\"requestedSchemas\":" + requestedSchemas.stream().map(s -> "\"" + s + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        return "{\"contractVersion\":\"discovery-api/0.1\",\"discoveredAt\":\"2026-10-19T17:13:00Z\"" + requested
                + ",\"payload\":" + payloadJson + "}";
    }

    public static byte[] bytes(String json) {
        return json.getBytes(StandardCharsets.UTF_8);
    }
}
