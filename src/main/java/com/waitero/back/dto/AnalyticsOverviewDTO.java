package com.waitero.back.dto;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record AnalyticsOverviewDTO(
        long views,
        long orders,
        long sessions,
        @Deprecated
        BigDecimal conversionRate,
        @Deprecated
        BigDecimal dropoffRate,
        BigDecimal averageOrderValue,
        long impressions,
        BigDecimal ctr,
        BigDecimal revenuePerImpression
) {
}