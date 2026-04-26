package com.waitero.back.dto;

import lombok.Builder;

@Builder
public record InsightDTO(
        String type,
        Long dishId,
        Long targetDishId,
        String message
) {
}
