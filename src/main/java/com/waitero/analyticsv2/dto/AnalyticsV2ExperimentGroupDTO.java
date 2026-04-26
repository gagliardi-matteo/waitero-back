package com.waitero.analyticsv2.dto;

import java.math.BigDecimal;

public record AnalyticsV2ExperimentGroupDTO(
        long orders,
        BigDecimal totalRevenue,
        BigDecimal averageOrderValue,
        BigDecimal itemsPerOrder
) {
}
