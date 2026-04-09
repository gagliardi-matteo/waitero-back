package com.waitero.back.controller;

import com.waitero.back.dto.AnalyticsDashboardDTO;
import com.waitero.back.dto.AnalyticsOverviewDTO;
import com.waitero.back.dto.BenchmarkInsightDTO;
import com.waitero.back.dto.DishPerformanceDTO;
import com.waitero.back.dto.RevenueOpportunityDTO;
import com.waitero.back.security.AccessContextService;
import com.waitero.back.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;
    private final AccessContextService accessContextService;

    @GetMapping("/overview")
    public AnalyticsOverviewDTO getOverview() {
        return analyticsService.getOverview(accessContextService.getActingRestaurantIdOrThrow());
    }

    @GetMapping("/dashboard")
    public AnalyticsDashboardDTO getDashboard() {
        return analyticsService.getDashboard(accessContextService.getActingRestaurantIdOrThrow());
    }

    @GetMapping("/dish-performance")
    public List<DishPerformanceDTO> getDishPerformance() {
        return analyticsService.getDishPerformance(accessContextService.getActingRestaurantIdOrThrow());
    }

    @GetMapping("/revenue-opportunities")
    public List<RevenueOpportunityDTO> getRevenueOpportunities() {
        return analyticsService.getRevenueOpportunities(accessContextService.getActingRestaurantIdOrThrow());
    }

    @GetMapping("/benchmarks")
    public List<BenchmarkInsightDTO> getBenchmarkInsights() {
        return analyticsService.getBenchmarkInsights(accessContextService.getActingRestaurantIdOrThrow());
    }
}
