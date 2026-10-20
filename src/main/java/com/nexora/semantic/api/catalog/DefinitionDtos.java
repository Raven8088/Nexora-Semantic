package com.nexora.semantic.api.catalog;

import com.nexora.semantic.application.catalog.SemanticCatalogService.EvaluatedDefinition;
import com.nexora.semantic.domain.catalog.Aggregation;
import com.nexora.semantic.domain.catalog.DefinitionKind;
import com.nexora.semantic.domain.catalog.DefinitionOrigin;
import com.nexora.semantic.domain.catalog.DefinitionSpec;
import com.nexora.semantic.domain.catalog.DefinitionStatus;
import com.nexora.semantic.domain.catalog.DimensionType;
import com.nexora.semantic.domain.catalog.SemanticDefinition;
import com.nexora.semantic.domain.catalog.TimeGranularity;
import com.nexora.semantic.domain.shared.InvalidValueException;
import com.nexora.semantic.domain.source.PhysicalObject;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * HTTP contract of the semantic catalog. A definition carries exactly one block matching its kind
 * ({@code concept}, {@code metric} or {@code dimension}); references are ids of physical objects (S2) and of
 * concepts. Unknown JSON properties are rejected (strict global setting).
 */
public final class DefinitionDtos {

    private DefinitionDtos() {
    }

    public record ConceptBlock(@NotNull UUID relationId) {
    }

    /** @param columnId optional for COUNT (counts rows), required for SUM and AVG */
    public record MetricBlock(@NotNull UUID conceptId, @NotNull Aggregation aggregation, UUID columnId) {
    }

    /**
     * @param granularities TEMPORAL only; all of DAY…YEAR when omitted
     * @param timeZone      TEMPORAL on timestamptz only; IANA id, {@code UTC} when omitted (decision D14)
     */
    public record DimensionBlock(@NotNull UUID conceptId, @NotNull UUID columnId, @NotNull DimensionType type,
                                 @Size(max = 5) Set<TimeGranularity> granularities, @Size(max = 64) String timeZone) {
    }

    public record CreateDefinitionRequest(
            @NotNull DefinitionKind kind,
            @NotBlank @Size(max = 63) @Pattern(regexp = "[a-z0-9][a-z0-9_-]{1,62}") String key,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @Valid ConceptBlock concept,
            @Valid MetricBlock metric,
            @Valid DimensionBlock dimension) {
    }

    /** Full replacement of the mutable content; kind and key are immutable. */
    public record UpdateDefinitionRequest(
            @NotBlank @Size(max = 200) String name,
            @Size(max = 2000) String description,
            @Valid ConceptBlock concept,
            @Valid MetricBlock metric,
            @Valid DimensionBlock dimension) {
    }

    public record DeactivateRequest(@NotBlank @Size(max = 500) String reason) {
    }

    /** Builds the domain spec from the single block that matches {@code kind}. */
    static DefinitionSpec spec(DefinitionKind kind, ConceptBlock concept, MetricBlock metric, DimensionBlock dimension) {
        int blocks = (concept == null ? 0 : 1) + (metric == null ? 0 : 1) + (dimension == null ? 0 : 1);
        if (blocks != 1) {
            throw new InvalidValueException(kind == null ? "spec" : kind.name().toLowerCase(Locale.ROOT),
                    "exactly one of concept, metric or dimension is required");
        }
        DefinitionKind given = concept != null ? DefinitionKind.CONCEPT
                : metric != null ? DefinitionKind.METRIC : DefinitionKind.DIMENSION;
        if (kind != null && given != kind) {
            throw new InvalidValueException(given.name().toLowerCase(Locale.ROOT), "does not match kind " + kind);
        }
        return switch (given) {
            case CONCEPT -> new DefinitionSpec.ConceptSpec(concept.relationId());
            case METRIC -> new DefinitionSpec.MetricSpec(metric.conceptId(), metric.aggregation(), metric.columnId());
            case DIMENSION -> new DefinitionSpec.DimensionSpec(dimension.conceptId(), dimension.columnId(),
                    dimension.type(), dimension.granularities(), timeZone(dimension.timeZone()));
        };
    }

    private static ZoneId timeZone(String value) {
        if (value == null) {
            return null;
        }
        if (!"UTC".equals(value) && !ZoneId.getAvailableZoneIds().contains(value)) {
            throw new InvalidValueException("dimension.timeZone", "must be an IANA time zone id");
        }
        return ZoneId.of(value);
    }

    public record IssueResponse(String code, String field, String message) {
    }

    public record ConceptResponse(UUID relationId, String relationNaturalKey) {
    }

    public record MetricResponse(UUID conceptId, Aggregation aggregation, UUID columnId, String columnNaturalKey) {
    }

    public record DimensionResponse(UUID conceptId, UUID columnId, String columnNaturalKey, DimensionType type,
                                    List<TimeGranularity> granularities, String timeZone) {
    }

    /**
     * @param usable APPROVED and without issues: usable by query resolution (S4)
     * @param issues why the definition cannot be approved or used with the current physical metadata
     */
    public record DefinitionResponse(
            UUID id,
            UUID dataSourceId,
            DefinitionKind kind,
            String key,
            String name,
            String description,
            DefinitionStatus status,
            DefinitionOrigin origin,
            boolean usable,
            List<IssueResponse> issues,
            ConceptResponse concept,
            MetricResponse metric,
            DimensionResponse dimension,
            String createdBy,
            Instant createdAt,
            String updatedBy,
            Instant updatedAt,
            String approvedBy,
            Instant approvedAt,
            String inactiveReason,
            long version) {

        static DefinitionResponse from(EvaluatedDefinition evaluated) {
            SemanticDefinition d = evaluated.definition();
            Map<UUID, PhysicalObject> refs = evaluated.references();
            ConceptResponse concept = null;
            MetricResponse metric = null;
            DimensionResponse dimension = null;
            switch (d.spec()) {
                case DefinitionSpec.ConceptSpec c -> concept = new ConceptResponse(c.relationId(),
                        naturalKey(refs, c.relationId()));
                case DefinitionSpec.MetricSpec m -> metric = new MetricResponse(m.conceptId(), m.aggregation(),
                        m.columnId(), m.columnId() == null ? null : naturalKey(refs, m.columnId()));
                case DefinitionSpec.DimensionSpec dim -> dimension = new DimensionResponse(dim.conceptId(),
                        dim.columnId(), naturalKey(refs, dim.columnId()), dim.type(),
                        dim.granularities().stream().sorted().toList(),
                        dim.timeZone() == null ? null : dim.timeZone().getId());
            }
            return new DefinitionResponse(d.id(), d.dataSourceId().value(), d.kind(), d.key().value(), d.name(),
                    d.description(), d.status(), d.origin(), evaluated.usable(),
                    evaluated.issues().stream().map(i -> new IssueResponse(i.code().name(), i.field(), i.message()))
                            .toList(),
                    concept, metric, dimension, d.createdBy().id(), d.createdAt(), d.updatedBy().id(), d.updatedAt(),
                    d.approvedBy(), d.approvedAt(), d.inactiveReason(), d.version());
        }

        private static String naturalKey(Map<UUID, PhysicalObject> refs, UUID id) {
            PhysicalObject object = refs.get(id);
            return object == null ? null : object.naturalKey().value();
        }
    }
}
