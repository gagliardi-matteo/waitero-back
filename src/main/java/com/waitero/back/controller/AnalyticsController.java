package com.waitero.back.controller;

import com.waitero.back.dto.AnalyticsOverviewDTO;
import com.waitero.back.dto.BenchmarkInsightDTO;
import com.waitero.back.dto.DishPerformanceDTO;
import com.waitero.back.dto.RevenueOpportunityDTO;
import com.waitero.back.service.AnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @GetMapping("/overview")
    public AnalyticsOverviewDTO getOverview() {
        Long restaurantId = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
        return analyticsService.getOverview(restaurantId);
    }

    @GetMapping("/dish-performance")
    public List<DishPerformanceDTO> getDishPerformance() {
        Long restaurantId = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
        return analyticsService.getDishPerformance(restaurantId);
    }

    @GetMapping("/revenue-opportunities")
    public List<RevenueOpportunityDTO> getRevenueOpportunities() {
        Long restaurantId = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
        return analyticsService.getRevenueOpportunities(restaurantId);
    }

    @GetMapping("/benchmarks")
    public List<BenchmarkInsightDTO> getBenchmarkInsights() {
        Long restaurantId = Long.parseLong(SecurityContextHolder.getContext().getAuthentication().getName());
        return analyticsService.getBenchmarkInsights(restaurantId);
    }
}
