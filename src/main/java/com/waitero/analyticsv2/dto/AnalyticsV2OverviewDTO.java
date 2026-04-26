package com.waitero.analyticsv2.dto;

import java.math.BigDecimal;

public record AnalyticsV2OverviewDTO(
        long totalOrders,
        BigDecimal totalRevenue,
        BigDecimal averageOrderValue,
        BigDecimal itemsPerOrder
) {
}
