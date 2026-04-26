package com.waitero.analyticsv2.dto;

public record AnalyticsV2DebugDishPositionDTO(
        int position,
        Long dishId,
        String dishName,
        String category
) {
}