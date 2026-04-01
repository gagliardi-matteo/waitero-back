package com.waitero.back.dto;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record RevenueOpportunityDTO(
        Long dishId,
        String dishName,
        String category,
        BigDecimal currentPrice,
        BigDecimal suggestedPrice,
        String opportunityType,
        String title,
        String rationale,
        String actionLabel,
        BigDecimal revenueScore
) {
}
