package com.waitero.analyticsv2.dto;

import java.math.BigDecimal;

public record UpsellSuggestionV2DTO(
        Long dishId,
        String dishName,
        String description,
        String category,
        BigDecimal price,
        String imageUrl,
        long supportingDishCount,
        long pairOrderCount,
        BigDecimal affinity,
        BigDecimal lift,
        String rationale
) {
}
