package com.nexora.semantic.api.resolution;

import com.nexora.semantic.api.catalog.RelationshipDtos.ContractResponse;
import com.nexora.semantic.domain.catalog.Aggregation;
import com.nexora.semantic.domain.catalog.DefinitionKind;
import com.nexora.semantic.domain.catalog.DimensionType;
import com.nexora.semantic.domain.catalog.TimeGranularity;
import com.nexora.semantic.domain.catalog.TypeCategory;
import com.nexora.semantic.domain.resolution.FilterLiteral;
import com.nexora.semantic.domain.resolution.FilterOperator;
import com.nexora.semantic.domain.resolution.FilterValue;
import com.nexora.semantic.domain.resolution.ResolutionRequest;
import com.nexora.semantic.domain.resolution.SemanticResolution;
import com.nexora.semantic.domain.resolution.SemanticResolution.FilterStage;
import com.nexora.semantic.domain.resolution.SemanticResolver;
import com.nexora.semantic.domain.shared.InvalidValueException;
import com.nexora.semantic.domain.source.PhysicalObjectType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * HTTP contract of the semantic resolution (S4.1). Requests carry only persistent ids and typed values; unknown JSON
 * properties are rejected (strict global setting), so table names, column names or expressions cannot be smuggled in.
 */
public final class ResolutionDtos {

    private ResolutionDtos() {
    }

    /**
     * @param value  the single value of EQ, NE, GT, GTE, LT, LTE (a JSON string, number or boolean)
     * @param values the values of IN
     */
    public record FilterRequest(@NotNull UUID definitionId, @NotNull FilterOperator operator, JsonNode value,
                                @Size(max = SemanticResolver.MAX_IN_VALUES) List<JsonNode> values) {
    }

    /**
     * @param metrics                ids of APPROVED metrics (at least one; an empty list is reported as NO_METRICS)
     * @param dimensions             ids of APPROVED dimensions
     * @param filters                typed filters on dimensions or requested metrics
     * @param preferredRelationships ids of usable relationships reserved for S4.2 (validated, never selected here)
     */
    public record ResolutionRequestBody(
            @NotNull @Size(max = SemanticResolver.MAX_METRICS) List<@NotNull UUID> metrics,
            @Size(max = SemanticResolver.MAX_DIMENSIONS) List<@NotNull UUID> dimensions,
            @Size(max = SemanticResolver.MAX_FILTERS) List<@Valid @NotNull FilterRequest> filters,
            @Size(max = SemanticResolver.MAX_PREFERRED_RELATIONSHIPS) List<@NotNull UUID> preferredRelationships) {

        public ResolutionRequest toDomain() {
            List<ResolutionRequest.FilterRequest> domainFilters = new ArrayList<>();
            if (filters != null) {
                for (int i = 0; i < filters.size(); i++) {
                    FilterRequest f = filters.get(i);
                    String field = "filters[" + i + "]";
                    if (f.value() != null && f.values() != null) {
                        throw new InvalidValueException(field, "use either value or values, not both");
                    }
                    List<FilterLiteral> literals = new ArrayList<>();
                    if (f.value() != null) {
                        literals.add(literal(f.value(), field + ".value"));
                    }
                    if (f.values() != null) {
                        for (int j = 0; j < f.values().size(); j++) {
                            literals.add(literal(f.values().get(j), field + ".values[" + j + "]"));
                        }
                    }
                    domainFilters.add(new ResolutionRequest.FilterRequest(f.definitionId(), f.operator(), literals));
                }
            }
            return new ResolutionRequest(metrics, dimensions, domainFilters, preferredRelationships);
        }
    }

    /** JSON scalar to literal; text stays text (never parsed as an expression). */
    static FilterLiteral literal(JsonNode node, String field) {
        if (node == null || node.isNull()) {
            return new FilterLiteral.Null();
        }
        if (node.isString()) {
            return new FilterLiteral.Text(node.asString());
        }
        if (node.isBoolean()) {
            return new FilterLiteral.Bool(node.asBoolean());
        }
        if (node.isIntegralNumber()) {
            return new FilterLiteral.Number(new BigDecimal(node.bigIntegerValue()));
        }
        if (node.isNumber()) {
            return new FilterLiteral.Number(new BigDecimal(node.asString()));
        }
        throw new InvalidValueException(field, "must be a JSON string, number, boolean or null");
    }

    public record ConceptResponse(UUID id, String key, String name, long version, UUID relationObjectId) {
    }

    public record MetricResponse(UUID id, String key, String name, long version, UUID conceptId,
                                 Aggregation aggregation, UUID columnObjectId) {
    }

    public record DimensionResponse(UUID id, String key, String name, long version, UUID conceptId,
                                    UUID columnObjectId, DimensionType type, TypeCategory valueType,
                                    List<TimeGranularity> granularities, String timeZone) {
    }

    /** @param values canonical strings (ISO-8601 for temporal values), typed by {@code valueType} */
    public record FilterResponse(UUID definitionId, DefinitionKind definitionKind, FilterStage stage,
                                 FilterOperator operator, TypeCategory valueType, UUID columnObjectId,
                                 List<String> values) {
    }

    public record DependencyResponse(UUID objectId, PhysicalObjectType type, String naturalKey, String fingerprint,
                                     UUID lastSeenSnapshotId, String dataType) {
    }

    /**
     * Typed semantic resolution. It is not a plan: no SQL, no JOIN, no route; {@code preferredRelationships} are only
     * validated. {@code fingerprint} changes whenever any revision observed changes.
     */
    public record ResolutionResponse(
            UUID dataSourceId,
            UUID metadataSnapshotId,
            List<MetricResponse> metrics,
            List<DimensionResponse> dimensions,
            List<DimensionResponse> filterDimensions,
            List<ConceptResponse> concepts,
            List<FilterResponse> filters,
            List<DependencyResponse> physicalDependencies,
            List<ContractResponse> preferredRelationships,
            String fingerprint) {

        static ResolutionResponse from(SemanticResolution r) {
            return new ResolutionResponse(r.dataSourceId().value(), r.metadataSnapshotId(),
                    r.metrics().stream().map(m -> new MetricResponse(m.id(), m.key(), m.name(), m.version(),
                            m.conceptId(), m.aggregation(), m.columnObjectId())).toList(),
                    r.dimensions().stream().map(d -> new DimensionResponse(d.id(), d.key(), d.name(), d.version(),
                            d.conceptId(), d.columnObjectId(), d.type(), d.valueType(), d.granularities(),
                            d.timeZone())).toList(),
                    r.filterDimensions().stream().map(d -> new DimensionResponse(d.id(), d.key(), d.name(),
                            d.version(), d.conceptId(), d.columnObjectId(), d.type(), d.valueType(),
                            d.granularities(), d.timeZone())).toList(),
                    r.concepts().stream().map(c -> new ConceptResponse(c.id(), c.key(), c.name(), c.version(),
                            c.relationObjectId())).toList(),
                    r.filters().stream().map(f -> new FilterResponse(f.definitionId(), f.definitionKind(), f.stage(),
                            f.operator(), f.valueType(), f.columnObjectId(),
                            f.values().stream().map(FilterValue::canonical).toList())).toList(),
                    r.physicalDependencies().stream().map(o -> new DependencyResponse(o.objectId(), o.type(),
                            o.naturalKey(), o.fingerprint(), o.lastSeenSnapshotId(), o.dataType())).toList(),
                    r.preferredRelationships().stream().map(ContractResponse::from).toList(),
                    r.fingerprint());
        }
    }
}
