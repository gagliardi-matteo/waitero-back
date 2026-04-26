package com.waitero.analyticsv2.dto;

import java.math.BigDecimal;

public record RelatedDishV2DTO(
        Long dishId,
        String dishName,
        String description,
        String category,
        BigDecimal price,
        Boolean available,
        String imageUrl,
        long pairOrderCount,
        long relatedDishOrderCount,
        BigDecimal affinity,
        BigDecimal lift
) {
}
