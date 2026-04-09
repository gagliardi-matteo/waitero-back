package com.waitero.back.dto;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record RevenueKpiDTO(
        BigDecimal revenuePerUser,
        BigDecimal averageOrderValue,
        BigDecimal upsellRevenue,
        BigDecimal upsellShare,
        BigDecimal itemsPerOrder,
        long ordersWithUpsell,
        long ordersWithoutUpsell,
        BigDecimal avgWithUpsell,
        BigDecimal avgWithoutUpsell,
        BigDecimal uplift
) {
}