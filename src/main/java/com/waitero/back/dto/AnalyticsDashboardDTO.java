package com.waitero.back.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class AnalyticsDashboardDTO {
    private AnalyticsOverviewDTO overview;
    private List<DishPerformanceDTO> dishPerformance;
    private List<RevenueOpportunityDTO> revenueOpportunities;
    private List<BenchmarkInsightDTO> benchmarkInsights;
}
