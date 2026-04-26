package com.waitero.analyticsv2.dto;

import java.math.BigDecimal;

public record AnalyticsV2ExperimentSignificanceDTO(
        String metric,
        String method,
        BigDecimal zScore,
        BigDecimal pValue,
        boolean statisticallySignificant,
        boolean sufficientSample
) {
}
