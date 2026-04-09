package com.waitero.back.dto;

import lombok.Builder;

@Builder
public record ExperimentDecision(
        Long restaurantId,
        String winningVariant,
        double upliftRevenue,
        double upliftAOV,
        double upliftItems,
        double upliftPercent,
        double confidence,
        int sampleSize,
        boolean isSignificant,
        String recommendation
) {
}