package com.nexora.semantic.api.source;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Limits of the Discovery metadata import endpoint.
 *
 * @param maxImportBytes maximum size of an import document; larger requests get 413 without being buffered
 */
@Validated
@ConfigurationProperties("nexora.semantic.discovery")
public record ImportProperties(
        @DefaultValue("16777216") @Min(1024) @Max(268_435_456) int maxImportBytes) {
}
