package com.waitero.analyticsv2.dto;

import java.math.BigDecimal;

public record MenuRankedDishV2DTO(
        Long dishId,
        String dishName,
        String description,
        String category,
        BigDecimal price,
        String imageUrl,
        long orderCount,
        long quantitySold,
        BigDecimal revenuePerDish,
        BigDecimal orderFrequencyPerDish,
        BigDecimal coOccurrenceBoost,
        BigDecimal rankingScore
) {
}
