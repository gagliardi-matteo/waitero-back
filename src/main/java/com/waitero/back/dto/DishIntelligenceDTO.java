package com.waitero.back.dto;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;

@Builder
public record DishIntelligenceDTO(
        Long dishId,
        String name,
        BigDecimal score,
        BigDecimal rpi,
        BigDecimal ctr,
        BigDecimal orderRate,
        BigDecimal affinityScore,
        BigDecimal explorationBoost,
        String performanceCategory,
        List<String> insights
) {
}
