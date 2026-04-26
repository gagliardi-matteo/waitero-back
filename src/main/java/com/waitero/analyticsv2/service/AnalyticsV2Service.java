package com.waitero.analyticsv2.service;

import com.waitero.analyticsv2.dto.AnalyticsV2DashboardDTO;
import com.waitero.analyticsv2.dto.AnalyticsV2DishMetricsDTO;
import com.waitero.analyticsv2.dto.AnalyticsV2OverviewDTO;
import com.waitero.analyticsv2.repository.AnalyticsV2MetricsRepository;
import com.waitero.analyticsv2.support.AnalyticsV2TimeRange;
import com.waitero.analyticsv2.support.DecimalScaleV2;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AnalyticsV2Service {

    private final AnalyticsV2MetricsRepository metricsRepository;

    public AnalyticsV2OverviewDTO getOverview(Long restaurantId, AnalyticsV2TimeRange timeRange) {
        if (restaurantId == null) {
            return new AnalyticsV2OverviewDTO(
                    0L,
                    DecimalScaleV2.money(null),
                    DecimalScaleV2.money(null),
                    DecimalScaleV2.scaled(null, 4)
            );
        }
        return metricsRepository.fetchOverview(restaurantId, timeRange);
    }

    public List<AnalyticsV2DishMetricsDTO> getDishMetrics(Long restaurantId, AnalyticsV2TimeRange timeRange) {
        if (restaurantId == null) {
            return List.of();
        }
        return metricsRepository.fetchDishMetrics(restaurantId, false, timeRange);
    }

    public AnalyticsV2DashboardDTO getDashboard(Long restaurantId, AnalyticsV2TimeRange timeRange) {
        return new AnalyticsV2DashboardDTO(getOverview(restaurantId, timeRange), getDishMetrics(restaurantId, timeRange));
    }
}