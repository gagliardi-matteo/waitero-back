package com.waitero.back.dto;

import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record DishUpsellPairDTO(
        Long baseDishId,
        String baseDishName,
        Long suggestedDishId,
        String suggestedDishName,
        BigDecimal affinityScore
) {
}
