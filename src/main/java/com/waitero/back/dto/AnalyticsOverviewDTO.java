package com.waitero.back.dto;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record AnalyticsOverviewDTO(
        long views,
        long orders,
        long sessions,
        BigDecimal conversionRate,
        BigDecimal dropoffRate,
        BigDecimal averageOrderValue
) {
}
