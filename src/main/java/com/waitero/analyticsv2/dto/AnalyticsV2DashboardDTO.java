package com.waitero.analyticsv2.dto;

import java.util.List;

public record AnalyticsV2DashboardDTO(
        AnalyticsV2OverviewDTO overview,
        List<AnalyticsV2DishMetricsDTO> dishes
) {
}
