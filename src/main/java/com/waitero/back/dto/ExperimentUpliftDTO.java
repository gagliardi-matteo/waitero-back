package com.waitero.back.dto;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record ExperimentUpliftDTO(
        BigDecimal revenue,
        BigDecimal avgOrderValue,
        BigDecimal itemsPerOrder
) {
}