package com.waitero.back.dto;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record ExperimentVariantMetricsDTO(
        long orders,
        BigDecimal revenue,
        BigDecimal avgOrderValue,
        BigDecimal itemsPerOrder
) {
}