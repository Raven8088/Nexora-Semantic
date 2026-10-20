package com.nexora.semantic.application.port;

import com.nexora.semantic.domain.source.DiscoveredDatabase;

/**
 * Anti-corruption port: turns a raw import document (Semantic's envelope around a Nexora Discovery response) into
 * Semantic's own model. Implementations are tolerant to unknown fields of the Discovery payload and validate its
 * structure.
 */
public interface DiscoveredMetadataReader {

    /** @throws com.nexora.semantic.domain.source.InvalidSnapshotException if the document cannot be accepted */
    DiscoveredDatabase read(byte[] document);
}
