package com.waitero.back.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

@Builder
public record ExperimentMetricsDTO(
        @JsonProperty("A") ExperimentVariantMetricsDTO variantA,
        @JsonProperty("B") ExperimentVariantMetricsDTO variantB,
        ExperimentUpliftDTO uplift
) {
}