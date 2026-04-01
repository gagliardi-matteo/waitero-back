package com.waitero.back.dto;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record DishPerformanceDTO(
        Long dishId,
        String dishName,
        String category,
        BigDecimal price,
        long views,
        long clicks,
        long addToCart,
        long orderCount,
        BigDecimal viewToCartRate,
        BigDecimal viewToOrderRate,
        String performanceLabel
) {
}
