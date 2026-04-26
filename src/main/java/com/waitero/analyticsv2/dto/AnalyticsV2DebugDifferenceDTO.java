package com.waitero.analyticsv2.dto;

public record AnalyticsV2DebugDifferenceDTO(
        Long dishId,
        String dishName,
        Integer v1Position,
        Integer v2Position,
        String differenceType
) {
}