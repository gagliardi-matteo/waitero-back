package com.waitero.back.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;

@Builder
public record ExperimentAnalysisDTO(
        Long restaurantId,
        LocalDate dateFrom,
        LocalDate dateTo,
        @JsonProperty("A") ExperimentVariantPerformanceDTO variantA,
        @JsonProperty("B") ExperimentVariantPerformanceDTO variantB,
        @JsonProperty("C") ExperimentVariantPerformanceDTO variantC,
        String currentMode,
        String targetMode,
        String suggestedWinner,
        BigDecimal upliftVsBaseline,
        boolean sufficientData,
        boolean stable,
        String reason,
        String action
) {
}
