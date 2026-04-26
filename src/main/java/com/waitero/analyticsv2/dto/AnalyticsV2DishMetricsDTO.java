package com.waitero.analyticsv2.dto;

import java.math.BigDecimal;

public record AnalyticsV2DishMetricsDTO(
        Long dishId,
        String dishName,
        String description,
        String category,
        BigDecimal currentPrice,
        Boolean available,
        String imageUrl,
        long orderCount,
        long quantitySold,
        BigDecimal revenuePerDish,
        BigDecimal orderFrequencyPerDish
) {
}
