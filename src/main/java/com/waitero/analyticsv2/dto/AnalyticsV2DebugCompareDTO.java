package com.waitero.analyticsv2.dto;

import java.time.LocalDate;
import java.util.List;

public record AnalyticsV2DebugCompareDTO(
        Long restaurantId,
        Long dishId,
        LocalDate dateFrom,
        LocalDate dateTo,
        List<AnalyticsV2DebugDishPositionDTO> rankingV1,
        List<AnalyticsV2DebugDishPositionDTO> rankingV2,
        List<AnalyticsV2DebugDifferenceDTO> rankingDifferences,
        List<AnalyticsV2DebugDishPositionDTO> upsellV1,
        List<AnalyticsV2DebugDishPositionDTO> upsellV2,
        List<AnalyticsV2DebugDifferenceDTO> upsellDifferences,
        List<String> notes
) {
}