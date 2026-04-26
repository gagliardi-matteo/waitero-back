package com.waitero.back.dto;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record ExperimentVariantPerformanceDTO(
        String variant,
        BigDecimal totalRevenue,
        long totalOrders,
        long totalSessions,
        BigDecimal rps,
        BigDecimal aov,
        BigDecimal cr,
        long activeDays
) {
}
