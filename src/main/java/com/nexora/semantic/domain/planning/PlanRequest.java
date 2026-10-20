package com.nexora.semantic.domain.planning;

import com.nexora.semantic.domain.catalog.TimeGranularity;
import com.nexora.semantic.domain.resolution.ResolutionRequest;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * S4.2 request: the S4.1 request unchanged plus the only information the catalog cannot supply, the time bucket
 * of each requested TEMPORAL dimension (among the granularities that dimension allows). Nothing is assumed when a
 * granularity is missing.
 */
public record PlanRequest(ResolutionRequest resolution, List<GranularityChoice> timeGranularities) {

    public record GranularityChoice(UUID dimensionId, TimeGranularity granularity) {
        public GranularityChoice {
            Objects.requireNonNull(dimensionId, "dimensionId");
            Objects.requireNonNull(granularity, "granularity");
        }
    }

    public PlanRequest {
        Objects.requireNonNull(resolution, "resolution");
        timeGranularities = timeGranularities == null ? List.of() : List.copyOf(timeGranularities);
    }
}
