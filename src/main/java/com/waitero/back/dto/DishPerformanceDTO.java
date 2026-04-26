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
        long impressions,
        long clicks,
        long addToCart,
        long orderCount,
        BigDecimal viewToCartRate,
        BigDecimal ctr,
        BigDecimal revenuePerImpression,
        BigDecimal viewToOrderRate,
        long recentViews,
        long previousViews,
        long recentOrderCount,
        long previousOrderCount,
        BigDecimal recentViewToOrderRate,
        BigDecimal previousViewToOrderRate,
        BigDecimal trendDelta,
        String trendDirection,
        String performanceLabel
) {
}
