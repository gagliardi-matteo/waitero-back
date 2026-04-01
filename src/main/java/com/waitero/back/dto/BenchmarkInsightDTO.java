package com.waitero.back.dto;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record BenchmarkInsightDTO(
        Long dishId,
        String dishName,
        String category,
        long views,
        long orderCount,
        BigDecimal viewToCartRate,
        BigDecimal viewToOrderRate,
        BigDecimal categoryViewToCartRate,
        BigDecimal categoryViewToOrderRate,
        BigDecimal restaurantViewToOrderRate,
        String benchmarkLabel,
        String title,
        String rationale,
        String actionLabel,
        BigDecimal benchmarkScore
) {
}
