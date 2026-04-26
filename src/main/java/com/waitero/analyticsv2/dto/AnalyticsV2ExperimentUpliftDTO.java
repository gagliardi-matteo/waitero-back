package com.waitero.analyticsv2.dto;

import java.math.BigDecimal;

public record AnalyticsV2ExperimentUpliftDTO(
        BigDecimal revenueDelta,
        BigDecimal revenuePct,
        BigDecimal averageOrderValueDelta,
        BigDecimal averageOrderValuePct,
        BigDecimal itemsPerOrderDelta,
        BigDecimal itemsPerOrderPct
) {
}
